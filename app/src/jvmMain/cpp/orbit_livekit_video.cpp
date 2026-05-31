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

            // Java's IntBuffer (Big Endian) reads 4 bytes as: (Byte0 << 24) | (Byte1 << 16) | (Byte2 << 8) | Byte3
            // So to get 0xAARRGGBB in Java, we must write:
            // Byte0 = A, Byte1 = R, Byte2 = G, Byte3 = B
            // On Little Endian C++, a uint32_t cast to bytes is [Byte3, Byte2, Byte1, Byte0].
            // But we are accessing via uint32_t*, so writing 0xAARRGGBB directly to dst[i] in C++
            // will write [BB, GG, RR, AA] in memory.
            // When Java reads it via Big-Endian IntBuffer, it reads [BB, GG, RR, AA] as 0xBBGGRRAA.
            // To make Java see 0xAARRGGBB, we must write 0xAARRGGBB in memory as [AA, RR, GG, BB].
            // To write [AA, RR, GG, BB] in Little Endian C++, we construct the uint32_t as:
            // (BB << 24) | (GG << 16) | (RR << 8) | AA
            uint8_t A = 0xFF;
            uint8_t R = clamp(r);
            uint8_t G = clamp(g);
            uint8_t B = clamp(b);
            dst[y * width + x] = (B << 24) | (G << 16) | (R << 8) | A;
        }
    }
}

JNIEXPORT jlong JNICALL
Java_livekit_LiveKitLocalTrack_getBufferAddress(JNIEnv* env, jclass clazz, jobject byteBuffer) {
    return (jlong)env->GetDirectBufferAddress(byteBuffer);
}

}
