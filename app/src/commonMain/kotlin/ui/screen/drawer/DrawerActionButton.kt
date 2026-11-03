package ui.screen.drawer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ui.icons.TablerIcons
import ui.icons.Users
import ui.icons.Logout
import ui.icons.Trash
import ui.components.OrbitButton
import ui.components.OrbitButtonTone
import ui.components.OrbitCallout
import ui.components.OrbitDialog
import ui.components.OrbitNotices
import ui.model.OrbitServer
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

@Composable
internal fun InviteDialog(
    server: OrbitServer,
    repository: repository.OrbitRepository? = null,
    onDismiss: () -> Unit
) {
    val t = rememberCosmicTokens()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var inviteInput by remember { mutableStateOf("") }
    var isInviting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(false) }
    var isPublic by remember { mutableStateOf<Boolean?>(null) }
    var userSuggestions by remember { mutableStateOf<List<ui.model.OrbitUser>>(emptyList()) }

    LaunchedEffect(server.id) {
        isPublic = repository?.isSpacePublic(server.id)
    }

    LaunchedEffect(inviteInput) {
        val q = inviteInput.trim()
        if (q.length >= 2) {
            userSuggestions = repository?.searchUsers(q, limit = 5) ?: emptyList()
        } else {
            userSuggestions = emptyList()
        }
    }

    val effectiveHandle = remember(server.canonicalAlias, server.name) {
        server.canonicalAlias ?: run {
            val withoutQuotes = server.name.lowercase().trim().filter { it != '\'' && it != '"' && it != '’' && it != '`' }
            val slug = withoutQuotes.replace(Regex("[^a-z0-9_.-]+"), "-").replace(Regex("-+"), "-").trim('-', '.')
            if (slug.isNotBlank()) "#$slug" else server.name
        }
    }

    OrbitDialog(
        title = "Invite to ${server.name}",
        subtitle = if (isPublic == true) "Share this server's handle or invite directly." else "Private server. Invite members directly by username or Matrix ID.",
        icon = with(TablerIcons) { Users },
        onDismissRequest = onDismiss,
        confirmLabel = if (isPublic == true) "Copy join handle" else "Close",
        onConfirm = {
            if (isPublic == true) {
                clipboard.setText(AnnotatedString(effectiveHandle))
                OrbitNotices.info("Copied '$effectiveHandle'")
            }
            onDismiss()
        },
        dismissLabel = if (isPublic == true) "Close" else null
    ) {
        Column {
            if (isPublic == true) {
                OrbitCallout(
                    text = "This server is Public. Anyone can join by searching '$effectiveHandle' or '${server.name}' in 'Join a Server'.",
                    tone = OrbitButtonTone.Neutral
                )
                Spacer(Modifier.height(OrbitSpace.md))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(ui.theme.OrbitRadius.smShape)
                        .background(t.Elevated)
                        .padding(horizontal = OrbitSpace.md),
                    verticalArrangement = Arrangement.Center
                ) {
                    androidx.compose.material3.Text(
                        text = "${server.name} ($effectiveHandle)",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                            color = t.Text_White,
                            fontSize = 13.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        ),
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(OrbitSpace.md))
            } else if (isPublic == false) {
                OrbitCallout(
                    text = "This server is Private. There is no open join link. Only people directly invited by you can join.",
                    tone = OrbitButtonTone.Primary
                )
                Spacer(Modifier.height(OrbitSpace.md))
            }

            androidx.compose.material3.Text(
                text = "INVITE BY MATRIX ID OR USERNAME",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                    color = t.Text_Subtle,
                    fontSize = 11.sp
                )
            )
            Spacer(Modifier.height(OrbitSpace.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = inviteInput,
                    onValueChange = { inviteInput = it; statusMessage = null },
                    placeholder = { androidx.compose.material3.Text("wajji or @wajji:matrix.org", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).height(48.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                Spacer(Modifier.width(OrbitSpace.sm))
                OrbitButton(
                    text = if (isInviting) "Inviting..." else "Invite",
                    enabled = !isInviting && inviteInput.isNotBlank(),
                    onClick = {
                        isInviting = true
                        statusMessage = null
                        scope.launch {
                            val targetId = inviteInput.trim()
                            val res = repository?.inviteUserToSpace(server.id, targetId)
                            isInviting = false
                            if (res?.isSuccess == true) {
                                isSuccess = true
                                statusMessage = "Invited $targetId!"
                                inviteInput = ""
                                userSuggestions = emptyList()
                            } else {
                                isSuccess = false
                                statusMessage = res?.exceptionOrNull()?.message ?: "Invite failed"
                            }
                        }
                    }
                )
            }

            if (userSuggestions.isNotEmpty()) {
                Spacer(Modifier.height(OrbitSpace.xs))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ui.theme.OrbitRadius.smShape)
                        .background(t.Elevated)
                        .border(1.dp, t.Separator, ui.theme.OrbitRadius.smShape)
                        .padding(4.dp)
                ) {
                    for (user in userSuggestions) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ui.theme.OrbitRadius.xsShape)
                                .clickable {
                                    inviteInput = user.id
                                    userSuggestions = emptyList()
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(t.Accent, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.material3.Text(
                                    text = user.displayName.take(1).uppercase(),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column {
                                androidx.compose.material3.Text(
                                    text = user.displayName,
                                    color = t.Text_White,
                                    fontSize = 12.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                )
                                androidx.compose.material3.Text(
                                    text = user.id,
                                    color = t.Text_Subtle,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }

            if (statusMessage != null) {
                Spacer(Modifier.height(OrbitSpace.xs))
                androidx.compose.material3.Text(
                    text = statusMessage!!,
                    color = if (isSuccess) t.Success else t.Danger,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
internal fun LeaveServerDialog(
    server: OrbitServer,
    onDismiss: () -> Unit,
    onConfirmLeave: () -> Unit
) {
    var isLeaving by remember { mutableStateOf(false) }

    OrbitDialog(
        title = "Leave '${server.name}'",
        subtitle = "Are you sure you want to leave ${server.name}? You won't be able to rejoin unless you are re-invited.",
        icon = with(TablerIcons) { Logout },
        onDismissRequest = { if (!isLeaving) onDismiss() },
        confirmLabel = if (isLeaving) "Leaving..." else "Leave Server",
        confirmTone = OrbitButtonTone.Danger,
        confirmEnabled = !isLeaving,
        onConfirm = {
            isLeaving = true
            onConfirmLeave()
        },
        dismissLabel = "Cancel"
    ) {
        Column {
            OrbitCallout(
                text = "You will lose access to all channels and messages in this server.",
                tone = OrbitButtonTone.Danger
            )
        }
    }
}

@Composable
internal fun DeleteServerDialog(
    server: OrbitServer,
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    var isDeleting by remember { mutableStateOf(false) }

    OrbitDialog(
        title = "Delete '${server.name}'",
        subtitle = "Are you sure you want to delete ${server.name}? This action cannot be undone.",
        icon = with(TablerIcons) { Trash },
        onDismissRequest = { if (!isDeleting) onDismiss() },
        confirmLabel = if (isDeleting) "Deleting..." else "Delete Server",
        confirmTone = OrbitButtonTone.Danger,
        confirmEnabled = !isDeleting,
        onConfirm = {
            isDeleting = true
            onConfirmDelete()
        },
        dismissLabel = "Cancel"
    ) {
        Column {
            OrbitCallout(
                text = "This will permanently remove the server, disconnect channels, and remove all members.",
                tone = OrbitButtonTone.Danger
            )
        }
    }
}
