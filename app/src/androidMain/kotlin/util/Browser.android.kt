package util

import android.content.Intent
import android.net.Uri

actual suspend fun performSsoLogin(homeserverUrl: String): String {
    val callbackUrl = "orbit://sso-callback"
    val ssoUrl = "${homeserverUrl.trimEnd('/')}/_matrix/client/v3/login/sso/redirect?redirectUrl=$callbackUrl"
    
    val deferred = SsoHandler.prepare()
    openUrlInBrowser(ssoUrl)
    
    return deferred.await()
}

actual fun openUrlInBrowser(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    appContext.startActivity(intent)
}
