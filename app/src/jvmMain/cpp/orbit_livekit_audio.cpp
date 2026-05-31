#include <jni.h>

#include "livekit/livekit.h"

#include <exception>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>

namespace {

std::once_flag g_initializeOnce;
std::mutex g_sessionMutex;
std::unique_ptr<livekit::Room> g_room;
std::shared_ptr<livekit::AudioSource> g_audioSource;
std::shared_ptr<livekit::LocalAudioTrack> g_audioTrack;
bool g_connected = false;

void initializeSdkOnce() {
    std::call_once(g_initializeOnce, [] {
        livekit::initialize(livekit::LogSink::kConsole);
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

void resetRoomLocked() {
    g_audioTrack.reset();
    g_audioSource.reset();
    if (g_room != nullptr) {
        g_room->setDelegate(nullptr);
    }
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
        livekit::RoomOptions options;
        options.auto_subscribe = true;
        options.dynacast = false;

        g_connected = g_room->Connect(urlString, tokenString, options);
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
    JNIEnv*, jclass, jobject, jint, jint, jint, jint) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativeSetRemoteAudioSink(JNIEnv*, jclass, jobject) {}
