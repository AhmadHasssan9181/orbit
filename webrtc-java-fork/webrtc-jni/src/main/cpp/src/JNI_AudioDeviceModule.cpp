/*
 * Copyright 2019 Alex Andres
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

#include "JNI_AudioDeviceModule.h"
#include "Exception.h"
#include "JavaEnums.h"
#include "JavaError.h"
#include "JavaRef.h"
#include "JavaUtils.h"
#include "WebRTCContext.h"
#include "api/audio/create_audio_device_module.h"

#ifdef _WIN32
#include "media/audio/windows/OrbitAudioDeviceModule.h"
#endif

#include "api/scoped_refptr.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_initialize
(JNIEnv * env, jobject caller, jobject jAudioLayer)
{
	jni::WebRTCContext * context = static_cast<jni::WebRTCContext*>(javaContext);
	auto audioLayer = jni::JavaEnums::toNative<webrtc::AudioDeviceModule::AudioLayer>(env, jAudioLayer);

#ifdef _WIN32
	webrtc::scoped_refptr<webrtc::AudioDeviceModule> audioModule(new orbit::OrbitAudioDeviceModule(webrtc::CreateAudioDeviceModule(context->webrtcEnv, audioLayer)));
#else
	webrtc::scoped_refptr<webrtc::AudioDeviceModule> audioModule = webrtc::CreateAudioDeviceModule(context->webrtcEnv, audioLayer);
#endif

	if (!audioModule) {
		env->Throw(jni::JavaError(env, "Create AudioDeviceModule failed"));
		return;
	}

	if (audioModule->Init() != 0) {
		env->Throw(jni::JavaError(env, "Initialize AudioDeviceModule failed"));
		return;
	}

	SetHandle(env, caller, audioModule.release());
}

extern "C" JNIEXPORT void JNICALL Java_webrtc_OrbitAudioDeviceModule_setSystemAudioShareEnabled
(JNIEnv * env, jobject caller, jboolean enabled)
{
#ifdef _WIN32
	auto * audioModule = GetHandle<orbit::OrbitAudioDeviceModule>(env, caller);
	if (audioModule) {
		audioModule->SetSystemAudioShareEnabled(enabled);
	}
#endif
}

extern "C" JNIEXPORT void JNICALL Java_webrtc_OrbitAudioDeviceModule_setOrbitZeroEnabled
(JNIEnv * env, jobject caller, jboolean enabled)
{
#ifdef _WIN32
	auto * audioModule = GetHandle<orbit::OrbitAudioDeviceModule>(env, caller);
	if (audioModule) {
		audioModule->SetOrbitZeroEnabled(enabled);
	}
#endif
}

extern "C" JNIEXPORT void JNICALL Java_webrtc_OrbitAudioDeviceModule_setAttenuationStrength
(JNIEnv * env, jobject caller, jfloat strength)
{
#ifdef _WIN32
	auto * audioModule = GetHandle<orbit::OrbitAudioDeviceModule>(env, caller);
	if (audioModule) {
		audioModule->SetAttenuationStrength(strength);
	}
#endif
}

