package ui.screen.drawer

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.assets.OrbitEmptyIllustration
import ui.assets.OrbitEmptyScene
import ui.model.OrbitServer
import ui.theme.rememberCosmicTokens

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
internal fun DrawerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    accent: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bgColor by animateColorAsState(
        targetValue = if (isHovered) ChannelHover_Bg else Color.Transparent,
        label = "drawerActionBg"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            accent -> AccentLight
            isHovered -> Text_White
            else -> Text_Muted
        },
        label = "drawerActionColor"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = contentColor,
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
        )
    }
}

@Composable
internal fun InviteDialog(server: OrbitServer, onDismiss: () -> Unit) {
    val tokens = rememberCosmicTokens()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tokens.CardBg,
        title = {
            Text(
                "Invite friends to ${server.name}",
                style = MaterialTheme.typography.titleMedium.copy(color = Text_White, fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column {
                OrbitEmptyIllustration(
                    scene = OrbitEmptyScene.Invite,
                    modifier = Modifier.fillMaxWidth().height(126.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Invite links aren't supported yet. Share this server ID so others can find it manually.",
                    style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted, fontSize = 13.sp),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                OutlinedTextField(
                    value = server.id,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = tokens.Bg,
                        unfocusedContainerColor = tokens.Bg,
                        focusedTextColor = Text_White,
                        unfocusedTextColor = Text_White,
                        focusedBorderColor = Accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(server.id))
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text("Copy ID", color = Color.White)
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
            ) {
                Text("Close", color = Text_Muted)
            }
        }
    )
}
