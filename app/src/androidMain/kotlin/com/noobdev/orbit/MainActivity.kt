package com.noobdev.orbit

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import ui.screen.main.OrbitApp
import util.SsoHandler

class MainActivity : ComponentActivity() {
    private var onScreenSharePermissionGranted: ((Intent) -> Unit)? = null

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startScreenShareService()
            onScreenSharePermissionGranted?.invoke(result.data!!)
        }
    }

    private val callPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        util.appContext = this.applicationContext
        handleIntent(intent)
        requestCallPermissionsIfNeeded()
        // Enable edge-to-edge so the app draws behind system bars on Android
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            OrbitApp(
                onStartScreenShare = { callback ->
                    onScreenSharePermissionGranted = callback
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                }
            )
        }
    }

    private fun startScreenShareService() {
        val serviceIntent = Intent(this, ScreenShareService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopScreenShareService() {
        val serviceIntent = Intent(this, ScreenShareService::class.java)
        stopService(serviceIntent)
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

    private fun requestCallPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val missingPermissions = callPermissions.filter { permission ->
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), CALL_PERMISSION_REQUEST)
        }
    }

    private companion object {
        private const val CALL_PERMISSION_REQUEST = 1001
    }
}
