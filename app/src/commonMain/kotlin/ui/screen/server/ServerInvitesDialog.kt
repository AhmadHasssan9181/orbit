package ui.screen.server

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import repository.OrbitRepository
import ui.components.OrbitButton
import ui.components.OrbitButtonTone
import ui.components.OrbitCallout
import ui.components.OrbitDialog
import ui.components.OrbitAvatarImage
import ui.icons.TablerIcons
import ui.icons.Bell
import ui.model.ServerInvite
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

@Composable
fun ServerInvitesDialog(
    repository: OrbitRepository?,
    onDismiss: () -> Unit,
    onInviteAccepted: (spaceId: String) -> Unit = {}
) {
    val t = rememberCosmicTokens()
    val scope = rememberCoroutineScope()
    var invites by remember { mutableStateOf<List<ServerInvite>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var actionInProgressId by remember { mutableStateOf<String?>(null) }
    var noticeMessage by remember { mutableStateOf<String?>(null) }

    fun refreshInvites() {
        scope.launch {
            isLoading = true
            invites = repository?.getIncomingInvites() ?: emptyList()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshInvites()
    }

    OrbitDialog(
        title = "Server Invitations",
        subtitle = "Invitations to private servers and spaces.",
        icon = with(TablerIcons) { Bell },
        onDismissRequest = onDismiss,
        confirmLabel = "Close",
        onConfirm = onDismiss,
        dismissLabel = null,
        width = 460.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(OrbitSpace.md)
        ) {
            if (noticeMessage != null) {
                OrbitCallout(
                    text = noticeMessage!!,
                    tone = OrbitButtonTone.Primary
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = t.Accent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else if (invites.isEmpty()) {
                OrbitCallout(
                    text = "No pending server invitations. When you receive an invitation to join a private server, it will appear here.",
                    tone = OrbitButtonTone.Neutral
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height((invites.size * 68).coerceAtMost(280).dp),
                    verticalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
                ) {
                    items(invites, key = { it.spaceId }) { invite ->
                        val isCurrentAction = actionInProgressId == invite.spaceId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(OrbitRadius.smShape)
                                .background(t.Elevated)
                                .border(1.dp, t.Separator, OrbitRadius.smShape)
                                .padding(OrbitSpace.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!invite.avatarUrl.isNullOrBlank()) {
                                OrbitAvatarImage(
                                    url = invite.avatarUrl,
                                    contentDescription = invite.name,
                                    modifier = Modifier.size(36.dp).clip(CircleShape)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(t.Accent, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = invite.name.take(1).uppercase(),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }

                            Spacer(Modifier.width(OrbitSpace.md))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = invite.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = t.Text_White,
                                        fontSize = 13.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = invite.spaceId,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = t.Text_Subtle,
                                        fontSize = 10.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(Modifier.width(OrbitSpace.sm))

                            // Decline Button
                            OrbitButton(
                                text = "Decline",
                                tone = OrbitButtonTone.Neutral,
                                enabled = !isCurrentAction,
                                onClick = {
                                    actionInProgressId = invite.spaceId
                                    scope.launch {
                                        repository?.declineInvite(invite.spaceId)
                                        noticeMessage = "Declined invitation to ${invite.name}"
                                        refreshInvites()
                                        actionInProgressId = null
                                    }
                                }
                            )

                            Spacer(Modifier.width(OrbitSpace.xs))

                            // Accept Button
                            OrbitButton(
                                text = if (isCurrentAction) "Joining..." else "Accept",
                                tone = OrbitButtonTone.Primary,
                                enabled = !isCurrentAction,
                                onClick = {
                                    actionInProgressId = invite.spaceId
                                    scope.launch {
                                        val res = repository?.acceptInvite(invite.spaceId)
                                        actionInProgressId = null
                                        if (res?.isSuccess == true) {
                                            noticeMessage = "Joined ${invite.name}!"
                                            onInviteAccepted(invite.spaceId)
                                            refreshInvites()
                                        } else {
                                            noticeMessage = res?.exceptionOrNull()?.message ?: "Failed to join server"
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
