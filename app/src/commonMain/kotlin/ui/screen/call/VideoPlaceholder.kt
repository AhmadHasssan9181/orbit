package ui.screen.call

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * What a call tile should show for one participant.
 *
 * ## Why this exists
 *
 * A participant whose camera is off has a video *publication* announced to us by
 * LiveKit, so they arrive as a tile with a renderer and no frames -- ever. The
 * tile then draws that renderer, which says it is working on it, for as long as
 * the call lasts. That is a lie about state: nothing is being worked on.
 *
 * There are genuinely three states and they need three different answers, which
 * is what [videoPlaceholder] decides:
 *
 *   - a frame has landed            -> the video
 *   - no frame yet, but soon        -> a spinner, because a share can take 18s
 *   - no frame and not coming       -> the person's avatar
 *
 * The old code had the first and the last but no middle, so "soon" and "never"
 * were drawn identically.
 */
internal enum class VideoPlaceholder {
    /** A real frame is on screen. */
    Video,

    /** Nothing yet, but within the window where a track legitimately takes time. */
    Spinner,

    /** Nothing, and past the point where waiting is an honest description. */
    Avatar
}

/**
 * How long a tile waits on a frame before admitting there is not one coming.
 *
 * Eight seconds. Run 27 attached a screen share at 18s, but that was a *share*
 * going through a subscription retry ladder, and shares get a dedicated stuck-share
 * path with its own 5s auto-reconnect and its own messaging. This is for a
 * participant's *camera*, which either arrives within a second or is not coming
 * because the camera is off.
 */
internal const val CAMERA_FRAME_GRACE_MS = 8_000L

/**
 * The pure decision, separated so it can be pinned by a test.
 *
 * [hasFrame] is the only thing that can end the wait. Elapsed time alone never
 * promotes to [VideoPlaceholder.Avatar] without it, so a slow-but-real camera
 * cannot be talked out of its own tile by a timer.
 */
internal fun videoPlaceholder(hasFrame: Boolean, waitedMs: Long): VideoPlaceholder = when {
    hasFrame -> VideoPlaceholder.Video
    waitedMs >= CAMERA_FRAME_GRACE_MS -> VideoPlaceholder.Avatar
    else -> VideoPlaceholder.Spinner
}

/**
 * Elapsed milliseconds since this tile first asked for a frame.
 *
 * Restarts whenever a frame lands, so a camera that drops and comes back gets a
 * fresh grace window instead of being permanently demoted to an avatar by a
 * clock that started at join.
 */
@Composable
internal fun rememberFrameWaitMs(hasFrame: Boolean): Long {
    var waited by remember { mutableStateOf(0L) }
    LaunchedEffect(hasFrame) {
        if (hasFrame) {
            waited = 0L
            return@LaunchedEffect
        }
        // Ticked rather than read once, so the tile transitions on its own without
        // anything upstream having to remember to nudge it.
        val startedAt = System.currentTimeMillis()
        while (waited < CAMERA_FRAME_GRACE_MS) {
            delay(250)
            waited = System.currentTimeMillis() - startedAt
        }
    }
    return waited
}