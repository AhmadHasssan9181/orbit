package livekit

sealed class LiveKitVideoTileRenderer {
    object LocalPreview : LiveKitVideoTileRenderer()
    data class Remote(val participantIdentity: String?) : LiveKitVideoTileRenderer()
}
