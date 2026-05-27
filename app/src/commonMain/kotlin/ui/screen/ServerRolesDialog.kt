package ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import repository.OrbitRepository
import ui.model.OrbitPermission
import ui.model.OrbitPermissionCategory
import ui.model.OrbitRole
import ui.model.OrbitRoleDefaults
import ui.model.OrbitServer
import ui.model.OrbitServerRolesState
import ui.model.PermissionGrant
import ui.theme.rememberCosmicTokens

@Composable
fun ServerRolesDialog(
    repository: OrbitRepository,
    server: OrbitServer,
    onDismiss: () -> Unit
) {
    val tokens = rememberCosmicTokens()
    val scope = rememberCoroutineScope()
    val eventType = remember(server.id) { OrbitRoleDefaults.accountDataEventType(server.id) }
    val defaultState = remember(server.id) { OrbitRoleDefaults.defaultState(server.id) }
    val storedState by repository
        .observeAccountData(eventType, OrbitServerRolesState.serializer())
        .collectAsState(null)

    var roles by remember(server.id) { mutableStateOf(defaultState.roles) }
    var selectedRoleId by remember(server.id) { mutableStateOf(defaultState.roles.first().id) }
    var statusText by remember(server.id) { mutableStateOf("Server role presets loaded") }
    var isSaving by remember(server.id) { mutableStateOf(false) }

    LaunchedEffect(storedState, server.id) {
        val nextState = storedState?.takeIf { it.serverId == server.id && it.roles.isNotEmpty() } ?: defaultState
        roles = nextState.roles.sortedByDescending { it.rank }
        if (roles.none { it.id == selectedRoleId }) {
            selectedRoleId = roles.first().id
        }
    }

    val selectedRole = roles.firstOrNull { it.id == selectedRoleId } ?: roles.first()

    fun updateRole(updatedRole: OrbitRole) {
        roles = roles.map { role -> if (role.id == updatedRole.id) updatedRole else role }
            .sortedByDescending { it.rank }
        statusText = "Unsaved role changes"
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .widthIn(max = 980.dp)
                .heightIn(max = 720.dp),
            shape = RoundedCornerShape(8.dp),
            color = tokens.Bg,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                RoleDialogHeader(
                    serverName = server.name,
                    statusText = statusText,
                    onDismiss = onDismiss
                )

                Spacer(Modifier.height(16.dp))

                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (maxWidth < 700.dp) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RolePicker(
                                roles = roles,
                                selectedRoleId = selectedRoleId,
                                onSelectRole = { selectedRoleId = it },
                                modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp)
                            )
                            RolePermissionEditor(
                                role = selectedRole,
                                onRoleChange = ::updateRole,
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RolePicker(
                                roles = roles,
                                selectedRoleId = selectedRoleId,
                                onSelectRole = { selectedRoleId = it },
                                modifier = Modifier.width(230.dp).fillMaxHeight()
                            )
                            RolePermissionEditor(
                                role = selectedRole,
                                onRoleChange = ::updateRole,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = tokens.Separator.copy(alpha = 0.35f))
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Click any permission badge to cycle Yes, Limited, Optional, Senior only, and No.",
                        color = tokens.Text_Muted,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                roles = OrbitRoleDefaults.defaultRoles().sortedByDescending { it.rank }
                                selectedRoleId = roles.first().id
                                statusText = "Defaults restored locally"
                            }
                        ) {
                            Text("Reset")
                        }
                        Button(
                            enabled = !isSaving,
                            onClick = {
                                isSaving = true
                                scope.launch {
                                    val state = OrbitServerRolesState(serverId = server.id, roles = roles)
                                    repository
                                        .setAccountData(eventType, state, OrbitServerRolesState.serializer())
                                        .onSuccess { statusText = "Saved role presets" }
                                        .onFailure { statusText = "Save failed: ${it.message ?: "unknown error"}" }
                                    isSaving = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = tokens.Accent)
                        ) {
                            Text(if (isSaving) "Saving..." else "Save", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleDialogHeader(
    serverName: String,
    statusText: String,
    onDismiss: () -> Unit
) {
    val tokens = rememberCosmicTokens()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.AccentGhost),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Security, null, tint = tokens.AccentLight, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Roles for $serverName",
                    color = tokens.Text_White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = statusText,
                    color = tokens.Text_Muted,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Close roles", tint = tokens.Text_Muted)
        }
    }
}

