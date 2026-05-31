package livekit

enum class NativeErrorCode {
    NONE,
    CONNECT_TIMEOUT,
    CONNECT_FAILED,
    TOKEN_EXPIRED,
    TOKEN_REFRESH_FAILED,
    AUTH_FAILED,
    PUBLISH_AUDIO_FAILED,
    PUBLISH_VIDEO_FAILED,
    AUDIO_CAPTURE_FAILED,
    VIDEO_CAPTURE_FAILED,
    AUDIO_DEVICE_UNAVAILABLE,
    VIDEO_DEVICE_UNAVAILABLE,
    CAMERA_PERMISSION_DENIED,
    CAMERA_DEVICE_BUSY,
    CAMERA_DRIVER_FAILURE,
    CAMERA_CAPABILITY_UNAVAILABLE,
    CAMERA_MODE_REJECTED,
    REMOTE_AUDIO_FAILED,
    REMOTE_VIDEO_FAILED,
    DISCONNECTED,
    USER_LEFT,
    RECONNECT_FAILED,
    NATIVE_EXCEPTION,
    STALE_EVENT,
    UNKNOWN;

    companion object {
        fun fromName(value: String?): NativeErrorCode =
            values().firstOrNull { it.name == value } ?: UNKNOWN

        fun fromStatus(status: NativeStatusCode): NativeErrorCode =
            when (status) {
                NativeStatusCode.OK -> NONE
                NativeStatusCode.CONNECT_TIMEOUT -> CONNECT_TIMEOUT
                NativeStatusCode.CONNECT_FAILED -> CONNECT_FAILED
                NativeStatusCode.TOKEN_EXPIRED -> TOKEN_EXPIRED
                NativeStatusCode.TOKEN_REFRESH_FAILED -> TOKEN_REFRESH_FAILED
                NativeStatusCode.AUTH_FAILED -> AUTH_FAILED
                NativeStatusCode.PUBLISH_AUDIO_FAILED -> PUBLISH_AUDIO_FAILED
                NativeStatusCode.PUBLISH_VIDEO_FAILED -> PUBLISH_VIDEO_FAILED
                NativeStatusCode.AUDIO_CAPTURE_FAILED -> AUDIO_CAPTURE_FAILED
                NativeStatusCode.VIDEO_CAPTURE_FAILED -> VIDEO_CAPTURE_FAILED
                NativeStatusCode.AUDIO_DEVICE_UNAVAILABLE -> AUDIO_DEVICE_UNAVAILABLE
                NativeStatusCode.VIDEO_DEVICE_UNAVAILABLE -> VIDEO_DEVICE_UNAVAILABLE
                NativeStatusCode.CAMERA_PERMISSION_DENIED -> CAMERA_PERMISSION_DENIED
                NativeStatusCode.CAMERA_DEVICE_BUSY -> CAMERA_DEVICE_BUSY
                NativeStatusCode.CAMERA_DRIVER_FAILURE -> CAMERA_DRIVER_FAILURE
                NativeStatusCode.CAMERA_CAPABILITY_UNAVAILABLE -> CAMERA_CAPABILITY_UNAVAILABLE
                NativeStatusCode.CAMERA_MODE_REJECTED -> CAMERA_MODE_REJECTED
                NativeStatusCode.REMOTE_AUDIO_FAILED -> REMOTE_AUDIO_FAILED
                NativeStatusCode.REMOTE_VIDEO_FAILED -> REMOTE_VIDEO_FAILED
                NativeStatusCode.DISCONNECTED -> DISCONNECTED
                NativeStatusCode.USER_LEFT -> USER_LEFT
                NativeStatusCode.RECONNECT_FAILED -> RECONNECT_FAILED
                NativeStatusCode.NATIVE_EXCEPTION -> NATIVE_EXCEPTION
                NativeStatusCode.STALE_EVENT -> STALE_EVENT
                NativeStatusCode.UNKNOWN -> UNKNOWN
            }
    }
}

data class NativeError(
    val code: NativeErrorCode,
    val message: String,
    val operation: String,
    val recoverable: Boolean
)

data class NativeResult<out T>(
    val ok: Boolean,
    val value: T? = null,
    val error: NativeError? = null
) {
    companion object {
        fun <T> success(value: T): NativeResult<T> = NativeResult(ok = true, value = value)
        fun <T> failure(error: NativeError): NativeResult<T> = NativeResult(ok = false, error = error)
    }
}

enum class NativeStatusCode(val code: Int) {
    OK(0),
    CONNECT_TIMEOUT(1),
    CONNECT_FAILED(2),
    TOKEN_EXPIRED(3),
    TOKEN_REFRESH_FAILED(4),
    AUTH_FAILED(5),
    PUBLISH_AUDIO_FAILED(6),
    PUBLISH_VIDEO_FAILED(7),
    AUDIO_CAPTURE_FAILED(8),
    VIDEO_CAPTURE_FAILED(9),
    AUDIO_DEVICE_UNAVAILABLE(10),
    VIDEO_DEVICE_UNAVAILABLE(11),
    CAMERA_PERMISSION_DENIED(12),
    CAMERA_DEVICE_BUSY(13),
    CAMERA_DRIVER_FAILURE(14),
    CAMERA_CAPABILITY_UNAVAILABLE(15),
    CAMERA_MODE_REJECTED(16),
    REMOTE_AUDIO_FAILED(17),
    REMOTE_VIDEO_FAILED(18),
    DISCONNECTED(19),
    USER_LEFT(20),
    RECONNECT_FAILED(21),
    NATIVE_EXCEPTION(22),
    STALE_EVENT(23),
    UNKNOWN(999);

    companion object {
        fun fromCode(code: Int): NativeStatusCode =
            values().firstOrNull { it.code == code } ?: UNKNOWN
    }
}
