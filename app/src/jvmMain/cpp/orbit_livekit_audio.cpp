#include <jni.h>

#include "livekit/livekit.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <exception>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

std::once_flag g_initializeOnce;
std::mutex g_sessionMutex;
std::unique_ptr<livekit::Room> g_room;
std::unique_ptr<livekit::RoomDelegate> g_delegate;
std::shared_ptr<livekit::AudioSource> g_audioSource;
std::shared_ptr<livekit::LocalAudioTrack> g_audioTrack;
bool g_connected = false;

JavaVM* g_javaVm = nullptr;
std::mutex g_sinkMutex;
jobject g_remoteAudioSink = nullptr;
jmethodID g_remoteAudioSinkMethod = nullptr;

struct RemoteAudioReader {
    std::string trackSid;
    std::shared_ptr<livekit::AudioStream> stream;
    std::thread thread;
    std::atomic_bool running{true};
};

std::vector<std::shared_ptr<RemoteAudioReader>> g_remoteAudioReaders;

void initializeSdkOnce() {
    std::call_once(g_initializeOnce, [] {
        livekit::initialize(livekit::LogLevel::Info, livekit::LogSink::kConsole);
        std::cout << "DEBUG [OrbitLiveKitAudio] LiveKit C++ SDK initialized" << std::endl;
    });
}

std::string jStringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }

    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }

    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

bool getJniEnv(JNIEnv** env, bool* attached) {
    *env = nullptr;
    *attached = false;
    if (g_javaVm == nullptr) {
        return false;
    }

    const jint getEnvResult = g_javaVm->GetEnv(reinterpret_cast<void**>(env), JNI_VERSION_1_8);
    if (getEnvResult == JNI_OK) {
        return true;
    }
    if (getEnvResult != JNI_EDETACHED) {
        return false;
    }

    if (g_javaVm->AttachCurrentThread(reinterpret_cast<void**>(env), nullptr) != JNI_OK) {
        return false;
    }
    *attached = true;
    return true;
}

void dispatchRemoteAudioFrame(const livekit::AudioFrame& frame) {
    JNIEnv* env = nullptr;
    bool attached = false;
    if (!getJniEnv(&env, &attached) || env == nullptr) {
        return;
    }

    jobject sink = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_sinkMutex);
        if (g_remoteAudioSink != nullptr && g_remoteAudioSinkMethod != nullptr) {
            sink = env->NewLocalRef(g_remoteAudioSink);
            method = g_remoteAudioSinkMethod;
        }
    }

    if (sink != nullptr && method != nullptr) {
        const auto& samples = frame.data();
        const auto byteLength = static_cast<jsize>(samples.size() * sizeof(std::int16_t));
        jbyteArray pcm = env->NewByteArray(byteLength);
        if (pcm != nullptr) {
            env->SetByteArrayRegion(
                pcm,
                0,
                byteLength,
                reinterpret_cast<const jbyte*>(samples.data()));
            env->CallVoidMethod(
                sink,
                method,
                pcm,
                static_cast<jint>(frame.sampleRate()),
                static_cast<jint>(frame.numChannels()),
                static_cast<jint>(frame.samplesPerChannel()));
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            env->DeleteLocalRef(pcm);
        }
        env->DeleteLocalRef(sink);
    }

    if (attached) {
        g_javaVm->DetachCurrentThread();
    }
}

void stopReader(const std::shared_ptr<RemoteAudioReader>& reader) {
    if (reader == nullptr) {
        return;
    }

    reader->running = false;
    if (reader->stream != nullptr) {
        reader->stream->close();
    }
    if (reader->thread.joinable()) {
        if (reader->thread.get_id() == std::this_thread::get_id()) {
            reader->thread.detach();
        } else {
            reader->thread.join();
        }
    }
}

void stopAllRemoteReadersLocked() {
    auto readers = std::move(g_remoteAudioReaders);
    g_remoteAudioReaders.clear();
    for (const auto& reader : readers) {
        stopReader(reader);
    }
}

