package ui.screen.server

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import repository.OrbitRepository
import ui.assets.OrbitEmptyIllustration
import ui.assets.OrbitEmptyScene
import ui.components.OrbitTextField
import ui.model.ChannelType
import ui.model.OrbitServer
import ui.screen.orbitGlobalShortcuts
import ui.theme.rememberCosmicTokens

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

private enum class ServerDialogMode {
    Join,
    Create
}

private val DiscordRoomNameRegex = Regex("^[a-z]+(?:-[a-z]+)*$")

/** Hoisted: both were recompiled on every keystroke in the room-name field. */
private val WhitespaceRunRegex = Regex("\\s+")
private val RepeatedHyphenRegex = Regex("-{2,}")

private fun normalizeRoomNameInput(value: String): String {
    val lowered = value.lowercase().replace(WhitespaceRunRegex, "-")
    return buildString {
        lowered.forEach { ch ->
            if (ch in 'a'..'z' || ch == '-') append(ch)
        }
    }.replace(RepeatedHyphenRegex, "-")
}

private fun validateRoomName(value: String): String? {
    return when {
        value.isBlank() -> "Room name is required"
        !DiscordRoomNameRegex.matches(value) -> "Use letters and hyphens only, like general-chat"
        else -> null
    }
}

@Composable
private fun ChannelTypeChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) tokens.Accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) tokens.Text_White else tokens.Text_Muted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
        )
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

    // A real Dialog window, not a Box with a scrim: the overlay version took no
    // keyboard focus (keystrokes reached the composer behind it), ignored Esc and
    // ignored clicks outside, and so could not be dismissed at all.
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Column(
            modifier = modifier
                .width(430.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(tokens.Bg)
                .orbitGlobalShortcuts()
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

            // The "Custom / Template" and "Invite / Discover" cards had no
            // clickable modifier at all and no action behind them, so they were
            // removed rather than wired to a lie. The single text field above is
            // the only real flow.

            OrbitTextField(
                value = input,
                onValueChange = { input = it; errorMsg = null },
                placeholder = if (isCreateMode) "My Server" else "#room:server.com",
                singleLine = true,
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
    var channelType by remember { mutableStateOf(ChannelType.TEXT) }
    var isWorking by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val validationMsg = remember(input) {
        if (input.isBlank()) null else validateRoomName(input)
    }

    // Real Dialog window, matching JoinServerScreen: focus capture, Esc and
    // scrim dismissal, instead of an inert Box overlay.
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Column(
            modifier = modifier
                .width(400.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(tokens.Bg)
                .orbitGlobalShortcuts()
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
                modifier = Modifier.padding(bottom = 14.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.CardBg)
                    .border(1.dp, tokens.Separator.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                ChannelTypeChoice(
                    label = "Text",
                    selected = channelType == ChannelType.TEXT,
                    onClick = { channelType = ChannelType.TEXT },
                    modifier = Modifier.weight(1f)
                )
                ChannelTypeChoice(
                    label = "Voice",
                    selected = channelType == ChannelType.VOICE,
                    onClick = { channelType = ChannelType.VOICE },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(14.dp))

            OrbitTextField(
                value = input,
                onValueChange = {
                    input = normalizeRoomNameInput(it)
                    errorMsg = null
                },
                placeholder = if (channelType == ChannelType.VOICE) "general" else "general-chat",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = validationMsg ?: errorMsg ?: if (channelType == ChannelType.VOICE) {
                    "Voice channels appear in the separate voice section."
                } else {
                    "Only letters and hyphens are allowed."
                },
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
                                type = channelType,
                                topic = if (channelType == ChannelType.VOICE) "Voice channel" else ""
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
                        Text(if (channelType == ChannelType.VOICE) "Create Voice" else "Create Text", color = Color.White)
                    }
                }
            }
        }
    }
}
