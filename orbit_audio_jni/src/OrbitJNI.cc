#include <jni.h>
#include "OrbitAudioDeviceModule.h"
#include "OrbitVideoProcessor.h"
#include "api/scoped_refptr.h"
#include "rtc_base/ref_counted_object.h"
#include <comdef.h>
#include <audiopolicy.h>
#include <mmdeviceapi.h>

extern "C" {

JNIEXPORT void JNICALL
Java_webrtc_OrbitAudioEngine_injectCustomAdm(JNIEnv* env, jobject /* this */, jobject java_adm) {
    // 1. Create our custom ADM using direct initialization because the constructor is explicit
    webrtc::scoped_refptr<webrtc::AudioDeviceModule> custom_adm(
        new webrtc::RefCountedObject<orbit::OrbitAudioDeviceModule>()
    );

    // 2. Allocate a new pointer to the scoped_refptr on the heap (this is exactly what webrtc-java does)
    auto* adm_ptr = new webrtc::scoped_refptr<webrtc::AudioDeviceModule>(custom_adm);

    // 3. Find the nativeHandle field in NativeObject
    jclass native_obj_class = env->FindClass("dev/onvoid/webrtc/internal/NativeObject");
    if (!native_obj_class) return;

    jfieldID handle_fid = env->GetFieldID(native_obj_class, "nativeHandle", "J");
    if (!handle_fid) return;

    // 4. Read the old handle
    jlong old_handle = env->GetLongField(java_adm, handle_fid);

    // 5. Inject our new handle! 
    env->SetLongField(java_adm, handle_fid, reinterpret_cast<jlong>(adm_ptr));
}

JNIEXPORT void JNICALL
Java_webrtc_OrbitAudioEngine_setOrbitZeroEnabled(JNIEnv* env, jobject /* this */, jobject java_adm, jboolean enabled) {
    // Stub for now to satisfy JNI
}

JNIEXPORT void JNICALL
Java_webrtc_OrbitVideoEngine_injectVideoProcessor(JNIEnv* env, jobject /* this */, jobject java_source, jstring model_path) {
    jclass native_obj_class = env->FindClass("dev/onvoid/webrtc/internal/NativeObject");
    if (!native_obj_class) return;

    jfieldID handle_fid = env->GetFieldID(native_obj_class, "nativeHandle", "J");
    if (!handle_fid) return;

    jlong handle = env->GetLongField(java_source, handle_fid);
    if (handle == 0) return;

    auto* source_ptr = reinterpret_cast<webrtc::scoped_refptr<webrtc::VideoTrackSourceInterface>*>(handle);
    
    const char* path_str = env->GetStringUTFChars(model_path, nullptr);
    std::string model_path_cpp(path_str);
    env->ReleaseStringUTFChars(model_path, path_str);

    // Wrap the existing source with our processor source
    webrtc::scoped_refptr<orbit::OrbitVideoProcessorSource> processor_source(
        new webrtc::RefCountedObject<orbit::OrbitVideoProcessorSource>(*source_ptr)
    );
    processor_source->GetProcessor()->LoadModel(model_path_cpp);

    // Replace the handle with our new wrapped source
    auto* new_source_ptr = new webrtc::scoped_refptr<webrtc::VideoTrackSourceInterface>(processor_source);
    env->SetLongField(java_source, handle_fid, reinterpret_cast<jlong>(new_source_ptr));
}

JNIEXPORT void JNICALL
Java_webrtc_OrbitAudioEngine_setSystemAudioShareEnabled(JNIEnv* env, jobject /* this */, jobject java_adm, jboolean enabled) {
    // Find the nativeHandle to get our custom ADM
    jclass native_obj_class = env->FindClass("dev/onvoid/webrtc/internal/NativeObject");
    if (!native_obj_class) return;
    
    jfieldID handle_fid = env->GetFieldID(native_obj_class, "nativeHandle", "J");
    if (!handle_fid) return;

    jlong handle = env->GetLongField(java_adm, handle_fid);
    if (handle != 0) {
        auto* adm_ptr = reinterpret_cast<webrtc::scoped_refptr<webrtc::AudioDeviceModule>*>(handle);
        // Cast to our implementation. 
        orbit::OrbitAudioDeviceModule* custom_adm = static_cast<orbit::OrbitAudioDeviceModule*>((*adm_ptr).get());
        if (custom_adm) {
            custom_adm->SetSystemAudioShareEnabled(enabled);
        }
    }
}

JNIEXPORT void JNICALL
Java_webrtc_OrbitAudioEngine_disableWindowsDucking(JNIEnv* env, jobject /* this */) {
    CoInitializeEx(NULL, COINIT_MULTITHREADED);
    IMMDeviceEnumerator* deviceEnumerator = NULL;
    IMMDevice* defaultDevice = NULL;
    IAudioSessionManager2* sessionManager = NULL;
    IAudioSessionControl* sessionControl = NULL;
    IAudioSessionControl2* sessionControl2 = NULL;

    if (SUCCEEDED(CoCreateInstance(__uuidof(MMDeviceEnumerator), NULL, CLSCTX_INPROC_SERVER, 
        __uuidof(IMMDeviceEnumerator), (void**)&deviceEnumerator))) {
        if (SUCCEEDED(deviceEnumerator->GetDefaultAudioEndpoint(eRender, eCommunications, &defaultDevice))) {
            if (SUCCEEDED(defaultDevice->Activate(__uuidof(IAudioSessionManager2), CLSCTX_INPROC_SERVER, NULL, 
                (void**)&sessionManager))) {
                if (SUCCEEDED(sessionManager->GetAudioSessionControl(NULL, 0, &sessionControl))) {
                    if (SUCCEEDED(sessionControl->QueryInterface(__uuidof(IAudioSessionControl2), 
                        (void**)&sessionControl2))) {
                        sessionControl2->SetDuckingPreference(TRUE); // TRUE means opt out of ducking
                        sessionControl2->Release();
                    }
                    sessionControl->Release();
                }
                sessionManager->Release();
            }
            defaultDevice->Release();
        }
        deviceEnumerator->Release();
    }
    CoUninitialize();
}

} // extern "C"
