package ui.screen.drawer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.components.OrbitButton
import ui.components.OrbitButtonTone
import ui.components.OrbitCallout
import ui.components.OrbitDialog
import ui.components.OrbitListRow
import ui.components.OrbitRowLeading
import ui.model.OrbitServer
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * A prominent action inside the channel drawer ("Invite People", "Roles").
 *
 * These used to be full-width buttons with a permanently coloured icon, which
 * put four equally-loud rows directly under the server name and flattened the
 * hierarchy of the whole drawer.
 */
@Composable
internal fun DrawerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    accent: Boolean,
    onClick: () -> Unit
) {
    val t = rememberCosmicTokens()
    OrbitListRow(
        title = text,
        onClick = onClick,
        height = 32.dp,
        horizontalPadding = OrbitSpace.sm,
        accentColor = if (accent) t.Accent else null,
        selected = false,
        showSelectionBar = false,
        leading = OrbitRowLeading(icon = icon)
    )
}

@Composable
internal fun InviteDialog(server: OrbitServer, onDismiss: () -> Unit) {
    val t = rememberCosmicTokens()
    val clipboard = LocalClipboardManager.current

    OrbitDialog(
        title = "Invite to ${server.name}",
        subtitle = "Share this server's ID so friends can find it.",
        icon = Icons.Default.Groups,
        onDismissRequest = onDismiss,
        confirmLabel = "Copy server ID",
        onConfirm = {
            clipboard.setText(AnnotatedString(server.id))
            onDismiss()
        },
        dismissLabel = "Close"
    ) {
        Column {
            OrbitCallout(
                text = "Orbit doesn't mint invite links yet. Anyone with the server ID can request to join.",
                tone = OrbitButtonTone.Neutral
            )
            Spacer(Modifier.height(OrbitSpace.md))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                verticalArrangement = Arrangement.Center
            ) {
                androidx.compose.material3.Text(
                    text = server.id,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                        color = t.Text_White,
                        fontSize = 13.sp
                    ),
                    maxLines = 1
                )
            }
        }
    }
}
