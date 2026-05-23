package util

import androidx.compose.runtime.Composable

@Composable
expect fun rememberFilePickerLauncher(
    onFileSelected: (bytes: ByteArray, filename: String, mimeType: String) -> Unit
): () -> Unit
