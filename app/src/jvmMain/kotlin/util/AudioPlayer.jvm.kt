package util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class AudioPlayer actual constructor() {
    private val _isPlaying = MutableStateFlow(false)
    actual val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    actual val progress: StateFlow<Float> = _progress.asStateFlow()

    actual fun play(url: String) {
        println("Audio playback not natively supported on Desktop JVM without external libs (like JavaZoom) for OGG/MP3.")
    }

    actual fun pause() {
    }

    actual fun stop() {
        _isPlaying.value = false
        _progress.value = 0f
    }

    actual fun release() {
        stop()
    }
}
