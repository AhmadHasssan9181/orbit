package com.noobdev.orbit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import android.content.Intent
import ui.screen.OrbitApp
import util.SsoHandler

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        util.appContext = this.applicationContext
        handleIntent(intent)
        // Enable edge-to-edge so the app draws behind system bars on Android
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            OrbitApp()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            if (uri.scheme == "orbit" && uri.host == "sso-callback") {
                uri.getQueryParameter("loginToken")?.let { token ->
                    SsoHandler.onTokenReceived(token)
                }
            }
        }
    }
}