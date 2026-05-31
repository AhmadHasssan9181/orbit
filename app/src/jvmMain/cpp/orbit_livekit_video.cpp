#include <jni.h>
#include <iostream>
#include <cstring>
#include <algorithm>

extern "C" {

JNIEXPORT void JNICALL
Java_livekit_LiveKitVideoSink_nativeInit(JNIEnv* env, jclass clazz) {
    std::cout << "DEBUG [C++] Orbit LiveKit Video Bridge Initialized" << std::endl;
}

inline uint8_t clamp(int v) {
    return (uint8_t)std::clamp(v, 0, 255);
}

JNIEXPORT void JNICALL
Java_livekit_LiveKitVideoSink_nativePushFrame(
    JNIEnv* env, jclass clazz,
    jobject byteBuffer, jint width, jint height,
    jlong yPtr, jint yStride, jint ySize,
    jlong uPtr, jint uStride, jint uSize,
    jlong vPtr, jint vStride, jint vSize
) {
    uint32_t* dst = (uint32_t*)env->GetDirectBufferAddress(byteBuffer);
    if (!dst) return;

    const uint8_t* py = (const uint8_t*)yPtr;
    const uint8_t* pu = (const uint8_t*)uPtr;
    const uint8_t* pv = (const uint8_t*)vPtr;

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int Y = py[y * yStride + x];
            int U = pu[(y / 2) * uStride + (x / 2)] - 128;
            int V = pv[(y / 2) * vStride + (x / 2)] - 128;

            // Standard I420 to RGB conversion
            int r = Y + (1.370705 * V);
            int g = Y - (0.337633 * U) - (0.698001 * V);
            int b = Y + (1.732446 * U);

            // Pack into ARGB (Little Endian: BGRA, Big Endian: ARGB)
            // BufferedImage.TYPE_INT_ARGB is 0xAARRGGBB in Java (Big Endian)
            // On x64 (Little Endian), uint32_t 0xAARRGGBB is stored as [BB, GG, RR, AA]
            dst[y * width + x] = (0xFF << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
        }
    }
}

JNIEXPORT jlong JNICALL
Java_livekit_LiveKitLocalTrack_getBufferAddress(JNIEnv* env, jclass clazz, jobject byteBuffer) {
    return (jlong)env->GetDirectBufferAddress(byteBuffer);
}

}
