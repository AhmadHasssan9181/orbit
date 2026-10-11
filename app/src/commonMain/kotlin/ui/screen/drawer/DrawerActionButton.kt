package ui.screen.drawer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.TablerIcons
import ui.icons.Users
import ui.components.OrbitButton
import ui.components.OrbitButtonTone
import ui.components.OrbitCallout
import ui.components.OrbitDialog
import ui.components.OrbitListRow
import ui.components.OrbitRowLeading
import ui.model.OrbitServer
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens
// `DrawerActionButton` used to live in this file: a prominent full-width row for
// "Invite People" / "Roles & permissions" / "Create a room", drawn straight under
// the server name. It went unused when those three were moved out of the drawer --
// Invite now hangs off the server name and Create off the last category header,
// matching Discord -- which is the change that gave the sidebar its room list
// back.
//
// `InviteDialog` below IS still live (`ChannelDrawer` calls it); only the row
// helper was dead.

@Composable
internal fun InviteDialog(server: OrbitServer, onDismiss: () -> Unit) {
    val t = rememberCosmicTokens()
    val clipboard = LocalClipboardManager.current

    OrbitDialog(
        title = "Invite to ${server.name}",
        subtitle = "Share this server's ID so friends can find it.",
        icon = with(TablerIcons) { Users },
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
