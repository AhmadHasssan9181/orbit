package ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.assets.OrbitDefaultAvatar
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
            OrbitDefaultAvatar(
                label = user.displayName,
                seed = user.id,
                color = Color(user.avatarColor),
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
            .background(OrbitConceptDark.floating)
            // border: 1px solid var(--border-subtle)
            .border(1.dp, OrbitConceptBorder.subtle(), ProfileCardShape)
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
                    .background(OrbitConceptDark.brand)
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
                    .background(OrbitConceptDark.floating)
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
                        // `Icons.Default.Close` is in material-icons-extended,
                        // which this project depends on; the "not in this
                        // project's icon set" note that used to live here was
                        // about material-icons-core and no longer applies.
                        imageVector = Icons.Default.Close,
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
                        color = OrbitConceptDark.textPrimary,
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
                        color = OrbitConceptDark.textMuted,
                        fontSize = 13.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // .popover-divider { height: 1px; background-color: var(--border-subtle) }
            HorizontalDivider(color = OrbitConceptBorder.subtle(), thickness = 1.dp)

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
                        icon = Icons.Default.Call,
                        onClick = { callManager?.startCall(callRoomId, false) },
                        tone = OrbitButtonTone.Neutral,
                        modifier = Modifier.weight(1f)
                    )
                    OrbitButton(
                        text = "Video",
                        icon = Icons.Default.Videocam,
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
            OrbitDefaultAvatar(
                label = user.displayName,
                seed = user.id,
                color = Color(user.avatarColor),
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
                    color = OrbitConceptDark.textMuted,
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
                color = OrbitConceptDark.textNormal,
                fontSize = 13.sp,
                lineHeight = 18.2.sp
            ),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}