void startRemoteReaderLocked(const std::shared_ptr<livekit::Track>& track) {
    if (track == nullptr || track->kind() != livekit::TrackKind::KIND_AUDIO) {
        return;
    }

    try {
        livekit::AudioStream::Options options;
        options.capacity = 20;

        auto reader = std::make_shared<RemoteAudioReader>();
        reader->trackSid = track->sid();
        reader->stream = livekit::AudioStream::fromTrack(track, options);
        reader->thread = std::thread([reader] {
            livekit::AudioFrameEvent event;
            while (reader->running && reader->stream != nullptr && reader->stream->read(event)) {
                dispatchRemoteAudioFrame(event.frame);
            }
        });
        g_remoteAudioReaders.push_back(reader);
        std::cout << "DEBUG [OrbitLiveKitAudio] Started remote audio stream for track "
                  << reader->trackSid << std::endl;
    } catch (const std::exception& error) {
        std::cerr << "ERROR [OrbitLiveKitAudio] Failed to start remote audio stream: "
                  << error.what() << std::endl;
    }
}

void stopRemoteReaderLocked(const std::string& trackSid) {
    auto it = std::remove_if(
        g_remoteAudioReaders.begin(),
        g_remoteAudioReaders.end(),
        [&](const std::shared_ptr<RemoteAudioReader>& reader) {
            if (reader != nullptr && reader->trackSid == trackSid) {
                stopReader(reader);
                return true;
            }
            return false;
        });
    g_remoteAudioReaders.erase(it, g_remoteAudioReaders.end());
}

class OrbitAudioRoomDelegate final : public livekit::RoomDelegate {
public:
    void onTrackSubscribed(livekit::Room&, const livekit::TrackSubscribedEvent& event) override {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        startRemoteReaderLocked(event.track);
    }

    void onTrackUnsubscribed(livekit::Room&, const livekit::TrackUnsubscribedEvent& event) override {
        if (event.track == nullptr) {
            return;
        }
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        stopRemoteReaderLocked(event.track->sid());
    }

    void onDisconnected(livekit::Room&, const livekit::DisconnectedEvent&) override {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        stopAllRemoteReadersLocked();
        g_connected = false;
    }
};

