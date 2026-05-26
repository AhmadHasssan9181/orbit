/*
 * Copyright 2021 Alex Andres
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "JNI_AudioResampler.h"
#include "Exception.h"
#include "JavaObject.h"
#include "JavaRef.h"
#include "JavaUtils.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioResampler_dispose(JNIEnv * env, jobject caller) {}
JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioResampler_initialize__
(JNIEnv * env, jobject caller) {}
JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioResampler_initialize__III
(JNIEnv* env, jobject caller, jint srcSamplesPerChannel, jint dstSamplesPerChannel, jint channels) {}
JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_audio_AudioResampler_resampleInternal
(JNIEnv * env, jobject caller, jbyteArray samplesIn, jint srcSamplesPerChannel, jbyteArray samplesOut, jint dstSamplesPerChannel, jint channels) { return 0; }
