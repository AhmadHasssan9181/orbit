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
bool g_micPublished = false;
bool g_acceptingFrames = false;
bool g_firstAudioFrameAccepted = false;
bool g_frameDroppedNotReadyLogged = false;

JavaVM* g_javaVm = nullptr;
std::mutex g_sinkMutex;
jobject g_remoteAudioSink = nullptr;
jmethodID g_remoteAudioSinkMethod = nullptr;

std::mutex g_eventSinkMutex;
jobject g_eventSink = nullptr;
jmethodID g_eventSinkMethod = nullptr;

struct RemoteAudioReader {
    std::string trackSid;
    std::shared_ptr<livekit::AudioStream> stream;
    std::thread thread;
    std::atomic_bool running{true};
};

std::vector<std::shared_ptr<RemoteAudioReader>> g_remoteAudioReaders;

constexpr jint kSampleRate = 48000;
constexpr jint kChannels = 1;
constexpr jint kFrameMs = 10;
constexpr jint kSamplesPerChannel = 480;
constexpr jint kFrameBytes = 960;

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
        g_acceptingFrames = false;
        g_micPublished = false;
        stopAllRemoteReadersLocked();
        g_connected = false;
    }
};

void resetRoomLocked() {
    g_acceptingFrames = false;
    stopAllRemoteReadersLocked();
    g_audioTrack.reset();
    g_audioSource.reset();
    if (g_room != nullptr) {
        g_room->setDelegate(nullptr);
    }
    g_delegate.reset();
    g_room.reset();
    g_connected = false;
    g_micPublished = false;
    g_firstAudioFrameAccepted = false;
    g_frameDroppedNotReadyLogged = false;
}

bool hasReadyMicLocked() {
    return g_connected &&
        g_micPublished &&
        g_acceptingFrames &&
        g_room != nullptr &&
        g_audioSource != nullptr &&
        g_audioTrack != nullptr;
}

void logFrameDroppedNotReadyLocked() {
    if (!g_frameDroppedNotReadyLogged) {
        g_frameDroppedNotReadyLogged = true;
        std::cout << "DEBUG [OrbitLiveKitAudio] frame dropped because native audio not ready" << std::endl;
    }
}

constexpr jint kStatusOk = 0;
constexpr jint kStatusConnectFailed = 2;
constexpr jint kStatusPublishAudioFailed = 6;
constexpr jint kStatusPublishVideoFailed = 7;
constexpr jint kStatusAudioCaptureFailed = 8;
constexpr jint kStatusVideoCaptureFailed = 9;
constexpr jint kStatusNativeException = 22;
constexpr jint kStatusStaleEvent = 23;

constexpr jlong kRawSessionId = 1;
jlong g_rawEpoch = 0;
bool g_rawHasLastError = false;
std::string g_rawLastErrorJson =
    R"({"code":"NONE","message":"","operation":"native","recoverable":false})";

std::string escapeJson(const std::string& input) {
    std::string output;
    output.reserve(input.size());
    for (const char c : input) {
        switch (c) {
            case '\\':
                output += "\\\\";
                break;
            case '"':
                output += "\\\"";
                break;
            case '\n':
                output += "\\n";
                break;
            case '\r':
                output += "\\r";
                break;
            case '\t':
                output += "\\t";
                break;
            default:
                output += c;
                break;
        }
    }
    return output;
}

void clearRawErrorLocked() {
    g_rawHasLastError = false;
    g_rawLastErrorJson =
        R"({"code":"NONE","message":"","operation":"native","recoverable":false})";
}

void setRawErrorLocked(
    const std::string& code,
    const std::string& message,
    const std::string& operation,
    bool recoverable) {
    g_rawHasLastError = true;
    g_rawLastErrorJson =
        std::string("{\"code\":\"") + escapeJson(code) +
        "\",\"message\":\"" + escapeJson(message) +
        "\",\"operation\":\"" + escapeJson(operation) +
        "\",\"recoverable\":" + (recoverable ? "true" : "false") + "}";
}

