package livekit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

enum class ShutdownReason {
    UserLeave,
    WindowClosed,
    TokenExpired,
    NetworkDrop,
    ServerDisconnect,
    NativeFailure,
    Unknown
}

suspend fun <T> awaitNativeResult(
    timeoutMs: Long,
    operation: String,
    userInitiated: Boolean = false,
    block: suspend () -> T
): NativeResult<T> =
    try {
        NativeResult.success(withTimeout(timeoutMs) { block() })
    } catch (error: TimeoutCancellationException) {
        NativeResult.failure(
            NativeError(
                code = NativeErrorCode.CONNECT_TIMEOUT,
                message = "$operation timed out after ${timeoutMs}ms",
                operation = operation,
                recoverable = true
            )
        )
    } catch (error: CancellationException) {
        // If OUR caller is being cancelled, propagate — swallowing it here would
        // break structured concurrency. A stray cancellation thrown by `block`
        // while this coroutine is still active is a real failure result.
        coroutineContext.ensureActive()
        NativeResult.failure(
            NativeError(
                code = if (userInitiated) NativeErrorCode.USER_LEFT else NativeErrorCode.DISCONNECTED,
                message = error.message ?: "$operation cancelled",
                operation = operation,
                recoverable = !userInitiated
            )
        )
    } catch (error: Throwable) {
        NativeResult.failure(
            NativeError(
                code = NativeErrorCode.UNKNOWN,
                message = error.message ?: "$operation failed",
                operation = operation,
                recoverable = false
            )
        )
    }