void resetRoomLocked() {
    stopAllRemoteReadersLocked();
    g_audioTrack.reset();
    g_audioSource.reset();
    if (g_room != nullptr) {
        g_room->setDelegate(nullptr);
    }
    g_delegate.reset();
    g_room.reset();
    g_connected = false;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativeSdkVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("livekit-cpp-sdk-0.4.1");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativeConnect(JNIEnv* env, jclass, jstring url, jstring token) {
    const std::string urlString = jStringToString(env, url);
    const std::string tokenString = jStringToString(env, token);
    if (urlString.empty() || tokenString.empty()) {
        std::cerr << "ERROR [OrbitLiveKitAudio] Missing LiveKit URL or token" << std::endl;
        return JNI_FALSE;
    }

    try {
        initializeSdkOnce();

        std::lock_guard<std::mutex> lock(g_sessionMutex);
        resetRoomLocked();

        g_room = std::make_unique<livekit::Room>();
        g_delegate = std::make_unique<OrbitAudioRoomDelegate>();
        g_room->setDelegate(g_delegate.get());
        livekit::RoomOptions options;
        options.auto_subscribe = true;
        options.dynacast = false;

        g_connected = g_room->connect(urlString, tokenString, options);
        if (!g_connected) {
            resetRoomLocked();
            std::cerr << "ERROR [OrbitLiveKitAudio] LiveKit C++ room connect failed" << std::endl;
            return JNI_FALSE;
        }

        std::cout << "DEBUG [OrbitLiveKitAudio] Connected LiveKit C++ room" << std::endl;
        return JNI_TRUE;
    } catch (const std::exception& error) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        resetRoomLocked();
        std::cerr << "ERROR [OrbitLiveKitAudio] Connect threw: " << error.what() << std::endl;
        return JNI_FALSE;
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        resetRoomLocked();
        std::cerr << "ERROR [OrbitLiveKitAudio] Connect threw unknown error" << std::endl;
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativeDisconnect(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    resetRoomLocked();
    std::cout << "DEBUG [OrbitLiveKitAudio] Disconnected LiveKit C++ room" << std::endl;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativePublishAudio(
    JNIEnv*, jclass, jint sampleRate, jint channels, jint frameMs) {
    if (sampleRate <= 0 || channels <= 0 || frameMs <= 0) {
        std::cerr << "ERROR [OrbitLiveKitAudio] Invalid audio publish format" << std::endl;
        return JNI_FALSE;
    }

    try {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        if (!g_connected || g_room == nullptr || g_room->localParticipant() == nullptr) {
            std::cerr << "ERROR [OrbitLiveKitAudio] Cannot publish audio before room connect" << std::endl;
            return JNI_FALSE;
        }

        g_audioTrack.reset();
        g_audioSource = std::make_shared<livekit::AudioSource>(
            static_cast<int>(sampleRate),
            static_cast<int>(channels),
            static_cast<int>(frameMs));
        g_audioTrack = g_room->localParticipant()->publishAudioTrack(
            "microphone",
            g_audioSource,
            livekit::TrackSource::SOURCE_MICROPHONE);

        std::cout << "DEBUG [OrbitLiveKitAudio] Published C++ microphone audio track" << std::endl;
        return JNI_TRUE;
    } catch (const std::exception& error) {
        g_audioTrack.reset();
        g_audioSource.reset();
        std::cerr << "ERROR [OrbitLiveKitAudio] Publish audio threw: " << error.what() << std::endl;
        return JNI_FALSE;
    } catch (...) {
        g_audioTrack.reset();
        g_audioSource.reset();
        std::cerr << "ERROR [OrbitLiveKitAudio] Publish audio threw unknown error" << std::endl;
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativePushAudioFrame(
    JNIEnv* env,
    jclass,
    jobject pcm,
    jint byteLength,
    jint sampleRate,
    jint channels,
    jint samplesPerChannel) {
    if (pcm == nullptr || byteLength <= 0 || sampleRate <= 0 || channels <= 0 || samplesPerChannel <= 0) {
        return JNI_FALSE;
    }

    void* data = env->GetDirectBufferAddress(pcm);
    if (data == nullptr) {
        std::cerr << "ERROR [OrbitLiveKitAudio] Audio frame buffer must be direct" << std::endl;
        return JNI_FALSE;
    }

    const int expectedBytes = samplesPerChannel * channels * static_cast<jint>(sizeof(std::int16_t));
    if (byteLength < expectedBytes || byteLength % static_cast<jint>(sizeof(std::int16_t)) != 0) {
        std::cerr << "ERROR [OrbitLiveKitAudio] Invalid audio frame byte length" << std::endl;
        return JNI_FALSE;
    }

    std::shared_ptr<livekit::AudioSource> source;
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        source = g_audioSource;
    }
    if (source == nullptr) {
        return JNI_FALSE;
    }

    try {
        std::vector<std::int16_t> samples(static_cast<std::size_t>(expectedBytes / sizeof(std::int16_t)));
        std::memcpy(samples.data(), data, static_cast<std::size_t>(expectedBytes));

        livekit::AudioFrame frame(
            std::move(samples),
            static_cast<int>(sampleRate),
            static_cast<int>(channels),
            static_cast<int>(samplesPerChannel));
        source->captureFrame(frame, 20);
        return JNI_TRUE;
    } catch (const std::exception& error) {
        std::cerr << "ERROR [OrbitLiveKitAudio] Push audio frame threw: " << error.what() << std::endl;
        return JNI_FALSE;
    } catch (...) {
        std::cerr << "ERROR [OrbitLiveKitAudio] Push audio frame threw unknown error" << std::endl;
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativeSetRemoteAudioSink(JNIEnv* env, jclass, jobject sink) {
    if (g_javaVm == nullptr) {
        env->GetJavaVM(&g_javaVm);
    }

    std::lock_guard<std::mutex> lock(g_sinkMutex);
    if (g_remoteAudioSink != nullptr) {
        env->DeleteGlobalRef(g_remoteAudioSink);
        g_remoteAudioSink = nullptr;
        g_remoteAudioSinkMethod = nullptr;
    }

    if (sink == nullptr) {
        return;
    }

    jobject newSink = env->NewGlobalRef(sink);
    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID method = env->GetMethodID(sinkClass, "onAudioFrame", "([BIII)V");
    env->DeleteLocalRef(sinkClass);

    if (method == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteGlobalRef(newSink);
        std::cerr << "ERROR [OrbitLiveKitAudio] RemoteAudioSink.onAudioFrame not found" << std::endl;
        return;
    }

    g_remoteAudioSink = newSink;
    g_remoteAudioSinkMethod = method;
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    g_javaVm = vm;
    return JNI_VERSION_1_8;
}
