package ui.screen.server

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions



import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import repository.OrbitRepository
import ui.assets.OrbitServerAssetIcon
import ui.components.OrbitEmptyState
import ui.components.orbitClickable
import ui.components.rememberOrbitInteraction
import ui.model.OrbitServer
import ui.screen.orbitGlobalShortcuts
import ui.theme.OrbitConceptBorder
import ui.theme.OrbitConceptDark
import ui.theme.OrbitConceptTheme
import ui.theme.OrbitSpace
import ui.icons.TablerIcons
import ui.icons.Search
import ui.icons.X
// ── Concept modal geometry ────────────────────────────────────────────────
// ExploreModal.jsx: a 840px card, `border-radius: 10px`, `overflow: hidden`,
// `max-height: 85vh`, on `.modal-overlay`.
private val ExploreCardWidth = 840.dp

/** `border-radius: '10px'` on the Explore card and on each card in the grid. */
private val ExploreCardShape = RoundedCornerShape(10.dp)

/** `border-radius: '8px'` on the search field. */
private val ExploreSearchShape = RoundedCornerShape(8.dp)

/** `border-radius: '12px'` on a card's 48px server icon. */
private val ExploreIconShape = RoundedCornerShape(12.dp)

/** `border-radius: '6px'` on the "Join Server" button. */
private val ExploreButtonShape = RoundedCornerShape(6.dp)

/**
 * `#eb459e` — the far stop of ExploreModal's hero gradient
 * (`linear-gradient(135deg, #5865F2 0%, #eb459e 100%)`). Not in OrbitConcept.kt.
 */
private val ExploreGradientEnd = Color(0xFFEB459E)

/** `rgba(0, 0, 0, 0.4)` — the literal on the hero's close button. */
private val ExploreCloseScrim = Color(0x66000000)

/** `rgba(255, 255, 255, 0.85)` — the literal on the hero's subtitle. */
private val ExploreHeroSubtitle = Color(0xD9FFFFFF)

/** `--text-danger` (#fa777c), same as the Add Server modal's error copy. */
private val ExploreDangerText = Color(0xFFFA777C)

/**
 * `max-height: 85vh`.
 *
 * A Compose `Dialog` measures its content with unbounded constraints and sizes
 * its own native window to the result, so a viewport-relative ceiling cannot be
 * expressed. 620dp stands in: it is 85% of a 730dp-tall window, which is about
 * the smallest height at which this window size class is used, and the grid
 * scrolls past it either way.
 */
private val ExploreCardMaxHeight = 620.dp

/**
 * Explore — the concept's `ExploreModal.jsx`, translated value for value.
 *
 *  - card `width: 840px`, `border-radius: 10px`, `overflow: hidden`,
 *    `background-color: var(--bg-chat)`, `box-shadow: var(--elevation-floating)`
 *  - hero `height: 160px`, `linear-gradient(135deg, #5865F2 0%, #eb459e 100%)`,
 *    `padding: 20px`, centred column; a 26px / 800 white headline and a 14px
 *    `rgba(255,255,255,0.85)` subtitle
 *  - the close X is `rgba(0,0,0,0.4)`, 32px, `top: 16px; right: 16px`
 *  - the search bar row is `padding: 20px 24px 10px`; the field itself is
 *    `--bg-secondary` on a `--border-subtle` hairline, 8px corners,
 *    `padding: 10px 14px`, 15px, with a `--text-muted` 18px search glyph
 *  - the grid is `repeat(auto-fit, minmax(240px, 1fr))` with a 16px gap and
 *    `padding: 16px 24px 24px` — which is `GridCells.Adaptive(240.dp)`
 *  - each card is `--bg-secondary`, 10px corners, `overflow: hidden`, a
 *    `--border-subtle` hairline, a 110px banner with the 48px icon punched
 *    through its bottom-left corner on a 3px `--bg-secondary` ring, a
 *    16px / 700 name, a 13px / 1.4 description and a `--brand-blurple` button
 *    at `padding: 8px`, 6px corners, 14px / 700
 *
 * ## What the grid actually lists
 *
 * The concept fills it from `discoverableServers`, a mock array of public
 * Discord communities. Matrix has no equivalent: `OrbitRepository` exposes
 * `getSpaces`/`observeSpaces` and `joinSpace`, and nothing that enumerates a
 * directory of servers you are not in. Inventing a card list here would have
 * put eight fake communities in a shipping client, so this lists the spaces the
 * account is actually in — which is real, filterable, and what the eye is being
 * pointed at anyway — and the action on a card opens it.
 *
 * Joining something new is still one keystroke away and is the concept's own
 * `handleJoin`: type a room alias into the search field and submit. A Matrix
 * room alias always contains a colon and a matrix.to invite always contains
 * "matrix.to", which is how the join card knows whether the query is a target
 * or just words to filter by.
 *
 * ## The backdrop
 *
 * `.modal-overlay` is `rgba(0,0,0,0.75)` + `backdrop-filter: blur(6px)` over the
 * whole viewport. As in [JoinServerScreen], this stays a real `Dialog` so Escape,
 * outside clicks and keyboard focus all work, and a `Dialog` is its own native
 * window sized to its content — so a viewport-wide scrim cannot be drawn inside
 * it, and `backdrop-filter` has no Compose equivalent.
 */
