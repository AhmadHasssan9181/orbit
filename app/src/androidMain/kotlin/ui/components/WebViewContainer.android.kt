package ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
actual fun WebViewContainer(
    url: String,
    modifier: Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text("WebView is not supported on Android yet.")
    }
}
