package livekit

sealed class LiveKitVideoTileRenderer {
    object LocalPreview : LiveKitVideoTileRenderer()
    data class Remote(val participantIdentity: String?) : LiveKitVideoTileRenderer()
    data class RemoteScreenShare(val participantIdentity: String?) : LiveKitVideoTileRenderer()
}
