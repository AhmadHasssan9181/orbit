package ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    var showSplash by remember { mutableStateOf(true) }

    if (showSplash) {
        SplashScreenWindow(onLoadingComplete = {
            showSplash = false
        })
    } else {
        val state = rememberWindowState()
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "Orbit",
            undecorated = true,
            transparent = false,
            icon = painterResource("logo.jpeg")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                CustomTitleBar(state = state, onClose = ::exitApplication)
                OrbitApp(modifier = Modifier.weight(1f))
            }
        }
    }
}
