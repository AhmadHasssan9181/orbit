package livekit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LiveKitRemoteVideoFrame(
    val sessionId: Long,
    val epoch: Long,
    val participantIdentity: String,
    val trackSid: String,
    val buffer: ByteArray,
    val width: Int,
    val height: Int,
    val format: Int,
    val strideY: Int,
    val strideU: Int,
    val strideV: Int,
    val timestampNs: Long,
    val rotation: Int,
    val sequence: Long
)

data class LiveKitRemoteVideoTrackState(
    val participantIdentity: String,
    val trackSid: String,
    val trackSource: String = "",
    val trackName: String = ""
) {
    val isScreenShare: Boolean
        get() = trackSource.equals("screenshare", ignoreCase = true) ||
                trackSource.equals("screen", ignoreCase = true) ||
                trackSource.equals("screen_share", ignoreCase = true) ||
                trackSource.equals("SOURCE_SCREENSHARE", ignoreCase = true) ||
                trackSource.equals("3", ignoreCase = true) ||
                trackSource.contains("screen", ignoreCase = true) ||
                trackName.contains("screen", ignoreCase = true) ||
                trackName.contains("share", ignoreCase = true) ||
                trackName.contains("desktop", ignoreCase = true) ||
                trackName.contains("display", ignoreCase = true) ||
                trackSid.contains("screen", ignoreCase = true) ||
                trackSid.contains("share", ignoreCase = true)
}

data class LiveKitLocalVideoFrame(
    val buffer: ByteArray,
    val width: Int,
    val height: Int,
    val format: Int,
    val strideY: Int,
    val timestampNs: Long,
    val rotation: Int,
    val sequence: Long
)

object LiveKitRemoteVideoFrameStore {
    private val _latestFrame = MutableStateFlow<LiveKitRemoteVideoFrame?>(null)
    val latestFrame: StateFlow<LiveKitRemoteVideoFrame?> = _latestFrame
    private val _latestCameraFrame = MutableStateFlow<LiveKitRemoteVideoFrame?>(null)
    val latestCameraFrame: StateFlow<LiveKitRemoteVideoFrame?> = _latestCameraFrame
    private val _latestScreenShareFrame = MutableStateFlow<LiveKitRemoteVideoFrame?>(null)
    val latestScreenShareFrame: StateFlow<LiveKitRemoteVideoFrame?> = _latestScreenShareFrame
    private val _activeTracks = MutableStateFlow<Map<String, LiveKitRemoteVideoTrackState>>(emptyMap())
    val activeTracks: StateFlow<Map<String, LiveKitRemoteVideoTrackState>> = _activeTracks

    private var sequence: Long = 0L
    private var activeTrackSid: String? = null

    fun trackStarted(participantIdentity: String, trackSid: String, trackSource: String = "", trackName: String = "") {
        _activeTracks.value = _activeTracks.value + (
            trackSid to LiveKitRemoteVideoTrackState(
                participantIdentity = participantIdentity,
                trackSid = trackSid,
                trackSource = trackSource,
                trackName = trackName
            )
        )
        if (activeTrackSid != trackSid) {
            println(
                "DEBUG [RemoteVideoUI] render started participantIdentity=$participantIdentity " +
                    "trackSid=$trackSid trackSource=$trackSource trackName=$trackName"
            )
        }
        activeTrackSid = trackSid
    }

    fun updateFrame(
        sessionId: Long,
        epoch: Long,
        participantIdentity: String,
        trackSid: String,
        buffer: ByteArray,
        width: Int,
        height: Int,
        format: Int,
        strideY: Int,
        strideU: Int,
        strideV: Int,
        timestampNs: Long,
        rotation: Int
    ) {
        sequence += 1
        val frameSequence = sequence
        if (frameSequence <= 3L || frameSequence % 60L == 0L) {
            println(
                "DEBUG [RemoteVideoUI] frame updated participantIdentity=$participantIdentity " +
                    "trackSid=$trackSid seq=$frameSequence width=$width height=$height " +
                    "format=$format bytes=${buffer.size} timestampNs=$timestampNs rotation=$rotation"
            )
        }
        val frame = LiveKitRemoteVideoFrame(
            sessionId = sessionId,
            epoch = epoch,
            participantIdentity = participantIdentity,
            trackSid = trackSid,
            buffer = buffer,
            width = width,
            height = height,
            format = format,
            strideY = strideY,
            strideU = strideU,
            strideV = strideV,
            timestampNs = timestampNs,
            rotation = rotation,
            sequence = frameSequence
        )
        _latestFrame.value = frame
        val trackState = _activeTracks.value[trackSid]
        if (trackState?.isScreenShare == true) {
            _latestScreenShareFrame.value = frame
        } else {
            _latestCameraFrame.value = frame
        }
    }

    fun trackStopped(participantIdentity: String, trackSid: String) {
        val trackState = _activeTracks.value[trackSid]
        _activeTracks.value = _activeTracks.value - trackSid
        if (trackState?.isScreenShare == true) {
            _latestScreenShareFrame.value = null
        } else {
            _latestCameraFrame.value = null
        }
        if (activeTrackSid == trackSid) {
            println(
                "DEBUG [RemoteVideoUI] render stopped participantIdentity=$participantIdentity " +
                    "trackSid=$trackSid"
            )
            activeTrackSid = null
            _latestFrame.value = null
        }
    }

    fun clear(reason: String) {
        if (_latestFrame.value != null || activeTrackSid != null) {
            println("DEBUG [RemoteVideoUI] render stopped reason=$reason trackSid=${activeTrackSid ?: "<none>"}")
        }
        activeTrackSid = null
        _activeTracks.value = emptyMap()
        _latestFrame.value = null
        _latestCameraFrame.value = null
        _latestScreenShareFrame.value = null
    }
}

object LiveKitLocalVideoFrameStore {
    private val _latestFrame = MutableStateFlow<LiveKitLocalVideoFrame?>(null)
    val latestFrame: StateFlow<LiveKitLocalVideoFrame?> = _latestFrame

    private var sequence: Long = 0L

    fun updateFrame(
        buffer: ByteArray,
        width: Int,
        height: Int,
        format: Int,
        strideY: Int,
        timestampNs: Long,
        rotation: Int
    ) {
        sequence += 1
        _latestFrame.value = LiveKitLocalVideoFrame(
            buffer = buffer,
            width = width,
            height = height,
            format = format,
            strideY = strideY,
            timestampNs = timestampNs,
            rotation = rotation,
            sequence = sequence
        )
    }

    fun clear(reason: String) {
        if (_latestFrame.value != null) {
            println("OrbitCallUI localPreviewFrame=cleared reason=$reason")
        }
        _latestFrame.value = null
    }
}
