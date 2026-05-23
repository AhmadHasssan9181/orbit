package util

import android.content.Intent
import android.net.Uri
import com.noobdev.orbit.OrbitApp

actual fun openUrlInBrowser(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    OrbitApp.instance.startActivity(intent)
}