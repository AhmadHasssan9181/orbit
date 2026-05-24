package util

expect suspend fun performSsoLogin(homeserverUrl: String): String
expect fun openUrlInBrowser(url: String)
