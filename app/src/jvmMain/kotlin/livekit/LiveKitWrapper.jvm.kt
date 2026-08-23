package livekit

actual suspend fun connectAndPublishToLiveKit(
    authResponse: LiveKitAuthResponse,
    isVideo: Boolean,
    iceServers: List<repository.TurnServer>,
    refreshTokenFetcher: (suspend () -> LiveKitAuthResponse)?,
    audioBitrateKbps: Int
): NativeResult<NativeMediaReadiness> {
    if (!LiveKitFeatureFlags.cppMedia) {
        return NativeResult.failure(
            NativeError(
                code = NativeErrorCode.CONNECT_FAILED,
                message = "C++ LiveKit media is disabled",
                operation = "connectAndPublishToLiveKit",
                recoverable = false
            )
        )
    }

    return LiveKitNativeMediaSession.connectAndPublish(
        authResponse = authResponse,
        videoPolicy = if (isVideo && LiveKitFeatureFlags.videoRequired) {
            JoinVideoPolicy.VideoRequired
        } else if (isVideo) {
            JoinVideoPolicy.VideoOptional
        } else {
            JoinVideoPolicy.AudioOnly
        },
        refreshTokenFetcher = refreshTokenFetcher,
        audioBitrateKbps = audioBitrateKbps
    )
}

actual suspend fun ensureLiveKitMediaReady(): NativeResult<NativeMediaReadiness> =
    LiveKitNativeMediaSession.ensureMediaReady()

actual fun liveKitCurrentReadiness(): NativeMediaReadiness? =
    if (LiveKitFeatureFlags.cppMedia) LiveKitNativeMediaSession.currentReadiness() else null

actual fun setLiveKitReconnectedListener(listener: (() -> Unit)?) {
    LiveKitNativeMediaSession.onReconnected = listener
}

actual fun disconnectFromLiveKit(userInitiated: Boolean) {
    LiveKitNativeMediaSession.disconnect(userInitiated = userInitiated)
}

actual fun setLiveKitPlaybackMuted(muted: Boolean) {
    LiveKitNativeAudioPlayer.setOutputMuted(muted)
}

actual fun setLiveKitVirtualBackground(enabled: Boolean) {
    LiveKitNativeVideoCapture.setVirtualBackgroundEnabled(enabled)
}

actual fun playSoundEffect(effectId: String) {
    SoundboardMixer.play(effectId)
}

actual fun stopSoundEffects() {
    SoundboardMixer.stopAll()
}

actual fun setSoundEffectVolume(volume: Float) {
    SoundboardMixer.setVolume(volume)
}

actual fun soundboardAddCustomSound(displayName: String, bytes: ByteArray): String? =
    SoundboardMixer.addCustomSound(displayName, bytes)

actual fun soundboardRemoveCustomSound(id: String) {
    SoundboardMixer.removeCustomSound(id)
}

actual fun soundboardCustomSounds(): kotlinx.coroutines.flow.StateFlow<List<SoundboardCustomSound>> =
    SoundboardMixer.customSounds

actual fun setLiveKitInputDevice(deviceName: String) {
    LiveKitNativeAudioCapture.setInputDeviceOverride(deviceName)
}

actual fun setLiveKitOutputDevice(deviceName: String) {
    LiveKitNativeAudioPlayer.setOutputDeviceName(deviceName)
}

actual fun setLiveKitInputVolume(volume: Float) {
    LiveKitNativeAudioCapture.setUserInputVolume(volume)
}

actual fun setLiveKitOutputVolume(volume: Float) {
    LiveKitNativeAudioPlayer.setOutputVolume(volume)
}

actual fun setLiveKitAudioProcessingOptions(noiseSuppression: Boolean, echoCancellation: Boolean, autoGainControl: Boolean) {
    OrbitApm.setAudioProcessingOptions(noiseSuppression, echoCancellation, autoGainControl)
}
