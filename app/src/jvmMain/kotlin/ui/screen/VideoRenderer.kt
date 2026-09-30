package ui.screen.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import livekit.LiveKitLocalVideoFrame
import livekit.LiveKitLocalVideoFrameStore
import livekit.LiveKitRemoteVideoFrame
import livekit.LiveKitRemoteVideoFrameStore
import livekit.LiveKitVideoTileRenderer
import webrtc.DesktopVideoRenderer

@Composable
actual fun VideoRenderer(renderer: Any?, modifier: Modifier) {
    when (renderer) {
        is LiveKitVideoTileRenderer.LocalPreview -> {
            val frame by LiveKitLocalVideoFrameStore.latestFrame.collectAsState()
            val mirror by LiveKitLocalVideoFrameStore.mirrorPreview.collectAsState()
            LiveKitFrameRenderer(
                image = rememberLocalFrameImage(frame),
                modifier = modifier.graphicsLayer { scaleX = if (mirror) -1f else 1f },
                contentScale = ContentScale.Crop,
                fallbackText = ""
            )
        }
        is LiveKitVideoTileRenderer.LocalScreenShare -> {
            val preview by livekit.LiveKitLocalScreenShareTrack.previewFrame.collectAsState()
            LiveKitFrameRenderer(
                image = rememberLocalSharePreviewImage(preview),
                modifier = modifier,
                contentScale = ContentScale.Fit,
                fallbackText = "Sharing your screen…"
            )
        }
        is LiveKitVideoTileRenderer.Remote -> {
            // Routed through the per-participant track rather than the global
            // "latest camera frame": the global store changed on every frame from
            // every participant, so in an N-person call all N tiles recomposed on
            // every frame — O(N²) work at 30fps.
            val tracks by LiveKitRemoteVideoFrameStore.activeTracks.collectAsState()
            val framesByTrack by LiveKitRemoteVideoFrameStore.framesByTrack.collectAsState()
            val identity = renderer.participantIdentity
            val frame = remember(tracks, framesByTrack, identity) {
                if (identity == null) {
                    framesByTrack.values.lastOrNull()
                } else {
                    val cameraSids = tracks.values
                        .filter { !it.isScreenShare && it.participantIdentity == identity }
                        .map { it.trackSid }
                    cameraSids.firstNotNullOfOrNull { framesByTrack[it] }
                        ?: framesByTrack.values.lastOrNull { it.participantIdentity == identity }
                }
            }
            LiveKitFrameRenderer(
                image = rememberRemoteFrameImage(frame),
                modifier = modifier,
                contentScale = ContentScale.Crop,
                fallbackText = "Video starting…"
            )
        }
        is LiveKitVideoTileRenderer.RemoteScreenShare -> {
            val frame by LiveKitRemoteVideoFrameStore.latestScreenShareFrame.collectAsState()
            val matchingFrame = frame?.takeIf { current ->
                renderer.participantIdentity == null || current.participantIdentity == renderer.participantIdentity
            }
            LiveKitFrameRenderer(
                image = rememberRemoteFrameImage(matchingFrame),
                modifier = modifier,
                contentScale = ContentScale.Fit,
                fallbackText = ""
            )
        }
        is LiveKitVideoTileRenderer.RemoteTrack -> {
            val framesByTrack by LiveKitRemoteVideoFrameStore.framesByTrack.collectAsState()
            val frame = framesByTrack[renderer.trackSid]
            // Last frame is held while a track stalls (muted video / reconnect).
            //
            // Before the first frame arrives, show a SPINNER rather than text. A
            // screen share can sit trackless for a while before it attaches -- the
            // subscription retry ladder is working on it, and run 27 attached at
            // 18 s -- so "Video starting…" either sat there looking broken or was
            // replaced by the stuck panel. A spinner says "working on it" for the
            // whole wait and disappears on its own the moment a frame lands, which
            // is the only honest description of what is happening.
            val image = rememberRemoteFrameImage(frame)
            if (image != null) {
                LiveKitFrameRenderer(
                    image = image,
                    modifier = modifier,
                    contentScale = if (renderer.isScreenShare) ContentScale.Fit else ContentScale.Crop,
                    fallbackText = ""
                )
            } else {
                Box(
                    modifier = modifier.background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.75f),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
        is DesktopVideoRenderer -> {
            val frame by renderer.frameImage.collectAsState()
            Box(
                modifier = modifier
                    .clipToBounds()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val image = frame
                if (image != null) {
                    Image(
                        bitmap = image.toComposeImageBitmap(),
                        contentDescription = null,
                        contentScale = if (renderer.isScreenShare) ContentScale.Fit else ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val (sx, sy) = videoMirrorScale(renderer.mirror, renderer.isScreenShare)
                                scaleX = sx
                                scaleY = sy
                            }
                    )
                } else {
                    Text("Starting video", color = Color.White)
                }
            }
        }
        "livekit_remote" -> {
            LiveKitVideoRenderer(modifier)
        }
        else -> {
            Box(
                modifier = modifier.background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text("Starting video", color = Color.White)
            }
        }
    }
}

@Composable
private fun LiveKitFrameRenderer(
    image: ImageBitmap?,
    modifier: Modifier,
    contentScale: ContentScale,
    fallbackText: String
) {
    Box(
        modifier = modifier
            .clipToBounds()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        } else if (fallbackText.isNotBlank()) {
            Text(fallbackText, color = Color.White)
        }
    }
}

/**
 * How a video frame should be transformed before drawing: (scaleX, scaleY).
 *
 * Mirroring horizontally is correct for your OWN camera -- it behaves like a
 * mirror rather than a video of you -- and wrong for a remote camera, which is
 * another person who would then appear to be left-handed. It is also wrong for a
 * screen share, which is not a person at all.
 *
 * ## Why scaleY is always 1
 *
 * This used to read `scaleY = if (isScreenShare) -1f else 1f`, which rendered
 * every screen share in a 1:1 UPSIDE DOWN while group calls were perfectly fine.
 *
 * The tell was that the flip existed in exactly one renderer in the app. The
 * LiveKit renderer that serves group calls has no flip at all, and both end up
 * drawing a Compose `Image` from a decoded frame -- so there is no mechanism by
 * which one path would need a vertical flip and the other would not. The working
 * path is the proof that the data arrives the right way up.
 *
 * This is not a subtle orientation bug either. A shared window is text and UI, so
 * a vertical flip renders as an obviously broken image, which is how it was
 * reported.
 */
internal fun videoMirrorScale(mirror: Boolean, isScreenShare: Boolean): Pair<Float, Float> {
    val scaleX = if (mirror && !isScreenShare) -1f else 1f
    return scaleX to 1f
}