package livekit

expect suspend fun connectAndPublishToLiveKit(
    authResponse: LiveKitAuthResponse,
    isVideo: Boolean,
    iceServers: List<repository.TurnServer> = emptyList(),
    refreshTokenFetcher: (suspend () -> LiveKitAuthResponse)? = null,
    audioBitrateKbps: Int = 64
): NativeResult<NativeMediaReadiness>

expect suspend fun ensureLiveKitMediaReady(): NativeResult<NativeMediaReadiness>

// E1: non-suspending peek at the native session's current stage for join progress UI
expect fun liveKitCurrentReadiness(): NativeMediaReadiness?

// H1: fires after a successful mid-call reconnect (token-refresh resume) so the
// owner can restore UI stage and re-affirm MatrixRTC membership. Pass null to clear.
expect fun setLiveKitReconnectedListener(listener: (() -> Unit)?)

expect fun disconnectFromLiveKit(userInitiated: Boolean)

// Deafen: mute mixed playback output while keeping the mic publishing
expect fun setLiveKitPlaybackMuted(muted: Boolean)

// Virtual background: blur the group-path camera frames (applied at next camera open)
expect fun setLiveKitVirtualBackground(enabled: Boolean)

// BUG-A fix: mid-call camera publish/unpublish for the native group path.
// enableCamera lazily opens capture + publishes the camera track (republish-safe);
// disableCamera mutes the track and stops frame push.
expect suspend fun enableLiveKitCamera(): NativeResult<Unit>
expect fun disableLiveKitCamera()

// Soundboard: synthesized effects mixed into the outgoing mic frames (group path)
// and played locally; stopAll cancels anything still queued or rendering.
expect fun playSoundEffect(effectId: String)
expect fun stopSoundEffects()
expect fun setSoundEffectVolume(volume: Float)

// Soundboard: user-uploaded effects (WAV/AIFF/AU, hard 10 s limit), persisted on disk.
data class SoundboardCustomSound(val id: String, val displayName: String)

/** Returns null on success or a user-presentable error message. Call off the UI thread. */
expect fun soundboardAddCustomSound(displayName: String, bytes: ByteArray): String?
expect fun soundboardRemoveCustomSound(id: String)
expect fun soundboardCustomSounds(): kotlinx.coroutines.flow.StateFlow<List<SoundboardCustomSound>>

// Voice & Video settings: runtime device + volume overrides for the native group audio path.
// Device names substring-match JavaSound mixers; "Default"/blank clears the override.
expect fun setLiveKitInputDevice(deviceName: String)
expect fun setLiveKitOutputDevice(deviceName: String)
expect fun setLiveKitInputVolume(volume: Float)
expect fun setLiveKitOutputVolume(volume: Float)

// Per-remote-participant output gain on the native group-call mixer
// (1.0 = unity, 0.0 = individually muted; independent of global deafen).
expect fun setLiveKitParticipantGain(identity: String, gain: Float)

// LiveKit identities of remote participants currently speaking, as metered
// by the native mixer (change-only updates). Empty while not in a call.
expect fun liveKitActiveSpeakers(): kotlinx.coroutines.flow.StateFlow<Set<String>>

// LiveKit identities whose remote mic track is currently muted (native room
// delegate: subscribe-time state + TrackMuted/TrackUnmuted). Empty while idle.
expect fun liveKitRemoteAudioMuted(): kotlinx.coroutines.flow.StateFlow<Set<String>>

// Voice & Video settings: NS/EC/AGC for the native group-call APM chain
expect fun setLiveKitAudioProcessingOptions(noiseSuppression: Boolean, echoCancellation: Boolean, autoGainControl: Boolean)

// matrixRTC E2EE media: arm the native KeyProvider path before connectAsync
// (encrypted rooms only) and feed per-participant media keys received through
// the olm to-device exchange into the native KeyProvider.
expect fun prepareNativeE2EE(enabled: Boolean)
expect fun deliverNativeMediaKey(identity: String, key: ByteArray, keyIndex: Int): Boolean
