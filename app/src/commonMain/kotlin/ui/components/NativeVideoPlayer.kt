package ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun NativeVideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {}
)
