package ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import ui.icons.TablerIcons
import ui.icons.Phone
import ui.icons.Video
import ui.icons.X
import ui.assets.OrbitDefaultAvatar
import ui.assets.OrbitMascotAvatar
import ui.assets.OrbitVerifiedBadge
import ui.assets.isOrbitVerifiedUser
import ui.components.OrbitButton
import ui.components.OrbitButtonTone
import ui.components.OrbitScrollbar
import ui.model.OrbitUser
import ui.theme.OrbitConceptBorder
import ui.theme.OrbitConceptDark
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens
/** The avatar with its presence dot, as every list and header in the app draws it. */
@Composable
internal fun SmallUserAvatar(user: OrbitUser, size: Dp) {
    val t = rememberCosmicTokens()
    Box(modifier = Modifier.size(size)) {
        if (user.avatarUrl != null) {
            coil3.compose.AsyncImage(
                model = user.avatarUrl,
                contentDescription = user.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        } else {
            OrbitMascotAvatar(
                seed = user.id,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .size(if (size > 40.dp) 13.dp else 9.dp)
                .align(Alignment.BottomEnd)
                .clip(CircleShape)
                .background(memberStatusColor(user.status))
                .border(2.dp, t.Members_Bg, CircleShape)
        )
    }
}

// ── Concept card geometry ────────────────────────────────────────────────
// UserProfilePopover.jsx sets `style={{ width: '340px' }}` on the card, which
// overrides `.user-profile-popover { width: 300px }` in index.css. 340 is the
// value that actually rendered, so 340dp is what the panel is.
private val ProfileCardWidth = 340.dp

/** `border-radius: 12px` on `.user-profile-popover`. */
private val ProfileCardShape = RoundedCornerShape(12.dp)

/** The badges pill: `border-radius: 12px` on `.popover-badges`. */
private val ProfileBadgeShape = RoundedCornerShape(12.dp)

/** `rgba(0, 0, 0, 0.6)` — the literal on `.popover-badges`. */
private val ProfileBadgeScrim = Color(0x99000000)

/** `rgba(0, 0, 0, 0.5)` — the literal on the popover's close button. */
private val ProfileCloseScrim = Color(0x80000000)

/**
 * The user profile card, from the concept's `UserProfilePopover.jsx`.
 *
 * ## What changed
 *
 * The old panel opened on a 150dp three-stop gradient running from the user's
 * avatar colour through the accent into a flat cyan — the single most neon
 * surface in the app — under a 22sp ExtraBold name, with four rows and a
 * full-width button that all did nothing. Those are gone, and so is the neon:
 * the concept's card is a *quiet* one, `--bg-floating` with an 80dp banner, a
 * 72dp avatar punched through it on a 6dp ring in the card's own colour, and
 * one hairline.
 *
 * The mapping, rule for rule:
 *
 *  | CSS                                        | here                          |
 *  |--------------------------------------------|-------------------------------|
 *  | `.user-profile-popover` width 340px        | [ProfileCardWidth]            |
 *  | `background-color: var(--bg-floating)`     | `OrbitConceptDark.floating`   |
 *  | `border-radius: 12px`                      | [ProfileCardShape]            |
 *  | `box-shadow: var(--elevation-floating)`     | `shadow(16.dp)`               |
 *  | `border: 1px solid var(--border-subtle)`   | `OrbitConceptBorder.subtle()` |
 *  | `.popover-banner` height 80px              | 80.dp, `--brand-blurple` fill |
 *  | `.popover-avatar-wrap` top 44 / left 16    | `offset(x = 16.dp, y = 44.dp)`|
 *  | 72x72, `border: 6px solid --bg-floating`   | 72.dp + `padding(6.dp)`       |
 *  | `.popover-badges` top 92 / right 16        | `offset(y = 92.dp)` + 16.dp   |
 *  | `.popover-body` padding 50/16/16, gap 12   | `padding` + `spacedBy(12.dp)` |
 *  | `.popover-name` 18px / 800                 | 18.sp / Bold                  |
 *  | `.popover-tag` 13px, `--text-muted`        | 13.sp                         |
 *  | `.popover-section-title` 11px / 700        | 11.sp / Bold, uppercased      |
 *  | `.popover-about` 13px, line-height 1.4     | 13.sp / 18.2.sp               |
 *
 * ## What is not here, and why
 *
 * The concept's ABOUT ME copy, ROLES pills, NOTE field and DM composer have no
 * data behind them in Orbit — `OrbitUser` carries a display name, a username, an
 * MXID and a presence, and nothing else. The fields below are the ones this app
 * actually knows, at the concept's type scale. The call buttons are kept: they
 * are wired, and `callRoomId` is still null for a group-room member, where they
 * are hidden rather than offered as a call that cannot happen.
 */
@Composable
internal fun DirectProfilePanel(
    user: OrbitUser,
    // CallManager.startCall takes a ROOM id; the panel used to pass user.id, so
    // the buttons dialled a non-existent room. Null (a member-list profile in a
    // group room) hides them rather than offering a call that cannot work.
    callRoomId: String? = null,
    /**
     * Dismiss the panel.
     *
     * This did not exist, and the panel was a one-way door. `ChatScreen` holds
     * `memberProfile` in state and the panel switch ranks a non-null profile
     * ABOVE the Members toggle:
     *
     * ```
     * shownProfile != null -> 0     // profile wins
     * memberPanelOpen     -> 2
     * ```
     *
     * So tapping a member set the state, the toggle stopped mattering, and
     * nothing in the app ever cleared it — no button, no Escape, no route back.
     * Reported with a screenshot of a window that could not be navigated out of.
     */
    onClose: (() -> Unit)? = null
) {
    val callManager = webrtc.LocalCallManager.current
    val listState = rememberLazyListState()
    val verified = isOrbitVerifiedUser(user.username, user.displayName, user.id)
    val t = rememberCosmicTokens()

    Column(
        modifier = Modifier
            .width(ProfileCardWidth)
            .fillMaxHeight()
            // box-shadow: var(--elevation-floating) — 0 16px 32px rgba(0,0,0,0.4).
            // The shape is passed to `shadow` so the shadow follows the 12px
            // radius rather than drawing a rectangle behind a rounded card.
            .shadow(16.dp, ProfileCardShape)
            // border-radius: 12px, overflow: hidden
            .clip(ProfileCardShape)
            // background-color: var(--bg-floating)
            .background(t.Elevated)
            // border: 1px solid var(--border-subtle)
            .border(1.dp, t.Separator, ProfileCardShape)
    ) {
        // ── Banner + avatar + badges ────────────────────────────────
        Box(modifier = Modifier.fillMaxWidth()) {
            // .popover-banner { height: 80px; background-size: cover;
            //                   background-position: center }
            // The concept falls back to `user.bannerColor || '#5865f2'`; Orbit
            // has no per-user banner colour, so it falls back to blurple.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(t.Accent)
            ) {
                val bannerUrl = user.bannerUrl
                if (bannerUrl != null) {
                    coil3.compose.AsyncImage(
                        model = bannerUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // .popover-avatar-wrap { position: absolute; top: 44px; left: 16px;
            //   width/height: 72px; border-radius: 50%;
            //   border: 6px solid var(--bg-floating) }
            //
            // Punched through the banner rather than sitting below it, which is
            // what makes the card read as one object. The ring is the card's own
            // surface colour, so the banner appears to be cut by a circle.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 16.dp, y = 44.dp)
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(t.Elevated)
                    .padding(6.dp)
            ) {
                ProfileCardAvatar(user)
            }

            // .popover-badges { display: flex; gap: 4px; position: absolute;
            //   top: 92px; right: 16px; background-color: rgba(0,0,0,0.6);
            //   padding: 4px 8px; border-radius: 12px }
            if (verified) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = 92.dp)
                        .padding(end = 16.dp)
                        .clip(ProfileBadgeShape)
                        .background(ProfileBadgeScrim)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OrbitVerifiedBadge(modifier = Modifier.size(16.dp))
                }
            }

            // The concept's close control: absolute, top 10px, right 10px, a
            // 26px circle on rgba(0,0,0,0.5). Always present rather than
            // hover-only — it is the only way out of this panel.
            if (onClose != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 10.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(ProfileCloseScrim)
                        .clickable(role = Role.Button, onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = with(TablerIcons) { X },
                        contentDescription = "Close profile",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // .popover-body { padding: 50px 16px 16px; gap: 12px }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 50.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                // .popover-name { font-size: 18px; font-weight: 800;
                //   color: var(--header-primary) }
                Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = t.Text_White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        lineHeight = 24.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // .popover-tag { font-size: 13px; color: var(--text-muted) }
                Text(
                    text = "@${user.username.ifBlank { user.id }}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = t.Text_Muted,
                        fontSize = 13.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // .popover-divider { height: 1px; background-color: var(--border-subtle) }
            HorizontalDivider(color = t.Separator, thickness = 1.dp)

            if (callRoomId != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
                ) {
                    // weight(1f), NOT `fullWidth = true`.
                    //
                    // A Row measures each non-weighted child against the FULL width
                    // available, and only then hands out the remainder to weighted
                    // children. `OrbitButton(fullWidth = true)` resolves to
                    // `fillMaxWidth()`, so each of these two claimed the whole panel
                    // and the "Video" button was pushed a full panel-width to the right
                    // and clipped off the edge -- a half-visible control with no way
                    // to reach it. Sharing the space evenly is also what the layout
                    // obviously intended.
                    OrbitButton(
                        text = "Voice",
                        icon = with(TablerIcons) { Phone },
                        onClick = { callManager?.startCall(callRoomId, false) },
                        tone = OrbitButtonTone.Neutral,
                        modifier = Modifier.weight(1f)
                    )
                    OrbitButton(
                        text = "Video",
                        icon = with(TablerIcons) { Video },
                        onClick = { callManager?.startCall(callRoomId, true) },
                        tone = OrbitButtonTone.Primary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 4.dp)
                ) {
                    item(key = "presence") {
                        ProfileSection(
                            title = "PRESENCE",
                            value = statusText(user.status),
                            leading = {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(memberStatusColor(user.status))
                                )
                            }
                        )
                    }
                    item(key = "mxid") {
                        ProfileSection(title = "MATRIX ID", value = user.id)
                    }
                }
                OrbitScrollbar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

/** The 72dp avatar inside `.popover-avatar-wrap`, with no presence dot. */
@Composable
private fun ProfileCardAvatar(user: OrbitUser) {
    Box(modifier = Modifier.fillMaxSize().clip(CircleShape)) {
        val avatarUrl = user.avatarUrl
        if (avatarUrl != null) {
            coil3.compose.AsyncImage(
                model = avatarUrl,
                contentDescription = user.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            OrbitMascotAvatar(
                seed = user.id,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * A `.popover-section-title` over a `.popover-about`.
 *
 * The concept draws those as two separate elements; in Compose one composable
 * with `spacedBy` between them is the same two boxes, and it keeps the 4px gap
 * from belonging to the caller's `Arrangement`.
 */
@Composable
private fun ProfileSection(
    title: String,
    value: String,
    leading: (@Composable () -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(6.dp))
            }
            Text(
                // .popover-section-title { font-size: 11px; font-weight: 700;
                //   color: var(--text-muted); text-transform: uppercase }
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = t.Text_Muted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                ),
                maxLines = 1
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            // .popover-about { font-size: 13px; color: var(--text-normal);
            //   line-height: 1.4 }
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                color = t.Text_Secondary,
                fontSize = 13.sp,
                lineHeight = 18.2.sp
            ),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A compact "4 by 2" profile card (~280dp x ~140dp) presenting user identity:
 * banner, avatar with status, display name, @username, presence status, Matrix ID,
 * and quick voice/video call action buttons.
 */
@Composable
internal fun CompactUserProfileCard(
    user: OrbitUser,
    callRoomId: String? = null,
    onVoiceCall: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val callManager = webrtc.LocalCallManager.current
    val verified = isOrbitVerifiedUser(user.username, user.displayName, user.id)
    val t = rememberCosmicTokens()
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .width(280.dp)
            .shadow(12.dp, shape)
            .clip(shape)
            .background(t.Elevated)
            .border(1.dp, t.Separator.copy(alpha = 0.5f), shape)
    ) {
        // ── Banner (height 44dp) + Quick call actions ─────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) {
            val bannerUrl = user.bannerUrl
            if (bannerUrl != null) {
                coil3.compose.AsyncImage(
                    model = bannerUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                listOf(t.Accent, t.Accent.copy(alpha = 0.75f))
                            )
                        )
                )
            }

            // Quick Call Actions on top-right of banner
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val effectiveVoiceCall: (() -> Unit)? = onVoiceCall ?: if (callRoomId != null) {
                    { callManager?.startCall(callRoomId, false); Unit }
                } else null
                val effectiveVideoCall: (() -> Unit)? = onVideoCall ?: if (callRoomId != null) {
                    { callManager?.startCall(callRoomId, true); Unit }
                } else null

                if (effectiveVoiceCall != null) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0x99000000))
                            .clickable(role = Role.Button, onClick = effectiveVoiceCall),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = with(TablerIcons) { Phone },
                            contentDescription = "Voice Call",
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                if (effectiveVideoCall != null) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(t.Accent)
                            .clickable(role = Role.Button, onClick = effectiveVideoCall),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = with(TablerIcons) { Video },
                            contentDescription = "Video Call",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                if (onClose != null) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0x80000000))
                            .clickable(role = Role.Button, onClick = onClose),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = with(TablerIcons) { X },
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }

        // ── Body with overlapping avatar and info ──────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Top row: Avatar on left, name & tag next to it
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Avatar offset upward by 16dp to punch through banner
                    Box(
                        modifier = Modifier
                            .offset(y = (-16).dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(t.Elevated)
                            .padding(2.dp)
                    ) {
                        SmallUserAvatar(user, size = 36.dp)
                    }

                    Spacer(Modifier.width(8.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .offset(y = (-4).dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = user.displayName,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    color = t.Text_White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.5.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (verified) {
                                OrbitVerifiedBadge(modifier = Modifier.size(13.dp))
                            }
                        }
                        Text(
                            text = "@${user.username.ifBlank { user.id }}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = t.Text_Muted,
                                fontSize = 11.5.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Hairline divider
                HorizontalDivider(
                    color = t.Separator.copy(alpha = 0.35f),
                    thickness = 0.8.dp,
                    modifier = Modifier.offset(y = (-8).dp)
                )

                // Bottom section: Presence & Matrix ID
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = (-4).dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    // Presence
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(memberStatusColor(user.status))
                        )
                        Text(
                            text = statusText(user.status),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = t.Text_Secondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            maxLines = 1
                        )
                    }

                    // Matrix ID
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "MXID:",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = t.Text_Muted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = user.id,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = t.Text_Secondary,
                                fontSize = 11.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Popup that displays [CompactUserProfileCard] positioned below an anchor.
 */
@Composable
internal fun CompactUserProfilePopup(
    user: OrbitUser,
    callRoomId: String? = null,
    onVoiceCall: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    onCardHoverStateChange: ((Boolean) -> Unit)? = null
) {
    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.roundToPx() }
    val marginPx = with(density) { 12.dp.roundToPx() }

    val positionProvider = remember(gapPx, marginPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val preferredX = anchorBounds.left
                val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
                val x = preferredX.coerceIn(marginPx, maxX)

                val preferredY = anchorBounds.bottom + gapPx
                val y = if (preferredY + popupContentSize.height > windowSize.height - marginPx) {
                    (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(marginPx)
                } else {
                    preferredY
                }
                return IntOffset(x, y)
            }
        }
    }

    val cardInteraction = remember { MutableInteractionSource() }
    val isCardHovered by cardInteraction.collectIsHoveredAsState()
    LaunchedEffect(isCardHovered) {
        onCardHoverStateChange?.invoke(isCardHovered)
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = false,
            clippingEnabled = false
        )
    ) {
        Box(modifier = Modifier.hoverable(cardInteraction)) {
            CompactUserProfileCard(
                user = user,
                callRoomId = callRoomId,
                onVoiceCall = onVoiceCall,
                onVideoCall = onVideoCall,
                onClose = onDismissRequest,
                modifier = modifier
            )
        }
    }
}

/**
 * Popup that displays [CompactUserProfileCard] positioned beside an anchor (e.g. for sidebar list rows).
 */
@Composable
internal fun CompactUserProfileBesidePopup(
    user: OrbitUser,
    callRoomId: String? = null,
    onVoiceCall: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    onCardHoverStateChange: ((Boolean) -> Unit)? = null
) {
    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.roundToPx() }
    val marginPx = with(density) { 12.dp.roundToPx() }

    val positionProvider = remember(gapPx, marginPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val fitsOnRight = anchorBounds.right + gapPx + popupContentSize.width <= windowSize.width - marginPx
                val preferredX = if (fitsOnRight) {
                    anchorBounds.right + gapPx
                } else {
                    anchorBounds.left - popupContentSize.width - gapPx
                }
                val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
                val x = preferredX.coerceIn(marginPx, maxX)

                val centeredY = anchorBounds.center.y - popupContentSize.height / 2
                val minY = marginPx
                val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(minY)
                val y = centeredY.coerceIn(minY, maxY)
                return IntOffset(x, y)
            }
        }
    }

    val cardInteraction = remember { MutableInteractionSource() }
    val isCardHovered by cardInteraction.collectIsHoveredAsState()
    LaunchedEffect(isCardHovered) {
        onCardHoverStateChange?.invoke(isCardHovered)
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = false,
            clippingEnabled = false
        )
    ) {
        Box(modifier = Modifier.hoverable(cardInteraction)) {
            CompactUserProfileCard(
                user = user,
                callRoomId = callRoomId,
                onVoiceCall = onVoiceCall,
                onVideoCall = onVideoCall,
                onClose = onDismissRequest,
                modifier = modifier
            )
        }
    }
}