@Composable
private fun RolePicker(
    roles: List<OrbitRole>,
    selectedRoleId: String,
    onSelectRole: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, tokens.Separator.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
            .background(tokens.CardBg)
            .padding(9.dp)
    ) {
        Text(
            text = "Role hierarchy",
            color = tokens.Text_White,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(roles, key = { it.id }) { role ->
                RoleListItem(
                    role = role,
                    selected = role.id == selectedRoleId,
                    onClick = { onSelectRole(role.id) }
                )
            }
        }
    }
}

@Composable
private fun RoleListItem(role: OrbitRole, selected: Boolean, onClick: () -> Unit) {
    val tokens = rememberCosmicTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color(role.color).copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(role.color))
        )
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = role.name,
                color = if (selected) Color(role.color) else tokens.Text_White,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${role.allowedCount}/${OrbitPermission.entries.size} allowed",
                color = tokens.Text_Muted,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
            )
        }
    }
}

@Composable
private fun RolePermissionEditor(
    role: OrbitRole,
    onRoleChange: (OrbitRole) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, tokens.Separator.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
            .background(tokens.CardBg)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(role.color).copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Tune, null, tint = Color(role.color), modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = role.name,
                    color = tokens.Text_White,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = role.description,
                    color = tokens.Text_Muted,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "#${role.rank}",
                color = Color(role.color),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
            )
        }

        Spacer(Modifier.height(14.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OrbitPermissionCategory.entries.forEach { category ->
                item("category-${category.name}") {
                    Text(
                        text = category.label.uppercase(),
                        color = tokens.AccentLight,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp
                        ),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                items(
                    OrbitPermission.entries.filter { it.category == category },
                    key = { permission -> "${role.id}-${permission.name}" }
                ) { permission ->
                    PermissionRow(
                        permission = permission,
                        grant = role.directGrantFor(permission),
                        onGrantClick = {
                            onRoleChange(role.withGrant(permission, role.directGrantFor(permission).next()))
                        }
                    )
                }
            }

            item("suggestions") {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Suggestions",
                    color = tokens.Text_White,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrbitRoleDefaults.suggestions.forEach { suggestion ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(tokens.Bg)
                                .padding(9.dp)
                        ) {
                            Column {
                                Text(
                                    text = suggestion.title,
                                    color = tokens.Text_White,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = suggestion.detail,
                                    color = tokens.Text_Muted,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    permission: OrbitPermission,
    grant: PermissionGrant,
    onGrantClick: () -> Unit
) {
    val tokens = rememberCosmicTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(tokens.Bg.copy(alpha = 0.82f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = permission.label,
                color = tokens.Text_White,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = permission.description,
                color = tokens.Text_Muted,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        PermissionGrantBadge(grant = grant, onClick = onGrantClick)
    }
}

@Composable
private fun PermissionGrantBadge(grant: PermissionGrant, onClick: () -> Unit) {
    val color = when (grant) {
        PermissionGrant.YES -> Color(0xFF3DDC84)
        PermissionGrant.NO -> Color(0xFF8E9297)
        PermissionGrant.LIMITED -> Color(0xFFF2C94C)
        PermissionGrant.SENIOR_ONLY -> Color(0xFFEB5757)
        PermissionGrant.OPTIONAL -> Color(0xFF56CCF2)
    }
    Box(
        modifier = Modifier
            .width(98.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(5.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = grant.label,
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
