package com.noobdev.orbit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ui.screen.OrbitApp


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // This is where you hand control over to Kotlin Multiplatform.
            // Replace this comment with your actual main Compose function:
            OrbitApp()
        }
    }
}