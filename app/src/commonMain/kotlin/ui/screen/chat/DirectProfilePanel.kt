package ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import ui.components.OrbitListRow
import ui.components.OrbitRowLeading
import ui.components.OrbitScrollbar
import ui.model.OrbitUser
import ui.theme.OrbitMetrics
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

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

/**
 * Direct-message profile panel.
 *
 * This one needed the most work of anything in the UI layer. It opened on a
 * 150dp three-stop gradient running from the user's avatar colour through the
 * accent into a flat cyan — the single most neon surface in the app — under a
 * 22sp ExtraBold name. Below that sat four rows and a full-width button that
 * all did nothing: "Mutual Servers", "Mutual Friends", "Add Friend" and "View
 * Full Profile" were `clickable {}`.
 *
 * Dead affordances are worse than missing ones, so they are gone. What remains
 * is what the app actually knows: who this is, their presence, their MXID, and
 * the two call buttons that are genuinely wired.
 */
@Composable
internal fun DirectProfilePanel(user: OrbitUser) {
    val t = rememberCosmicTokens()
    val avatarColor = Color(user.avatarColor)
    val callManager = webrtc.LocalCallManager.current
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .width(OrbitMetrics.sidePanelWidthWide)
            .fillMaxHeight()
            .background(t.Members_Bg)
            .border(1.dp, t.Separator)
    ) {
        // ── Identity header ──────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(
                    // A single low-alpha wash of the user's own colour. The
                    // previous three-stop gradient was unreadable text over.
                    Brush.verticalGradient(
                        listOf(
                            avatarColor.copy(alpha = 0.28f),
                            avatarColor.copy(alpha = 0.06f),
                            androidx.compose.ui.graphics.Color.Transparent
                        )
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = OrbitSpace.lg)
                    .offset(y = 22.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(t.Members_Bg)
                        .padding(4.dp)
                ) {
                    SmallUserAvatar(user, size = 64.dp)
                }
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = OrbitSpace.lg),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = t.Text_White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        letterSpacing = 0.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isOrbitVerifiedUser(user.username, user.displayName, user.id)) {
                    Spacer(Modifier.width(5.dp))
                    OrbitVerifiedBadge(modifier = Modifier.size(15.dp))
                }
            }
            Text(
                text = "@${user.username.ifBlank { user.id }}",
                style = MaterialTheme.typography.bodySmall.copy(color = t.Text_Muted, fontSize = 12.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = user.id,
                style = MaterialTheme.typography.labelSmall.copy(color = t.Text_Subtle, fontSize = 10.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(OrbitSpace.lg))

        Row(
            modifier = Modifier.padding(horizontal = OrbitSpace.lg),
            horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
        ) {
            OrbitButton(
                text = "Voice",
                icon = Icons.Default.Call,
                onClick = { callManager?.startCall(user.id, false) },
                tone = OrbitButtonTone.Neutral,
                fullWidth = true
            )
            OrbitButton(
                text = "Video",
                icon = Icons.Default.Videocam,
                onClick = { callManager?.startCall(user.id, true) },
                tone = OrbitButtonTone.Primary,
                fullWidth = true
            )
        }

        Spacer(Modifier.height(OrbitSpace.lg))

        androidx.compose.material3.HorizontalDivider(color = t.Separator, thickness = 1.dp)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = OrbitSpace.sm,
                    bottom = OrbitSpace.xxl,
                    start = OrbitSpace.sm,
                    end = OrbitSpace.sm
                )
            ) {
                item(key = "presence") {
                    ProfileField(
                        label = "Presence",
                        value = statusText(user.status),
                        valueColor = memberStatusColor(user.status)
                    )
                }
                item(key = "mxid") {
                    ProfileField(label = "Matrix ID", value = user.id)
                }
            }
            OrbitScrollbar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun ProfileField(
    label: String,
    value: String,
    valueColor: Color? = null
) {
    val t = rememberCosmicTokens()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = t.Text_Subtle,
                fontWeight = FontWeight.SemiBold,
                fontSize = 9.5.sp,
                letterSpacing = 0.6.sp
            )
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                color = valueColor ?: t.Text_Secondary,
                fontSize = 12.5.sp
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
