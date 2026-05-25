package ui.screen

import androidx.compose.foundation.background
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
import kotlinx.coroutines.launch
import repository.OrbitRepository
import ui.model.ChannelType
import ui.theme.rememberCosmicTokens

private enum class ServerDialogMode {
    Join,
    Create
}

@Composable
fun JoinServerScreen(
    repository: OrbitRepository,
    onClose: () -> Unit,
    onServerCreated: (String) -> Unit = {},
    onServerJoined: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()
    var mode by remember { mutableStateOf(ServerDialogMode.Join) }
    var input by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val isCreateMode = mode == ServerDialogMode.Create

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(400.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(tokens.Bg)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Add Server",
                color = tokens.Text_White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            TabRow(
                selectedTabIndex = if (isCreateMode) 1 else 0,
                containerColor = tokens.Bg,
                contentColor = tokens.Accent,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Tab(
                    selected = !isCreateMode,
                    onClick = {
                        mode = ServerDialogMode.Join
                        input = ""
                        errorMsg = null
                    },
                    enabled = !isWorking,
                    text = { Text("Join") }
                )
                Tab(
                    selected = isCreateMode,
                    onClick = {
                        mode = ServerDialogMode.Create
                        input = ""
                        errorMsg = null
                    },
                    enabled = !isWorking,
                    text = { Text("Create") }
                )
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it; errorMsg = null },
                label = { Text(if (isCreateMode) "Server name" else "Room ID or alias") },
                placeholder = {
                    Text(
                        if (isCreateMode) "My Server" else "#room:server.com",
                        color = tokens.Text_Muted
                    )
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = tokens.Accent,
                    unfocusedBorderColor = tokens.Separator,
                    focusedTextColor = tokens.Text_White,
                    unfocusedTextColor = tokens.Text_White,
                    cursorColor = tokens.Accent
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )

            if (errorMsg != null) {
                Text(
                    text = errorMsg!!,
                    color = Color.Red,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel", color = tokens.Text_Muted)
                }

                Button(
                    onClick = {
                        if (input.isBlank()) return@Button
                        isWorking = true
                        errorMsg = null
                        scope.launch {
                            val value = input.trim()
                            val result = if (isCreateMode) {
                                val spaceResult = repository.createSpace(value)
                                val spaceId = spaceResult.getOrNull()
                                if (spaceId != null) {
                                    val channelResult = repository.createChannel(spaceId, "general", ChannelType.TEXT)
                                    if (channelResult.isSuccess) {
                                        onServerCreated(spaceId)
                                        spaceResult
                                    } else {
                                        Result.failure(channelResult.exceptionOrNull() ?: Exception("Failed to create #general"))
                                    }
                                } else {
                                    spaceResult
                                }
                            } else {
                                repository.joinSpace(value).onSuccess { roomId ->
                                    onServerJoined(roomId)
                                }
                            }
                            if (result.isSuccess) {
                                onClose()
                            } else {
                                errorMsg = result.exceptionOrNull()?.message
                                    ?: if (isCreateMode) "Failed to create server" else "Failed to join"
                                isWorking = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.Accent),
                    enabled = !isWorking && input.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    if (isWorking) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(if (isCreateMode) "Create" else "Join", color = Color.White)
                    }
                }
            }
        }
    }
}