@Composable
fun ExploreServersDialog(
    repository: OrbitRepository,
    onClose: () -> Unit,
    onOpenServer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var isJoining by remember { mutableStateOf(false) }
    var joinError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    val spaces by remember(repository) { repository.observeSpaces() }
        .collectAsState(emptyList())

    val trimmed = query.trim()
    // A Matrix alias is always `#room:server`; a matrix.to invite always
    // contains "matrix.to". Anything else is a filter, not a target.
    val looksLikeTarget = trimmed.contains(":") || trimmed.contains("matrix.to")

    val matches = spaces.filter { !it.isDM }.filter { space ->
        trimmed.isEmpty() ||
            space.name.contains(trimmed, ignoreCase = true) ||
            space.description.contains(trimmed, ignoreCase = true) ||
            space.id.contains(trimmed, ignoreCase = true)
    }

    fun joinTarget(target: String) {
        if (target.isBlank() || isJoining) return
        isJoining = true
        joinError = null
        scope.launch {
            repository.joinSpace(target)
                .onSuccess { roomId ->
                    isJoining = false
                    onOpenServer(roomId)
                    onClose()
                }
                .onFailure { error ->
                    joinError = error.message ?: "Could not join that space"
                    isJoining = false
                }
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Column(
            modifier = modifier
                // A Dialog is its own native window, so app-wide shortcuts never
                // reach it unless the same dispatch is installed here.
                .orbitGlobalShortcuts()
                .width(ExploreCardWidth)
                // max-height: 85vh — see ExploreCardMaxHeight.
                .heightIn(max = ExploreCardMaxHeight)
                // border-radius: 10px + box-shadow: var(--elevation-floating)
                // (0 16px 32px rgba(0,0,0,0.4)). The shape goes into `shadow`
                // too, so the shadow follows the radius rather than drawing a
                // rectangle behind a rounded card.
                .shadow(16.dp, ExploreCardShape)
                .clip(ExploreCardShape)
                // background-color: var(--bg-chat)
                .background(OrbitConceptTheme.chat)
        ) {
            ExploreHero(onClose = onClose)

            // padding: 20px 24px 10px
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = OrbitSpace.xxl, end = OrbitSpace.xxl, top = OrbitSpace.xl, bottom = 10.dp)
            ) {
                ExploreSearchField(
                    value = query,
                    onValueChange = { query = it; joinError = null },
                    placeholder = "Explore your spaces, or paste an invite…",
                    onSubmit = { if (looksLikeTarget) joinTarget(trimmed) },
                    modifier = Modifier.weight(1f)
                )
            }

            if (joinError != null) {
                Text(
                    text = joinError!!,
                    color = ExploreDangerText,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = OrbitSpace.xxl)
                        .padding(bottom = OrbitSpace.sm)
                )
            }

            // padding: 16px 24px 24px, gap: 16px
            // → GridCells.Adaptive(240.dp) with 16dp between items, which is
            // the CSS grid's `repeat(auto-fit, minmax(240px, 1fr))`.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(240.dp),
                state = gridState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(
                    start = OrbitSpace.xxl,
                    end = OrbitSpace.xxl,
                    top = OrbitSpace.lg,
                    bottom = OrbitSpace.xxl
                ),
                horizontalArrangement = Arrangement.spacedBy(OrbitSpace.lg),
                verticalArrangement = Arrangement.spacedBy(OrbitSpace.lg)
            ) {
                if (looksLikeTarget) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "join-target") {
                        ExploreJoinCard(
                            target = trimmed,
                            isJoining = isJoining,
                            onJoin = { joinTarget(trimmed) }
                        )
                    }
                }

                items(count = matches.size, key = { matches[it].id }) { index ->
                    ExploreServerCard(space = matches[index], onOpen = {
                        onOpenServer(matches[index].id)
                        onClose()
                    })
                }

                if (matches.isEmpty() && !looksLikeTarget) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        OrbitEmptyState(
                            title = if (trimmed.isEmpty()) "No spaces yet" else "Nothing matches",
                            body = if (trimmed.isEmpty()) {
                                "Join a server from the Add Server dialog and it will show up here."
                            } else {
                                "Try another name, or paste a room alias like #general:example.org to join something new."
                            },
                            modifier = Modifier.fillMaxWidth().height(220.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * The 160px gradient header.
 *
 * `height: 160px`, `background: linear-gradient(135deg, #5865F2 0%, #eb459e
 * 100%)`, `padding: 20px`, centred column, with the headline 8px above the
 * subtitle.
 */
@Composable
private fun ExploreHero(onClose: () -> Unit) {
    // `background: linear-gradient(135deg, #5865F2 0%, #eb459e 100%)`.
    //
    // CSS 135deg runs from the top-left corner to the bottom-right one, so the
    // gradient line is exactly (width, height) here. BoxWithConstraints is used
    // only to get that size: `Modifier.background` is applied before the node
    // has been measured, and Brush.linearGradient wants the end point, not a
    // direction.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
    ) {
        val endX = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
        val endY = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.toPx() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(OrbitConceptDark.brand, ExploreGradientEnd),
                        start = androidx.compose.ui.geometry.Offset.Zero,
                        end = androidx.compose.ui.geometry.Offset(endX, endY)
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    // padding: 20px
                    .padding(OrbitSpace.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
            ) {
                Text(
                    text = "Find your community on Matrix",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 26.sp,
                        lineHeight = 32.sp
                    ),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "From gaming, to music, to learning, there's a place for you.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = ExploreHeroSubtitle,
                        fontSize = 14.sp
                    ),
                    textAlign = TextAlign.Center
                )
            }

            // position: absolute; top: 16px; right: 16px;
            // background: rgba(0,0,0,0.4); width/height: 32px;
            // border-radius: 50%
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = OrbitSpace.lg, end = OrbitSpace.lg)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(ExploreCloseScrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Close",
                        onClick = onClose
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = with(TablerIcons) { X },
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * `background-color: var(--bg-secondary)`, `border-radius: 8px`,
 * `padding: 10px 14px`, `border: 1px solid var(--border-subtle)`, 15px, with a
 * 18px `--text-muted` glyph at the trailing edge.
 */
@Composable
private fun ExploreSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = rememberOrbitInteraction()
    val focused by interaction.focusSource.collectIsFocusedAsState()
    val frame by animateColorAsState(
        targetValue = if (focused) OrbitConceptDark.brand else OrbitConceptBorder.subtle(),
        animationSpec = androidx.compose.animation.core.tween(120),
        label = "exploreFieldFrame"
    )
    val fieldStyle = MaterialTheme.typography.bodyMedium.copy(
        color = OrbitConceptTheme.textNormal,
        fontSize = 15.sp
    )

    Box(
        modifier = modifier
            .clip(ExploreSearchShape)
            .background(OrbitConceptTheme.secondary)
            .border(1.dp, frame, ExploreSearchShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = fieldStyle.copy(color = OrbitConceptTheme.textMuted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = fieldStyle,
                    interactionSource = interaction.focusSource,
                    cursorBrush = SolidColor(OrbitConceptDark.brand),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { onSubmit() },
                        onDone = { onSubmit() },
                        onGo = { onSubmit() }
                    )
                )
            }
            Spacer(Modifier.width(OrbitSpace.sm))
            Icon(
                imageVector = with(TablerIcons) { Search },
                contentDescription = null,
                tint = OrbitConceptTheme.textMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * One card in the grid: 110px banner, a 48px icon punched through its
 * bottom-left corner, then `padding: 24px 16px 16px` of identity, description,
 * meta and the primary button.
 *
 * The description is `--bg-secondary` inside a `--bg-secondary` card in the
 * concept too — it reads because the banner separates them. `flex: 1` on the
 * description and the meta row is what keeps the buttons on a common baseline
 * across a row of cards of unequal length, so the description takes the slack.
 */
@Composable
private fun ExploreServerCard(space: OrbitServer, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ExploreCardShape)
            .background(OrbitConceptTheme.secondary)
            .border(1.dp, OrbitConceptBorder.subtle(), ExploreCardShape)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // height: 110px
                .height(110.dp)
                .background(OrbitConceptDark.brand)
        ) {
            val bannerUrl = space.bannerUrl
            if (bannerUrl != null) {
                coil3.compose.AsyncImage(
                    model = bannerUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // position: absolute; bottom: -16px; left: 16px;
            // width/height: 48px; border-radius: 12px;
            // border: 3px solid var(--bg-secondary)
            //
            // Wrapped in its own zero-elevation Box because `offset` and
            // `padding` are positional, not sizing: `bottom: -16px` is a 16dp
            // downward offset and `left: 16px` is a 16dp leading pad on the
            // wrapper, with the 48dp applied to the icon itself.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(y = 16.dp)
                    .padding(start = 16.dp)
            ) {
                val avatarUrl = space.avatarUrl
                if (avatarUrl != null) {
                    coil3.compose.AsyncImage(
                        model = avatarUrl,
                        contentDescription = space.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(ExploreIconShape)
                            .border(3.dp, OrbitConceptTheme.secondary, ExploreIconShape)
                    )
                } else {
                    // Same curated pastel as the rail, so a server looks the
                    // same here as it does once you have joined it.
                    val pastel = ui.theme.pastelServerColor(space.id)
                    OrbitServerAssetIcon(
                        label = initialsOf(space.name),
                        color = pastel,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(ExploreIconShape)
                            .background(pastel)
                            .border(3.dp, OrbitConceptTheme.secondary, ExploreIconShape)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // padding: 24px 16px 16px — the 24px top clears the icon that
                // overhangs the banner by 16px.
                .padding(start = OrbitSpace.lg, end = OrbitSpace.lg, top = 24.dp, bottom = OrbitSpace.lg)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Text(
                    text = space.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = OrbitConceptTheme.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // The concept draws a 15px `--brand-blurple` check next to the
                // name when `server.verified` is set. `OrbitServer` has no
                // verified flag and inventing one per space is not possible, so
                // the badge is omitted rather than stamped on every card.
            }

            Text(
                text = space.description.ifBlank { "No description" },
                style = MaterialTheme.typography.bodySmall.copy(
                    color = OrbitConceptTheme.textMuted,
                    fontSize = 13.sp,
                    // line-height: 1.4
                    lineHeight = 18.2.sp
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = OrbitSpace.md)
            )

            // The concept puts "N Online • N Members" here. Orbit has no member
            // counts on an OrbitServer — the spaces API returns a name, an alias
            // and two URLs — so the space's Matrix id stands in, which is the
            // thing you actually need in order to share it.
            Text(
                text = space.id,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = OrbitConceptTheme.textMuted,
                    fontSize = 12.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = OrbitSpace.lg)
            )

            ExplorePrimaryButton(
                text = "Open",
                onClick = onOpen
            )
        }
    }
}

/**
 * The concept's "Join Server" card, for a room alias typed into the search bar.
 *
 * Same card metrics as [ExploreServerCard], with the hero gradient standing in
 * for a banner Orbit does not have yet and the concept's "Join Server" copy.
 */
@Composable
private fun ExploreJoinCard(target: String, isJoining: Boolean, onJoin: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ExploreCardShape)
            .background(OrbitConceptTheme.secondary)
            .border(1.dp, OrbitConceptBorder.subtle(), ExploreCardShape)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(OrbitConceptDark.brand, ExploreGradientEnd)
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(y = 16.dp)
                    .padding(start = 16.dp)
                    .size(48.dp)
                    .clip(ExploreIconShape)
                    .background(OrbitConceptDark.brand.copy(alpha = 0.35f))
                    .border(3.dp, OrbitConceptTheme.secondary, ExploreIconShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = with(TablerIcons) { Search },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = OrbitSpace.lg, end = OrbitSpace.lg, top = 24.dp, bottom = OrbitSpace.lg)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Text(
                    text = target,
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = OrbitConceptTheme.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "A room alias or invite you are not in yet. Joining here adds it to your spaces.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = OrbitConceptTheme.textMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.2.sp
                ),
                modifier = Modifier.padding(bottom = OrbitSpace.lg)
            )
            ExplorePrimaryButton(
                text = "Join Server",
                onClick = onJoin,
                loading = isJoining
            )
        }
    }
}

