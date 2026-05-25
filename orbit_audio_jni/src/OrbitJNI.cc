#include <jni.h>
#include "OrbitAudioDeviceModule.h"
#include "api/scoped_refptr.h"
#include "rtc_base/ref_counted_object.h"

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

    // 4. Read the old handle (we DO NOT delete it here, because it was allocated by a different DLL's CRT, which causes EXCEPTION_ACCESS_VIOLATION on Windows!)
    jlong old_handle = env->GetLongField(java_adm, handle_fid);
    // intentional leak of the 8-byte pointer to prevent CRT heap corruption

    // 5. Inject our new handle! 
    env->SetLongField(java_adm, handle_fid, reinterpret_cast<jlong>(adm_ptr));
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

} // extern "C"