void dispatchNativeEvent(JNIEnv* env, jobject event) {
    if (env == nullptr || event == nullptr) {
        return;
    }

    jobject sink = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_eventSinkMutex);
        if (g_eventSink != nullptr && g_eventSinkMethod != nullptr) {
            sink = env->NewLocalRef(g_eventSink);
            method = g_eventSinkMethod;
        }
    }

    if (sink == nullptr || method == nullptr) {
        return;
    }

    env->CallVoidMethod(sink, method, event);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    env->DeleteLocalRef(sink);
}

void dispatchSimpleNativeEvent(JNIEnv* env, const char* className, jlong sessionId, jlong epoch) {
    if (env == nullptr) {
        return;
    }

    jclass eventClass = env->FindClass(className);
    if (eventClass == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        return;
    }

    jmethodID ctor = env->GetMethodID(eventClass, "<init>", "(JJ)V");
    if (ctor == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteLocalRef(eventClass);
        return;
    }

    jobject event = env->NewObject(eventClass, ctor, sessionId, epoch);
    if (event != nullptr) {
        dispatchNativeEvent(env, event);
        env->DeleteLocalRef(event);
    }
    env->DeleteLocalRef(eventClass);
}

jobject createNativeError(
    JNIEnv* env,
    const char* codeName,
    const std::string& message,
    const std::string& operation,
    bool recoverable) {
    if (env == nullptr) {
        return nullptr;
    }

    jclass errorCodeClass = env->FindClass("livekit/NativeErrorCode");
    jclass errorClass = env->FindClass("livekit/NativeError");
    if (errorCodeClass == nullptr || errorClass == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        return nullptr;
    }

    jfieldID codeField = env->GetStaticFieldID(errorCodeClass, codeName, "Llivekit/NativeErrorCode;");
    jmethodID ctor = env->GetMethodID(errorClass, "<init>", "(Llivekit/NativeErrorCode;Ljava/lang/String;Ljava/lang/String;Z)V");
    if (codeField == nullptr || ctor == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteLocalRef(errorCodeClass);
        env->DeleteLocalRef(errorClass);
        return nullptr;
    }

    jobject code = env->GetStaticObjectField(errorCodeClass, codeField);
    jstring jMessage = env->NewStringUTF(message.c_str());
    jstring jOperation = env->NewStringUTF(operation.c_str());
    jobject error = env->NewObject(errorClass, ctor, code, jMessage, jOperation, recoverable ? JNI_TRUE : JNI_FALSE);

    if (code != nullptr) env->DeleteLocalRef(code);
    if (jMessage != nullptr) env->DeleteLocalRef(jMessage);
    if (jOperation != nullptr) env->DeleteLocalRef(jOperation);
    env->DeleteLocalRef(errorCodeClass);
    env->DeleteLocalRef(errorClass);
    return error;
}

void dispatchErrorNativeEvent(
    JNIEnv* env,
    const char* eventClassName,
    const char* errorCodeName,
    jlong sessionId,
    jlong epoch,
    const std::string& message,
    const std::string& operation,
    bool recoverable) {
    if (env == nullptr) {
        return;
    }

    jobject error = createNativeError(env, errorCodeName, message, operation, recoverable);
    if (error == nullptr) {
        return;
    }

    jclass eventClass = env->FindClass(eventClassName);
    if (eventClass == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteLocalRef(error);
        return;
    }

    jmethodID ctor = env->GetMethodID(eventClass, "<init>", "(JJLlivekit/NativeError;)V");
    if (ctor == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteLocalRef(error);
        env->DeleteLocalRef(eventClass);
        return;
    }

    jobject event = env->NewObject(eventClass, ctor, sessionId, epoch, error);
    if (event != nullptr) {
        dispatchNativeEvent(env, event);
        env->DeleteLocalRef(event);
    }
    env->DeleteLocalRef(error);
    env->DeleteLocalRef(eventClass);
}

