package livekit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    // CallManager drives this from rememberCoroutineScope() with no dispatcher of
    // its own, so the join used to run entirely on the Compose/EDT thread and the
    // multi-second mic/camera probing inside froze the window. Offload the whole
    // native connect path to IO here, at the media entry point, so no caller has to
    // remember to do it.
    return withContext(Dispatchers.IO) {
        LiveKitNativeMediaSession.connectAndPublish(
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

actual fun releaseNativeLiveKitSession() {
    LiveKitNativeMediaSession.releaseNativeSession()
}

actual fun setLiveKitPlaybackMuted(muted: Boolean) {
    LiveKitNativeAudioPlayer.setOutputMuted(muted)
}

actual fun setLiveKitVirtualBackground(enabled: Boolean) {
    LiveKitNativeVideoCapture.setVirtualBackgroundEnabled(enabled)
}

actual suspend fun enableLiveKitCamera(): NativeResult<Unit> =
    LiveKitNativeMediaSession.enableCamera()

actual fun disableLiveKitCamera() {
    LiveKitNativeMediaSession.disableCamera()
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

actual fun setLiveKitParticipantGain(identity: String, gain: Float) {
    LiveKitNativeAudioBridge.nativeSetParticipantGain(identity, gain)
}

actual fun liveKitActiveSpeakers(): kotlinx.coroutines.flow.StateFlow<Set<String>> =
    LiveKitNativeMediaSession.activeSpeakers

actual fun liveKitRemoteAudioMuted(): kotlinx.coroutines.flow.StateFlow<Set<String>> =
    LiveKitRemoteVideoFrameStore.remoteAudioMuted

actual fun setCallRinging(ringing: Boolean) {
    CallRingtone.setPlaying(ringing)
}

actual fun showCallNotification(title: String, body: String) {
    util.CallNotifier.show(title, body)
}

actual fun liveKitConnectionQuality(): kotlinx.coroutines.flow.StateFlow<Map<String, ConnectionQualitySample>> =
    LiveKitRemoteVideoFrameStore.connectionQuality

actual fun setLiveKitAudioProcessingOptions(noiseSuppression: Boolean, echoCancellation: Boolean, autoGainControl: Boolean) {
    OrbitApm.setAudioProcessingOptions(noiseSuppression, echoCancellation, autoGainControl)
}

actual fun prepareNativeE2EE(enabled: Boolean) {
    LiveKitNativeMediaSession.requestE2EE(enabled)
}

actual fun deliverNativeMediaKey(identity: String, key: ByteArray, keyIndex: Int): Boolean =
    LiveKitNativeMediaSession.deliverParticipantKey(identity, key, keyIndex)
