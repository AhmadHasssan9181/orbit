package ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import ui.model.OrbitUser

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
internal fun SmallUserAvatar(user: OrbitUser, size: Dp) {
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
                .size(if (size > 40.dp) 14.dp else 10.dp)
                .align(Alignment.BottomEnd)
                .border(2.dp, Members_Bg, CircleShape)
                .clip(CircleShape)
                .background(memberStatusColor(user.status))
        )
    }
}

@Composable
internal fun DirectProfilePanel(user: OrbitUser) {
    Column(
        modifier = Modifier
            .width(320.dp)
            .fillMaxHeight()
            .background(Members_Bg)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(user.avatarColor).copy(alpha = 0.95f),
                            Accent.copy(alpha = 0.85f),
                            Color(0xFF36D7D7)
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProfileCircleAction(Icons.Default.PersonAdd, "Add Friend")
                ProfileCircleAction(Icons.Default.MoreHoriz, "More")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
        ) {
            Box(modifier = Modifier.height(54.dp).fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .offset(y = (-44).dp)
                        .size(88.dp)
                        .border(7.dp, Members_Bg, CircleShape)
                ) {
                    SmallUserAvatar(user, size = 88.dp)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = Text_White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 22.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isOrbitVerifiedUser(user.username, user.displayName, user.id)) {
                    Spacer(Modifier.width(6.dp))
                    OrbitVerifiedBadge(modifier = Modifier.size(17.dp))
                }
            }

            Text(
                text = user.username.ifBlank { user.id },
                style = MaterialTheme.typography.bodyMedium.copy(color = Text_Secondary, fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(18.dp))
            ProfileInfoCard("Status", statusText(user.status))
            Spacer(Modifier.height(10.dp))
            ProfileInfoCard("Member Since", "Matrix direct message")
            Spacer(Modifier.height(10.dp))
            ProfileLinkRow("Mutual Servers")
            ProfileLinkRow("Mutual Friends")

            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {},
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) {
                Text("View Full Profile", color = Text_White, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ProfileCircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable {},
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ProfileInfoCard(title: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CardBg)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(title, color = Text_Secondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(value, color = Text_White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProfileLinkRow(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CardBg)
            .clickable {}
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Text_Secondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Text_Muted, modifier = Modifier.size(20.dp))
    }
}
