package util

import kotlinx.coroutines.flow.StateFlow

expect class AudioPlayer() {
    val isPlaying: StateFlow<Boolean>
    val progress: StateFlow<Float>

    fun play(url: String)
    fun pause()
    fun stop()
    fun release()
}
