package ui.screen.call

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.theme.CosmicTokens
import ui.theme.rememberCosmicTokens
import ui.icons.OrbitIcons

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*
import webrtc.*

@Composable
fun CallGrid(
    nodes: List<RenderNode>,
    tokens: CosmicTokens,
    layoutMode: CallLayoutMode = CallLayoutMode.Grid,
    modifier: Modifier = Modifier,
    callManager: CallManager? = null,
    pinnedNodeId: String? = null,
    onPinToggle: ((String) -> Unit)? = null,
    /**
     * `.voice-room-maximized`: the featured tile takes the whole stage on pure
     * black and the participant strip steps aside.
     *
     * This is a VIEWPORT state, not a layout mode. The concept's maximise button
     * does not choose between grid and theatre — it makes the container fill the
     * window — and in Orbit the container is already full size, so the only
     * honest reading of the same gesture is "the presentation, and nothing else,
     * is on screen". The two layout rules the tests pin are untouched by it: the
     * share still goes on top at a 16:9 box sized by [sharePanelSizeIn], and the
     * strip order is still [stripOrderFor]'s.
     */
    maximized: Boolean = false,
    onToggleMaximized: (() -> Unit)? = null
) {
    if (nodes.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().background(tokens.Sunken), contentAlignment = Alignment.Center) {
            Text(
                "Waiting for participants...",
                color = tokens.OnDark.copy(alpha = 0.4f),
                fontSize = 14.sp
            )
        }
        return
    }

    // Multiple people can share at once: every screen share competes for ONE
    // spotlight slot and the pager arrows shift between them. Extra shares are
    // no longer demoted to tiny thumbnails.
    val screenShareNodes = nodes.filter { it.isScreenShare }
    val nonShareNodes = nodes.filter { !it.isScreenShare }

    // Which layout this call actually got, and what it decided from.
    //
    // Two "still the same" reports in a row, and neither the screenshot nor any
    // existing log line could tell "the share branch never ran" apart from "the
    // share branch ran and drew the wrong thing". Those are different bugs with
    // different fixes, and guessing between them is what wasted the last two
    // rounds. Logged once per distinct layout state so it cannot spam.
    //
    // Read `shares=` first. If it is 0 while somebody is visibly sharing, the
    // share was never classified as a share and the layout is innocent.
    val layoutSignature = nodes.joinToString(" ") {
        it.id + if (it.isScreenShare) "[share]" else ""
    }
    LaunchedEffect(layoutSignature, layoutMode, pinnedNodeId) {
        println(
            "DEBUG [CallGrid] mode=$layoutMode pinned=$pinnedNodeId " +
                "shares=${screenShareNodes.size} faces=${nonShareNodes.size} " +
                "nodes=[$layoutSignature]"
        )
    }
    var sharePage by remember { mutableStateOf(0) }
    val clampedPage = if (screenShareNodes.isEmpty()) 0 else sharePage.coerceIn(0, screenShareNodes.lastIndex)
    val spotlightShareNode = screenShareNodes.getOrNull(clampedPage)

    // A screen share is featured ON TOP. Always.
    //
    // This used to depend on state, and that was the whole problem. In Grid mode
    // a share went on top, which was right. In Focus mode it went on the LEFT
    // with the other tiles stacked down the right-hand edge. A pin forced that
    // same branch even while Grid mode was still selected, so pinning any tile
    // at all silently reshaped the share as well.
    //
    // Side-by-side is the right shape for enlarging one person's face: they get
    // the height, and the faces nobody is watching get a thin column beside
    // them. It is the wrong shape for a document, a terminal or a game, because
    // a 16:9 share spends its whole life fighting a tall thin box and never gets
    // the full width of the window. Discord, Element, Zoom and Meet all put the
    // share on top with the people in a strip underneath, and not one of them
    // makes that depend on a layout toggle or on what somebody pinned.
    //
    // So this branch is chosen on the presence of a share, BEFORE layout mode and
    // BEFORE the pin are consulted. Focus mode is left meaning exactly one thing:
    // enlarge one face.
    //
    // The empty case is left alone on purpose. A share with nobody else in the
    // call has no strip to lay out, and it falls through to the single-tile card
    // further down, which is already a full-width panel.
    val featuredNode = featuredNodeFor(
        nodes = nodes,
        isShare = { it.isScreenShare },
        hasVideo = { it.renderer != null }
    )
    val stripNodes = if (featuredNode == null) nodes else nodes.filter { it.id != featuredNode.id }

    if (featuredNode != null && stripNodes.isNotEmpty()) {
        // A share wins over anything else for the panel, and the pager still
        // shifts between sharers when there is more than one.
        val featured = if (screenShareNodes.isNotEmpty()) screenShareNodes[clampedPage] else featuredNode
        val gap = 12.dp
        val stripHeight = CALL_SHARE_STRIP_HEIGHT
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                // `.voice-room-maximized { background-color: #000000 }` over
                // `.stream-theater-stage`'s #0b0c0e. Both are literal: a
                // letterbox is black whatever the app's theme is.
                .background(if (maximized) callStageBlack else tokens.Sunken)
        ) {
            // The share is a true 16:9 box, as large as the space above the strip
            // allows, centred in whatever is left over.
            //
            // It used to be `weight(2.5f).fillMaxWidth()`, and that is what made it
            // look wrong rather than merely imperfect. A weighted height ignores
            // the shape of the thing being shown: in a 1920x1080 window that weight
            // hands the share 1896x653, which is 2.9:1. A 16:9 screen inside a 2.9:1
            // box is drawn 1161px wide with a 367px black bar down each side, so
            // two thirds of the panel was empty and the strip below it had been
            // given a third of the window. Sizing the panel to the content's own
            // aspect is the fix; no weight ratio can express it, because the right
            // ratio changes with the window.
            //
            // Maximised, the strip is not there, so the panel gets everything --
            // the same function, a larger space, and therefore the same 16:9.
            val panelSpace = if (maximized) maxHeight else maxHeight - stripHeight - gap
            val panel = if (featured.isScreenShare) {
                sharePanelSizeIn(maxWidth, panelSpace)
            } else {
                // A face has no aspect to honour. It is drawn with Crop, so it
                // fills whatever box it is given, and giving it the whole area
                // above the strip is what makes a 1:1 read like a 1:1.
                SharePanelSize(maxWidth, panelSpace)
            }
            println(
                "DEBUG [CallGrid] ${if (maximized) "MAXIMIZED " else ""}" +
                    "ON TOP featured=${featured.id} share=${featured.isScreenShare} " +
                    "panel=${panel.width.value}x${panel.height.value}dp " +
                    "available=${maxWidth.value}x${panelSpace.value}dp " +
                    "strip=${stripNodes.map { it.id }}"
            )
            // The presentation area: everything above the participant strip, or
            // the whole stage once the call is maximised. The panel is centred in
            // it, and `.stream-bottom-right-actions` is pinned to ITS bottom-right
            // — which is where the concept pins them, because there the parent
            // `.stream-theater-stage` holds nothing but the stream.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (maximized) 0.dp else stripHeight + gap),
                contentAlignment = Alignment.Center
            ) {
                ShareSpotlightBox(
                    node = featured,
                    shareCount = screenShareNodes.size,
                    selectedPage = clampedPage,
                    onPageChange = { sharePage = it },
                    tokens = tokens,
                    callManager = callManager,
                    isPinned = featured.id == pinnedNodeId,
                    onPinToggle = onPinToggle,
                    modifier = Modifier
                        .width(panel.width)
                        .height(panel.height)
                )
                if (onToggleMaximized != null) {
                    CallStreamBottomActions(
                        isMaximized = maximized,
                        onToggleMaximized = onToggleMaximized
                    )
                }
            }
            if (!maximized) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(stripHeight)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        stripOrderFor(stripNodes, pinnedNodeId) { it.id }
                            .forEach { node ->
                                VideoNodeCard(
                                    node,
                                    tokens,
                                    callManager = callManager,
                                    isPinned = node.id == pinnedNodeId,
                                    onPinToggle = onPinToggle,
                                    modifier = Modifier
                                        .width(134.dp)
                                        .fillMaxHeight()
                                )
                            }
                    }
                }
            }
        }
        return
    }

    // -- Focus Mode: spotlight one participant, thumbnails on side --
    // A pinned tile forces the spotlight layout regardless of mode toggle;
    // pinned beats paged shares beats default remote pick.
    if ((layoutMode == CallLayoutMode.Focus || pinnedNodeId != null) && nodes.size >= 2) {
        val spotlightNode = nodes.find { it.id == pinnedNodeId }
            ?: spotlightShareNode
            ?: nodes.find { it.id == "remote" }
            ?: nodes.first()

        val thumbnailNodes = nodes.filter { it.id != spotlightNode.id }

        Row(
            modifier = modifier
                .fillMaxSize()
                .background(if (maximized) callStageBlack else Color.Transparent),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Main spotlight
            ShareSpotlightBox(
                node = spotlightNode,
                shareCount = screenShareNodes.size,
                selectedPage = clampedPage,
                onPageChange = { sharePage = it },
                tokens = tokens,
                callManager = callManager,
                isPinned = spotlightNode.id == pinnedNodeId,
                onPinToggle = onPinToggle,
                modifier = Modifier
                    .weight(3f)
                    .fillMaxHeight()
            )

            // Side thumbnails
            if (thumbnailNodes.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    thumbnailNodes.forEach { node ->
                        VideoNodeCard(
                            node,
                            tokens,
                            callManager = callManager,
                            isPinned = node.id == pinnedNodeId,
                            onPinToggle = onPinToggle,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }
        return
    }

    // -- Grid Mode --
    // The stage behind the tiles.
    //
    // Discord's call view is a dark room with slightly lighter tiles floating in
    // it. Rendering the stage as `t.Bg` (the chat surface, #313338) put lighter
    // tiles on a lighter stage, which flattened the whole view into one grey
    // rectangle with no sense of depth -- the single biggest reason the call
    // screen did not read as Discord. `Sunken` is the app's darkest surface and
    // matches Discord's stage; the tiles sit above it.
    //
    // Maximised, the concept paints the whole container #000000
    // (`.voice-room-maximized`), and that is the one place the stage is allowed
    // to be pure black rather than the app's darkest neutral.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (maximized) callStageBlack else Color.Transparent)
    ) {
    when {
        nodes.size == 1 -> {
            // Single participant — centered large card with empty panel below
            VideoNodeCard(
                node = nodes.first(),
                tokens = tokens,
                callManager = callManager,
                isPinned = nodes.first().id == pinnedNodeId,
                onPinToggle = onPinToggle,
                modifier = Modifier.fillMaxSize()
            )
        }

        nodes.size == 2 -> {
            // Equal side-by-side. A share never reaches here -- it was laid out
            // on top and returned from before this branch could be selected.
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                nodes.forEach { node ->
                    VideoNodeCard(node, tokens, callManager = callManager, isPinned = node.id == pinnedNodeId, onPinToggle = onPinToggle, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }

        else -> {
            // 2x2 or 2xN grid, CAPPED by [gridRowsFor].
            //
            // Discord's answer to a crowded call is the right one: show what
            // fits, and say how many are not shown. The overflow tile is
            // clickable nowhere on purpose -- it is a count, not a control, and
            // pretending otherwise would be the same lie as a dead button.
            //
            // Cameras only. A share was laid out on top and returned from long
            // before this branch could be selected.
            val (visibleRows, hiddenCount) = gridRowsFor(nodes)
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                visibleRows.forEach { rowNodes ->
                    val isOddRow = rowNodes.size < 2
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = if (isOddRow) Arrangement.Center else Arrangement.spacedBy(12.dp)
                    ) {
                        rowNodes.forEach { node ->
                            VideoNodeCard(
                                node,
                                tokens,
                                callManager = callManager,
                                isPinned = node.id == pinnedNodeId,
                                onPinToggle = onPinToggle,
                                modifier = if (isOddRow) {
                                    Modifier.fillMaxWidth(0.5f).fillMaxHeight()
                                } else {
                                    Modifier.weight(1f).fillMaxHeight()
                                }
                            )
                        }
                    }
                }
                if (hiddenCount > 0) {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(CallTileShape)
                                // The same `--bg-call-card` as every other tile.
                                // It was `tokens.Sunken`, which is lighter, so the
                                // count read as a hole punched in the grid rather
                                // than as the last tile of it.
                                .background(callCardSurface(tokens))
                                .border(1.dp, tokens.Separator, CallTileShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "+$hiddenCount",
                                color = tokens.OnDark.copy(alpha = 0.7f),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
    }
}

/**
 * How many rows of tiles the 2-column grid may lay out.
 *
 * Four rows is eight faces, which is the point past which a grid tile stops being
 * a face and starts being a thumbnail -- and past which every tile in the column is
 * small enough that a muted-mic badge or a raised hand is unreadable anyway.
 */
internal const val CALL_GRID_MAX_ROWS = 4

/**
 * Splits [nodes] into the rows the grid will actually draw, and counts the ones it
 * will not.
 *
 * Extracted from the composable, and taking a plain list, so the cap can be pinned
 * by a test instead of asserted by eye. Before this existed the grid was
 * `nodes.chunked(2)` with every row at `weight(1f)` and no limit at all: ten
 * participants meant five rows each taking a fifth of the height. Nothing spilled
 * off-screen -- it was worse than that, because each tile shrank to a sliver while
 * still being composed and still being handed frames, so a busy call looked broken
 * rather than crowded.
 */
internal fun <T> gridRowsFor(
    nodes: List<T>,
    columns: Int = 2,
    maxRows: Int = CALL_GRID_MAX_ROWS
): Pair<List<List<T>>, Int> {
    if (columns < 1) return emptyList<List<T>>() to nodes.size
    val visible = nodes.chunked(columns).take(maxRows)
    return visible to (nodes.size - visible.sumOf { it.size })
}

/**
 * The order of the participant strip underneath a shared screen.
 *
 * A pin moves its tile to the FRONT of the strip. It does not move it into the
 * featured panel, because while somebody is presenting, that panel is theirs.
 *
 * This is the other half of the same defect the on-top layout above fixes. The
 * old code let any pin drag the whole call into the side-by-side layout, so the
 * share lost the top of the window and half its width as a side effect of
 * pinning a face. Pinning is still honoured -- the tile jumps the queue and
 * keeps its marker -- but it can no longer take the presentation away from
 * everyone else who is in the call.
 *
 * Extracted so the decision can be pinned by a test rather than eyeballed, on
 * the grounds that it is the rule most likely to be quietly undone later by
 * someone tidying the layout code.
 */
internal fun <T> stripOrderFor(
    faces: List<T>,
    pinnedNodeId: String?,
    id: (T) -> String
): List<T> {
    if (pinnedNodeId == null) return faces
    return faces.filter { id(it) == pinnedNodeId } + faces.filter { id(it) != pinnedNodeId }
}

/**
 * The node that gets the big panel on top, or null to use the plain grid.
 *
 * This deliberately does NOT require knowing whether something is a screen share.
 *
 * Run 47 ended that line of enquiry. Element's SDP for a shared screen in a 1:1 is
 * a single video m-line with a bare-UUID msid and no screen-share marker anywhere
 * in it:
 *
 *     m=video 9 UDP/TLS/RTP/SAVPF 96 98 100 ...
 *     a=mid:1
 *     a=msid:8c8aae6b-... ee0cac5d-...
 *
 * So the share is genuinely indistinguishable from a camera by signalling, and any
 * layout that waits to be told will keep rendering it wrong no matter how good the
 * classifier is.
 *
 * The rule that needs no such knowledge is the one every real client uses anyway:
 * when exactly one tile in a two-person call has live video, that tile is what the
 * caller is looking at, so it gets the panel and the other goes in the strip. That
 * is Discord's 1:1, and it is what the screenshot should have shown -- instead
 * half the window went to a local avatar with its camera off.
 *
 * A share still outranks this, because when the share is identified it is known to
 * be what people are watching. This is the fallback for when it cannot be.
 */
internal fun <T> featuredNodeFor(
    nodes: List<T>,
    isShare: (T) -> Boolean,
    hasVideo: (T) -> Boolean
): T? {
    val shares = nodes.filter(isShare)
    if (shares.isNotEmpty()) return shares.first()
    // Two people, one of them showing video: that one is the call.
    if (nodes.size == 2) {
        val withVideo = nodes.filter(hasVideo)
        if (withVideo.size == 1) return withVideo.first()
    }
    return null
}

/**
 * Longest edge an inbound 1:1 frame is downscaled to.
 *
 * The 1:1 path had NO resolution limit at all -- only a frame-interval gate --
 * while a comment in WebRtcClientImpl claimed it was already capped at 1280. So a
 * peer sharing a 4K screen made the renderer allocate a 3840x2160 ARGB image
 * (about 33 MB) and run 8.3 million pixel conversions 15 times a second, on a
 * WebRTC sink thread. The frame-time comments there also claimed 20 fps where the
 * code said 66 ms, which is 15.
 *
 * **1920, not the group path's 1280, and deliberately so.** 1280 was tried first
 * and the test suite caught what it would have done: 1920x1080 is the most common
 * screen-share resolution there is, and an integer downscale to a 1280 long edge
 * halves it to 960x540. That is a real loss of legibility on text, which is the
 * entire content of a screen share -- it would have manufactured exactly the
 * "blurry stream" complaint this was meant to address.
 *
 * So the limit sits at 1920: a 1080p share passes through untouched, a 4K share is
 * halved to 1080p, and an 8K share comes down to 1080p. The audio-starvation risk
 * is closed without degrading anything anybody actually sends at a normal
 * resolution. The two paths now cost different amounts, which is honest: a group
 * call with a dozen 1080p tiles is a different problem from a 1:1 with one.
 */
internal const val INBOUND_VIDEO_MAX_LONG_EDGE = 1920

/** 20 fps. The code said 66 ms (15 fps) while the comment claimed 20. */
internal const val INBOUND_VIDEO_MIN_FRAME_INTERVAL_NS = 50_000_000L

private const val MIN_FRAME_INTERVAL_NS = INBOUND_VIDEO_MIN_FRAME_INTERVAL_NS

/** Height of the participant strip that sits under a shared screen. */
internal val CALL_SHARE_STRIP_HEIGHT = 80.dp

/** The aspect a shared screen is presented at. */
internal const val CALL_SHARE_ASPECT = 16f / 9f

internal data class SharePanelSize(val width: Dp, val height: Dp)

/**
 * The largest box of the given aspect ratio that fits in the space available.
 *
 * A shared screen is 16:9 and the panel it is drawn in has to be 16:9 too, or the
 * renderer letterboxes it and the panel ends up mostly black. This picks whichever
 * of width-first or height-first still fits, which is the only two answers there
 * are.
 *
 * Extracted and tested because the alternative -- expressing the shape as a
 * `weight()` -- cannot express it at all. A weight is a fraction of the window and
 * the correct fraction changes with the window's shape, so the same 2.5f that looks
 * right on a 16:10 laptop turns a 1080p window into a letterboxed strip.
 */
internal fun sharePanelSizeIn(
    availableWidth: Dp,
    availableHeight: Dp,
    aspect: Float = CALL_SHARE_ASPECT
): SharePanelSize {
    if (availableWidth <= 0.dp || availableHeight <= 0.dp || aspect <= 0f) {
        return SharePanelSize(0.dp, 0.dp)
    }
    val heightIfFullWidth = availableWidth / aspect
    return if (heightIfFullWidth <= availableHeight) {
        SharePanelSize(availableWidth, heightIfFullWidth)
    } else {
        SharePanelSize(availableHeight * aspect, availableHeight)
    }
}

/**
 * Spotlight slot for a screen-share node with prev/next arrows overlaid when
 * more than one person is sharing. Wraps around so the arrows always work.
 */
@Composable
private fun ShareSpotlightBox(
    node: RenderNode,
    shareCount: Int,
    selectedPage: Int,
    onPageChange: (Int) -> Unit,
    tokens: CosmicTokens,
    callManager: CallManager? = null,
    isPinned: Boolean = false,
    onPinToggle: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        VideoNodeCard(
            node, tokens,
            callManager = callManager,
            isPinned = isPinned,
            onPinToggle = onPinToggle,
            modifier = Modifier.fillMaxSize()
        )
        if (shareCount > 1) {
            SharePagerArrows(
                page = selectedPage,
                count = shareCount,
                onPageChange = onPageChange,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

@Composable
private fun SharePagerArrows(
    page: Int,
    count: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val t = rememberCosmicTokens()
    Box(modifier = modifier) {
        PagerArrowButton(
            icon = OrbitIcons.ChevronLeft,
            contentDescription = "Previous shared screen",
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
            onClick = { onPageChange((page - 1 + count) % count) }
        )
        PagerArrowButton(
            icon = OrbitIcons.ChevronRight,
            contentDescription = "Next shared screen",
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
            onClick = { onPageChange((page + 1) % count) }
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .background(ui.theme.Ink950.copy(alpha = 0.75f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = "${page + 1} / $count",
                color = t.OnDark.copy(alpha = 0.75f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun PagerArrowButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    IconButton(
        onClick = onClick,
        modifier = modifier
            .hoverable(interaction)
            .size(36.dp)
            .background(ui.theme.Ink950.copy(alpha = if (isHovered) 0.85f else 0.55f), CircleShape)
            .border(1.dp, t.OnDark.copy(alpha = 0.08f), CircleShape)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = t.OnDark.copy(alpha = 0.9f),
            modifier = Modifier.size(18.dp)
        )
    }
}
