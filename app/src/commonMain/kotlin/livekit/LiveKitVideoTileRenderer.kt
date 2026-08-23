package livekit

sealed class LiveKitVideoTileRenderer {
    object LocalPreview : LiveKitVideoTileRenderer()
    // Mirrors what the native session publishes from our own capture while sharing.
    object LocalScreenShare : LiveKitVideoTileRenderer()
    data class Remote(val participantIdentity: String?) : LiveKitVideoTileRenderer()
    data class RemoteScreenShare(val participantIdentity: String?) : LiveKitVideoTileRenderer()
    // Renders the frame of ONE specific remote track (per-participant grid tiles)
    data class RemoteTrack(val trackSid: String, val isScreenShare: Boolean = false) : LiveKitVideoTileRenderer()
}
