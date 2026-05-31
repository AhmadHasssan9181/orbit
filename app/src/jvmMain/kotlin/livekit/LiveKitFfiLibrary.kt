package livekit

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

interface LiveKitFfiLibrary : Library {
    
    // extern "C" fn event_callback(data_ptr: *const u8, len: usize)
    interface FfiCallbackFn : Callback {
        fun invoke(dataPtr: Pointer?, len: Long)
    }

    // void livekit_ffi_initialize(FfiCallbackFn cb, bool capture_logs, const char* sdk, const char* sdk_version)
    fun livekit_ffi_initialize(
        cb: FfiCallbackFn,
        captureLogs: Boolean,
        sdk: String?,
        sdkVersion: String?
    )

    // FfiHandleId livekit_ffi_request(const uint8_t *data, size_t len,
    //                                 const uint8_t **res_ptr, size_t *res_len);
    fun livekit_ffi_request(
        data: Pointer?,
        len: Long,
        res_ptr: PointerByReference,
        res_len: LongByReference
    ): Long

    // bool livekit_ffi_drop_handle(FfiHandleId handle_id);
    fun livekit_ffi_drop_handle(handleId: Long): Boolean

    companion object {
        val INSTANCE: LiveKitFfiLibrary by lazy {
            val libPath = webrtc.WebrtcNativeDepsLoader.livekitFfiPath ?: "livekit_ffi"
            Native.load(libPath, LiveKitFfiLibrary::class.java) as LiveKitFfiLibrary
        }
    }
}
