package livekit

object NativeStreamDiag {
    @JvmStatic
    fun onNativeLine(line: String) {
        StreamDiag.logNative(line.removePrefix("DEBUG [OrbitLiveKitAudio] "))
    }
}
