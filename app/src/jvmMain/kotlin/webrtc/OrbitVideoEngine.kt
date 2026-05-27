package webrtc

import dev.onvoid.webrtc.media.video.VideoTrackSource

object OrbitVideoEngine {
    init {
        WebrtcNativeDepsLoader.load()
    }

    /**
     * Injects the selfie segmentation processor into a VideoTrackSource.
     * @param source The VideoTrackSource (e.g. VideoDeviceSource) to wrap.
     * @param modelPath The absolute path to the .onnx model file.
     */
    external fun injectVideoProcessor(source: VideoTrackSource, modelPath: String)
}
