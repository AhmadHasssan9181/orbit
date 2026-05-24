package util

import kotlinx.coroutines.CompletableDeferred

object SsoHandler {
    private var ssoDeferred: CompletableDeferred<String>? = null

    fun prepare() : CompletableDeferred<String> {
        val deferred = CompletableDeferred<String>()
        ssoDeferred = deferred
        return deferred
    }

    fun onTokenReceived(token: String) {
        ssoDeferred?.complete(token)
        ssoDeferred = null
    }

    fun onCancel() {
        ssoDeferred?.cancel()
        ssoDeferred = null
    }
}
