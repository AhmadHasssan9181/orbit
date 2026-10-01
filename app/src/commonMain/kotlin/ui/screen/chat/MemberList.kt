package ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.assets.OrbitDefaultAvatar
import ui.model.OrbitUser
import ui.model.UserStatus
import ui.theme.OrbitConceptMetrics
import ui.theme.rememberCosmicTokens

/** One heading and the members under it. */
data class MemberGroup(
    val label: String,
    val members: List<OrbitUser>
)

/**
 * What somebody is doing, as the concept's `member.activity` line renders it.
 *
 * The concept has a fixed set of three (`playing`, `listening`/`spotify`,
 * `coding`), each with its own 12px glyph in its own colour. Matrix has no
 * rich-presence equivalent, so this is opt-in data a caller supplies rather
 * than something inferred.
 */
sealed interface MemberActivity {
    val name: String

    /** `Gamepad2` at 12px in #23a55a. */
    data class Playing(override val name: String) : MemberActivity

    /** `Headphones` at 12px in #1db954. */
    data class Listening(override val name: String) : MemberActivity

    /** `Code` at 12px in #007acc. */
    data class Coding(override val name: String) : MemberActivity
}

/**
 * The concept's `.member-list-sidebar`.
 *
 * 240px wide, `--bg-sidebar`, 16px top and bottom with 8px at the sides, and 16px
 * between groups. The width is [OrbitConceptMetrics.memberListWidth] rather than
 * a literal, and hiding the panel at narrow widths is left to the host: the
 * concept does it with `@media (max-width: 900px) { display: none }`, which
 * Orbit's window-size classes already do.
 *
 * ## Where this is used
 *
 * The member list people actually see is [MemberListPanel], and it is NOT this
 * composable: that panel has to carry live presence, role tiers and a moderation
 * menu, so it groups and scrolls for itself. What it does borrow is the geometry
 * and the colour of everything on a row — [MemberAvatarWithStatus] and the
 * `.member-group-header` type scale — so the two cannot drift.
 *
 * This composable exists for a panel that does NOT need moderation: the drawer, a
 * profile popover's people list, a picker. It is the concept's component with the
 * concept's numbers, and it is deliberately the simpler of the two.
 */
@Composable
fun MemberList(
    groups: List<MemberGroup>,
    modifier: Modifier = Modifier,
    activities: Map<String, MemberActivity> = emptyMap(),
    customStatuses: Map<String, String> = emptyMap(),
    onSelectUser: ((OrbitUser) -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    LazyColumn(
        modifier = modifier
            .width(OrbitConceptMetrics.memberListWidth)
            .background(t.Members_Bg),
        contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp, start = 8.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        groups.forEach { group ->
            item(key = "group-${group.label}", contentType = "header") {
                // .member-group-header — 12px, weight 700, --text-muted, uppercase,
                // 0.02em tracking, 4px/8px of padding.
                Text(
                    text = group.label.uppercase(),
                    color = t.Text_Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.24.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            items(group.members, key = { it.id }, contentType = { "member" }) { user ->
                MemberListRow(
                    user = user,
                    activity = activities[user.id],
                    customStatus = customStatuses[user.id],
                    onClick = onSelectUser?.let { open -> { open(user) } }
                )
            }
        }
    }
}

/**
 * `.member-item-row` — 6px/8px of padding, 10px to the avatar, a 4px radius and
 * `--bg-modifier-hover` on hover.
 *
 * A row with nothing to open is not clickable at all, so it does not light up on
 * hover either: the hover fill is the affordance, and a row that offers a
 * profile while saying nothing under the cursor is the same lie told quieter.
 */
@Composable
fun MemberListRow(
    user: OrbitUser,
    modifier: Modifier = Modifier,
    activity: MemberActivity? = null,
    customStatus: String? = null,
    onClick: (() -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (onClick != null && hovered) t.Hover else Color.Transparent)
            .hoverable(enabled = onClick != null, interactionSource = interaction)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Open ${user.displayName}'s profile",
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MemberAvatarWithStatus(user = user, status = user.status, ringColor = t.Members_Bg)

        // .member-details — a column that clips, so a long name ellipsises
        // instead of pushing the row's edge out.
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = user.displayName,
                    color = t.Text_White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (user.isBot) {
                    // `.message-role-tag` at the concept's inline override for this
                    // one: 9px, 1px/3px, with the rule's own 3px radius,
                    // `--brand-blurple` fill and white uppercase label.
                    Text(
                        text = "BOT",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(t.Accent)
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                    )
                }
            }
            // `.member-activity-text` — 11px, --text-muted, clipped. An activity
            // line wins over a custom status, as in the concept.
            val subline = activity?.name ?: customStatus
            if (subline != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 1.dp)
                ) {
                    if (activity != null) ActivityGlyph(activity)
                    Text(
                        text = subline,
                        color = t.Text_Muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * `.member-avatar-wrap` — a 32x32 relative box holding a circular avatar and the
 * presence dot, which is 10x10 at bottom -2 / right -2 with a 2px ring.
 *
 * The ring colour is the surface the dot sits ON, not a constant: the concept
 * writes `var(--bg-secondary)` because the avatar it drew happened to be in the
 * user bar. A ring in the wrong surface is a ring you can see.
 */
@Composable
fun MemberAvatarWithStatus(
    user: OrbitUser,
    modifier: Modifier = Modifier,
    status: UserStatus = user.status,
    ringColor: Color? = null,
    size: Dp = 32.dp
) {
    val ring = ringColor ?: rememberCosmicTokens().Members_Bg
    Box(modifier = modifier.size(size)) {
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
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // `bottom: -2px; right: -2px` on a 10px dot with a 2px border.
                .offset(x = 2.dp, y = 2.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(ring)
                .padding(2.dp)
                .clip(CircleShape)
                .background(memberStatusColor(status))
        )
    }
}

/** The 12px glyph beside a `.member-activity-text`. */
@Composable
private fun ActivityGlyph(activity: MemberActivity) {
    val icon = when (activity) {
        is MemberActivity.Playing -> Icons.Filled.SportsEsports
        is MemberActivity.Listening -> Icons.Filled.Headphones
        is MemberActivity.Coding -> Icons.Filled.Code
    }
    // Literals from the concept's `renderActivityIcon`: #23a55a, #1db954, #007acc.
    val tint = when (activity) {
        is MemberActivity.Playing -> Color(0xFF23A55A)
        is MemberActivity.Listening -> Color(0xFF1DB954)
        is MemberActivity.Coding -> Color(0xFF007ACC)
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(12.dp)
    )
}
