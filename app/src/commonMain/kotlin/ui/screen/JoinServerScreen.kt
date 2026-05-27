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
import ui.assets.OrbitEmptyIllustration
import ui.assets.OrbitEmptyScene
import ui.model.ChannelType
import ui.model.OrbitServer
import ui.theme.rememberCosmicTokens

private enum class ServerDialogMode {
    Join,
    Create
}

private val DiscordRoomNameRegex = Regex("^[a-z]+(?:-[a-z]+)*$")

private fun normalizeRoomNameInput(value: String): String {
    val lowered = value.lowercase().replace(Regex("\\s+"), "-")
    return buildString {
        lowered.forEach { ch ->
            if (ch in 'a'..'z' || ch == '-') append(ch)
        }
    }.replace(Regex("-{2,}"), "-")
}

private fun validateRoomName(value: String): String? {
    return when {
        value.isBlank() -> "Room name is required"
        !DiscordRoomNameRegex.matches(value) -> "Use letters and hyphens only, like general-chat"
        else -> null
    }
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
            OrbitEmptyIllustration(
                scene = if (isCreateMode) OrbitEmptyScene.CreateServer else OrbitEmptyScene.Servers,
                modifier = Modifier.width(220.dp).height(136.dp)
            )
            Spacer(Modifier.height(8.dp))

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

@Composable
fun CreateRoomDialog(
    repository: OrbitRepository,
    server: OrbitServer,
    onClose: () -> Unit,
    onRoomCreated: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()
    var input by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val validationMsg = remember(input) {
        if (input.isBlank()) null else validateRoomName(input)
    }

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
            OrbitEmptyIllustration(
                scene = OrbitEmptyScene.CreateRoom,
                modifier = Modifier.width(210.dp).height(130.dp)
            )
            Spacer(Modifier.height(8.dp))

            Text(
                text = "Create Room",
                color = tokens.Text_White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = server.name,
                color = tokens.Text_Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = normalizeRoomNameInput(it)
                    errorMsg = null
                },
                label = { Text("Room name") },
                placeholder = {
                    Text("general-chat", color = tokens.Text_Muted)
                },
                singleLine = true,
                isError = validationMsg != null || errorMsg != null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = tokens.Accent,
                    unfocusedBorderColor = tokens.Separator,
                    errorBorderColor = Color(0xFFF87171),
                    focusedTextColor = tokens.Text_White,
                    unfocusedTextColor = tokens.Text_White,
                    cursorColor = tokens.Accent
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = validationMsg ?: errorMsg ?: "Only letters and hyphens are allowed.",
                color = if (validationMsg != null || errorMsg != null) Color(0xFFF87171) else tokens.Text_Muted,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    onClick = onClose,
                    enabled = !isWorking,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel", color = tokens.Text_Muted)
                }

                Button(
                    onClick = {
                        val value = input.trim()
                        val validation = validateRoomName(value)
                        if (validation != null) {
                            errorMsg = validation
                            return@Button
                        }

                        isWorking = true
                        errorMsg = null
                        scope.launch {
                            repository.createChannel(
                                spaceId = server.id,
                                name = value,
                                type = ChannelType.TEXT
                            ).onSuccess { channelId ->
                                onRoomCreated(channelId)
                                onClose()
                            }.onFailure { error ->
                                errorMsg = error.message ?: "Failed to create room"
                                isWorking = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.Accent),
                    enabled = !isWorking && input.isNotBlank() && validationMsg == null,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isWorking) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Create", color = Color.White)
                    }
                }
            }
        }
    }
}
