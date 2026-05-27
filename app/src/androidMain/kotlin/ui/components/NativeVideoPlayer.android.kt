package ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun NativeVideoPlayer(
    url: String,
    modifier: Modifier,
    onClose: () -> Unit
) {
    // Android implementation not yet required for desktop focused tasks,
    // but stubbed for compilation.
}