/**
 * `background-color: var(--brand-blurple)`, `color: #fff`, `padding: 8px`,
 * `border-radius: 6px`, `font-size: 14px`, `font-weight: 700`.
 *
 * Hover and press use the concept's `--brand-blurple-hover` and
 * `--brand-blurple-active`. `OrbitButton(Primary)` is not used here because its
 * hover is `t.AccentLight` — a different colour, and one tied to whichever of
 * the app's accent themes is active.
 */
@Composable
private fun ExplorePrimaryButton(
    text: String,
    onClick: () -> Unit,
    loading: Boolean = false
) {
    val interaction = rememberOrbitInteraction()
    val hovered by interaction.hoverSource.collectIsHoveredAsState()
    val pressed by interaction.pressSource.collectIsPressedAsState()

    val bg by animateColorAsState(
        targetValue = when {
            pressed -> OrbitConceptDark.brandActive
            hovered -> OrbitConceptDark.brandHover
            else -> OrbitConceptDark.brand
        },
        animationSpec = androidx.compose.animation.core.tween(120),
        label = "exploreBtnBg"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ExploreButtonShape)
            .background(bg)
            .hoverable(interaction.hoverSource)
            .orbitClickable(interaction, role = Role.Button, onClickLabel = text, onClick = onClick)
            // padding: 8px
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            )
        }
    }
}