void dispatchDisconnectedEvent(
    JNIEnv* env,
    jlong sessionId,
    jlong epoch,
    const std::string& reason,
    bool recoverable) {
    if (env == nullptr) {
        return;
    }

    jclass eventClass = env->FindClass("livekit/NativeMediaEvent$Disconnected");
    if (eventClass == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        return;
    }

    jmethodID ctor = env->GetMethodID(eventClass, "<init>", "(JJLjava/lang/String;Z)V");
    if (ctor == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteLocalRef(eventClass);
        return;
    }

    jstring jReason = env->NewStringUTF(reason.c_str());
    jobject event = env->NewObject(eventClass, ctor, sessionId, epoch, jReason, recoverable ? JNI_TRUE : JNI_FALSE);
    if (event != nullptr) {
        dispatchNativeEvent(env, event);
        env->DeleteLocalRef(event);
    }
    if (jReason != nullptr) env->DeleteLocalRef(jReason);
    env->DeleteLocalRef(eventClass);
}

bool isRawSessionValid(jlong sessionId) {
    return sessionId == kRawSessionId;
}

std::string rawReadinessJsonLocked(jlong sessionId) {
    const bool validSession = isRawSessionValid(sessionId);
    const bool connected = validSession && g_connected;
    const bool audioPublished = validSession && g_micPublished;
    const bool acceptingAudioFrames = validSession && hasReadyMicLocked();
    const char* state = "Idle";
    if (!validSession) {
        state = "Failed";
    } else if (connected && audioPublished && acceptingAudioFrames) {
        state = "MediaReady";
    } else if (connected && audioPublished) {
        state = "AudioPublished";
    } else if (connected) {
        state = "LiveKitConnected";
    }

    return std::string("{\"sessionId\":") + std::to_string(sessionId) +
        ",\"epoch\":" + std::to_string(g_rawEpoch) +
        ",\"state\":\"" + state +
        "\",\"connected\":" + (connected ? "true" : "false") +
        ",\"audioPublished\":" + (audioPublished ? "true" : "false") +
        ",\"videoPublished\":false" +
        ",\"acceptingAudioFrames\":" + (acceptingAudioFrames ? "true" : "false") +
        ",\"acceptingVideoFrames\":false" +
        ",\"cameraRequested\":false" +
        ",\"cameraRequired\":false" +
        ",\"cameraState\":\"NotRequested\"" +
        ",\"joinedAsAudioOnlyFallback\":false" +
        ",\"tokenRefreshScheduled\":false" +
        ",\"lastError\":" + (g_rawHasLastError ? g_rawLastErrorJson : "null") +
        "}";
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
        std::cout << "DEBUG [OrbitLiveKitAudio] native connect started" << std::endl;

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

        std::cout << "DEBUG [OrbitLiveKitAudio] native connect succeeded" << std::endl;
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
    g_acceptingFrames = false;
    resetRoomLocked();
    std::cout << "DEBUG [OrbitLiveKitAudio] disconnect cleanup completed" << std::endl;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativePublishAudio(
    JNIEnv*, jclass, jint sampleRate, jint channels, jint frameMs) {
    std::cout << "DEBUG [OrbitLiveKitAudio] mic publish started" << std::endl;
    if (sampleRate != kSampleRate || channels != kChannels || frameMs != kFrameMs) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_acceptingFrames = false;
        g_micPublished = false;
        std::cerr << "ERROR [OrbitLiveKitAudio] Invalid audio publish format; expected 48000 Hz mono 10 ms" << std::endl;
        return JNI_FALSE;
    }

    try {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_acceptingFrames = false;
        g_micPublished = false;
        g_firstAudioFrameAccepted = false;
        g_frameDroppedNotReadyLogged = false;

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

        if (g_audioSource == nullptr || g_audioTrack == nullptr) {
            g_audioTrack.reset();
            g_audioSource.reset();
            std::cerr << "ERROR [OrbitLiveKitAudio] Native C++ microphone publish returned no track" << std::endl;
            return JNI_FALSE;
        }

        g_micPublished = true;
        g_acceptingFrames = true;
        std::cout << "DEBUG [OrbitLiveKitAudio] mic publish succeeded" << std::endl;
        std::cout << "DEBUG [OrbitLiveKitAudio] acceptingFrames=true" << std::endl;
        return JNI_TRUE;
    } catch (const std::exception& error) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_acceptingFrames = false;
        g_micPublished = false;
        g_audioTrack.reset();
        g_audioSource.reset();
        std::cerr << "ERROR [OrbitLiveKitAudio] Publish audio threw: " << error.what() << std::endl;
        return JNI_FALSE;
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_acceptingFrames = false;
        g_micPublished = false;
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
    if (pcm == nullptr) {
        return JNI_FALSE;
    }

    if (byteLength != kFrameBytes ||
        sampleRate != kSampleRate ||
        channels != kChannels ||
        samplesPerChannel != kSamplesPerChannel) {
        return JNI_FALSE;
    }

    std::shared_ptr<livekit::AudioSource> source;
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        if (!hasReadyMicLocked()) {
            logFrameDroppedNotReadyLocked();
            return JNI_FALSE;
        }
        source = g_audioSource;
    }

    void* data = env->GetDirectBufferAddress(pcm);
    if (data == nullptr) {
        std::cerr << "ERROR [OrbitLiveKitAudio] Audio frame buffer must be direct" << std::endl;
        return JNI_FALSE;
    }

    const int expectedBytes = samplesPerChannel * channels * static_cast<jint>(sizeof(std::int16_t));

    try {
        std::vector<std::int16_t> samples(static_cast<std::size_t>(expectedBytes / sizeof(std::int16_t)));
        std::memcpy(samples.data(), data, static_cast<std::size_t>(expectedBytes));

        livekit::AudioFrame frame(
            std::move(samples),
            static_cast<int>(sampleRate),
            static_cast<int>(channels),
            static_cast<int>(samplesPerChannel));
        source->captureFrame(frame, 20);
        {
            std::lock_guard<std::mutex> lock(g_sessionMutex);
            if (!g_firstAudioFrameAccepted) {
                g_firstAudioFrameAccepted = true;
                std::cout << "DEBUG [OrbitLiveKitAudio] first audio frame accepted" << std::endl;
            }
        }
        return JNI_TRUE;
    } catch (const std::exception& error) {
        std::cerr << "ERROR [OrbitLiveKitAudio] Push audio frame threw: " << error.what() << std::endl;
        return JNI_FALSE;
    } catch (...) {
        std::cerr << "ERROR [OrbitLiveKitAudio] Push audio frame threw unknown error" << std::endl;
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativeIsAcceptingFrames(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    return hasReadyMicLocked() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativeSetAcceptingFrames(JNIEnv*, jclass, jboolean accepting) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    g_acceptingFrames = accepting == JNI_TRUE && g_connected && g_micPublished && g_audioSource != nullptr && g_audioTrack != nullptr;
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

extern "C" JNIEXPORT jlong JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeCreateSession(JNIEnv*, jclass) {
    initializeSdkOnce();
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    g_rawEpoch += 1;
    clearRawErrorLocked();
    return kRawSessionId;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeDestroySession(JNIEnv*, jclass, jlong sessionId) {
    if (!isRawSessionValid(sessionId)) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeDestroySession", false);
        return kStatusStaleEvent;
    }

    std::lock_guard<std::mutex> lock(g_sessionMutex);
    g_acceptingFrames = false;
    resetRoomLocked();
    clearRawErrorLocked();
    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeSetEventSink(JNIEnv* env, jclass, jobject sink) {
    if (g_javaVm == nullptr) {
        env->GetJavaVM(&g_javaVm);
    }

    {
        std::lock_guard<std::mutex> lock(g_eventSinkMutex);
        if (g_eventSink != nullptr) {
            env->DeleteGlobalRef(g_eventSink);
            g_eventSink = nullptr;
            g_eventSinkMethod = nullptr;
        }

        if (sink != nullptr) {
            jobject newSink = env->NewGlobalRef(sink);
            jclass sinkClass = env->GetObjectClass(sink);
            jmethodID method = env->GetMethodID(sinkClass, "onNativeEvent", "(Llivekit/NativeMediaEvent;)V");
            env->DeleteLocalRef(sinkClass);

            if (method == nullptr) {
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                }
                env->DeleteGlobalRef(newSink);
                std::lock_guard<std::mutex> sessionLock(g_sessionMutex);
                setRawErrorLocked("NATIVE_EXCEPTION", "NativeMediaEventSink.onNativeEvent not found", "nativeSetEventSink", false);
                return kStatusNativeException;
            }

            g_eventSink = newSink;
            g_eventSinkMethod = method;
        }
    }

    std::lock_guard<std::mutex> lock(g_sessionMutex);
    clearRawErrorLocked();
    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeSetMixedAudioSink(JNIEnv*, jclass, jobject) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    clearRawErrorLocked();
    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeSetRemoteVideoSink(JNIEnv*, jclass, jobject) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    clearRawErrorLocked();
    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeConnectAsync(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch,
    jstring url,
    jstring token,
    jstring) {
    if (!isRawSessionValid(sessionId)) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeConnectAsync", false);
        return kStatusStaleEvent;
    }

    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_rawEpoch = epoch;
        clearRawErrorLocked();
    }

    dispatchSimpleNativeEvent(env, "livekit/NativeMediaEvent$Connecting", sessionId, epoch);
    const jboolean connected =
        Java_livekit_LiveKitNativeAudioBridge_nativeConnect(env, nullptr, url, token);
    if (connected == JNI_TRUE) {
        dispatchSimpleNativeEvent(env, "livekit/NativeMediaEvent$Connected", sessionId, epoch);
        return kStatusOk;
    }

    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("CONNECT_FAILED", "LiveKit C++ room connect failed", "nativeConnectAsync", true);
    }
    dispatchErrorNativeEvent(
        env,
        "livekit/NativeMediaEvent$ConnectFailed",
        "CONNECT_FAILED",
        sessionId,
        epoch,
        "LiveKit C++ room connect failed",
        "nativeConnectAsync",
        true);
    return kStatusConnectFailed;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeReconnectAsync(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch,
    jstring url,
    jstring token) {
    return Java_livekit_OrbitLiveKitNativeRaw_nativeConnectAsync(
        env,
        nullptr,
        sessionId,
        epoch,
        url,
        token,
        nullptr);
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeDisconnect(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch,
    jboolean userInitiated) {
    if (!isRawSessionValid(sessionId)) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeDisconnect", false);
        return kStatusStaleEvent;
    }

    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_rawEpoch = epoch;
        g_acceptingFrames = false;
        resetRoomLocked();
        clearRawErrorLocked();
        std::cout << "DEBUG [OrbitLiveKitAudio] disconnect cleanup completed" << std::endl;
    }
    dispatchDisconnectedEvent(
        env,
        sessionId,
        epoch,
        userInitiated == JNI_TRUE ? "user-left" : "native-disconnect",
        userInitiated != JNI_TRUE);
    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativePublishAudio(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch,
    jint sampleRate,
    jint channels) {
    if (!isRawSessionValid(sessionId)) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativePublishAudio", false);
        return kStatusStaleEvent;
    }

    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_rawEpoch = epoch;
        clearRawErrorLocked();
    }

    const jboolean published =
        Java_livekit_LiveKitNativeAudioBridge_nativePublishAudio(env, nullptr, sampleRate, channels, kFrameMs);
    if (published == JNI_TRUE) {
        dispatchSimpleNativeEvent(env, "livekit/NativeMediaEvent$AudioPublished", sessionId, epoch);
        return kStatusOk;
    }

    std::lock_guard<std::mutex> lock(g_sessionMutex);
    setRawErrorLocked("PUBLISH_AUDIO_FAILED", "LiveKit C++ microphone publish failed", "nativePublishAudio", false);
    return kStatusPublishAudioFailed;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativePushAudioFrame(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch,
    jobject buffer,
    jint sampleRate,
    jint channels,
    jint samplesPerChannel,
    jlong) {
    if (!isRawSessionValid(sessionId)) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativePushAudioFrame", false);
        return kStatusStaleEvent;
    }

    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        if (epoch != g_rawEpoch) {
            setRawErrorLocked("STALE_EVENT", "Stale native media epoch", "nativePushAudioFrame", true);
            return kStatusStaleEvent;
        }
    }

    const jint byteLength = samplesPerChannel * channels * static_cast<jint>(sizeof(std::int16_t));
    const jboolean pushed =
        Java_livekit_LiveKitNativeAudioBridge_nativePushAudioFrame(
            env,
            nullptr,
            buffer,
            byteLength,
            sampleRate,
            channels,
            samplesPerChannel);
    if (pushed == JNI_TRUE) {
        return kStatusOk;
    }

    std::lock_guard<std::mutex> lock(g_sessionMutex);
    setRawErrorLocked("AUDIO_CAPTURE_FAILED", "Native audio frame was rejected", "nativePushAudioFrame", true);
    return kStatusAudioCaptureFailed;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativePublishVideo(
    JNIEnv*,
    jclass,
    jlong sessionId,
    jlong epoch,
    jint,
    jint,
    jint,
    jint) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    if (!isRawSessionValid(sessionId)) {
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativePublishVideo", false);
        return kStatusStaleEvent;
    }
    g_rawEpoch = epoch;
    setRawErrorLocked("PUBLISH_VIDEO_FAILED", "Native C++ video is not implemented in this session", "nativePublishVideo", true);
    return kStatusPublishVideoFailed;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativePushVideoFrame(
    JNIEnv*,
    jclass,
    jlong sessionId,
    jlong epoch,
    jobject,
    jint,
    jint,
    jint,
    jint,
    jint,
    jint,
    jlong,
    jint) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    if (!isRawSessionValid(sessionId)) {
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativePushVideoFrame", false);
        return kStatusStaleEvent;
    }
    if (epoch != g_rawEpoch) {
        setRawErrorLocked("STALE_EVENT", "Stale native media epoch", "nativePushVideoFrame", true);
        return kStatusStaleEvent;
    }
    setRawErrorLocked("VIDEO_CAPTURE_FAILED", "Native C++ video is not implemented in this session", "nativePushVideoFrame", true);
    return kStatusVideoCaptureFailed;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeSetAudioMuted(JNIEnv*, jclass, jlong sessionId, jlong epoch, jboolean) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    if (!isRawSessionValid(sessionId)) {
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeSetAudioMuted", false);
        return kStatusStaleEvent;
    }
    g_rawEpoch = epoch;
    clearRawErrorLocked();
    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeSetVideoMuted(JNIEnv*, jclass, jlong sessionId, jlong epoch, jboolean) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    if (!isRawSessionValid(sessionId)) {
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeSetVideoMuted", false);
        return kStatusStaleEvent;
    }
    g_rawEpoch = epoch;
    clearRawErrorLocked();
    return kStatusOk;
}

extern "C" JNIEXPORT jstring JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeGetLastErrorJson(JNIEnv* env, jclass, jlong) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    return env->NewStringUTF(g_rawLastErrorJson.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeGetReadinessJson(JNIEnv* env, jclass, jlong sessionId) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    const std::string readinessJson = rawReadinessJsonLocked(sessionId);
    return env->NewStringUTF(readinessJson.c_str());
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    g_javaVm = vm;
    return JNI_VERSION_1_8;
}
