package util

import java.net.ServerSocket
import java.net.URLEncoder

actual suspend fun performSsoLogin(homeserverUrl: String): String {
    val serverSocket = ServerSocket(0)
    val port = serverSocket.localPort
    val redirectUrl = "http://localhost:$port"

    val ssoUrl = "${homeserverUrl.trimEnd('/')}/_matrix/client/v3/login/sso/redirect?redirectUrl=${
        URLEncoder.encode(redirectUrl, "UTF-8")
    }"
    openUrlInBrowser(ssoUrl)

    serverSocket.soTimeout = 300_000
    return try {
        val socket = serverSocket.accept()
        val reader = socket.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: throw IllegalStateException("No request received")

        val queryString = requestLine.split(" ").getOrNull(1)?.substringAfter("?") ?: ""
        val loginToken = queryString.split("&")
            .map { it.split("=", limit = 2) }
            .firstOrNull { it[0] == "loginToken" }
            ?.getOrNull(1)
            ?: throw IllegalStateException("No loginToken in callback")

        val htmlResponse = "<html><body><h1>Login Successful</h1><p>Return to Orbit.</p></body></html>"
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n$htmlResponse"
        socket.getOutputStream().write(response.toByteArray())
        socket.close()
        loginToken
    } finally {
        serverSocket.close()
    }
}

actual fun openUrlInBrowser(url: String) {
    val os = System.getProperty("os.name").lowercase()
    val runtime = Runtime.getRuntime()
    when {
        os.contains("win") -> runtime.exec("rundll32 url.dll,FileProtocolHandler $url")
        os.contains("mac") -> runtime.exec("open $url")
        os.contains("nix") || os.contains("nux") -> runtime.exec("xdg-open $url")
    }
}
