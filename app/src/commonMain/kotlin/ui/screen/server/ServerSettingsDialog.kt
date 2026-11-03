package ui.screen.server

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import repository.OrbitRepository
import ui.components.OrbitNotices
import ui.icons.CameraPlus
import ui.icons.Check
import ui.icons.TablerIcons
import ui.icons.X
import ui.model.OrbitServer
import ui.screen.orbitGlobalShortcuts
import ui.theme.OrbitConceptDark
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.pastelServerColor
import ui.theme.rememberCosmicTokens

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ServerSettingsDialog(
    server: OrbitServer,
    repository: OrbitRepository?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val tokens = rememberCosmicTokens()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var serverName by remember(server.id) { mutableStateOf(server.name) }
    var currentAvatarUrl by remember(server.id) { mutableStateOf(server.avatarUrl) }
    var currentBannerUrl by remember(server.id) { mutableStateOf(server.bannerUrl) }

    var isUploadingAvatar by remember { mutableStateOf(false) }
    var isUploadingBanner by remember { mutableStateOf(false) }
    var isSavingName by remember { mutableStateOf(false) }

    val nameChanged = serverName.trim() != server.name && serverName.isNotBlank()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
            scrimColor = Color(0xCC000000)
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val isFullscreen = maxWidth >= 1350.dp
            val widthFraction = if (isFullscreen) 0.68f else 0.76f

            Surface(
                modifier = modifier
                    .orbitGlobalShortcuts()
                    .fillMaxWidth(widthFraction)
                    .fillMaxHeight(0.85f)
                    .heightIn(max = 780.dp)
                    .shadow(28.dp, RoundedCornerShape(16.dp))
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onClose()
                            true
                        } else false
                    },
                shape = RoundedCornerShape(16.dp),
                color = colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.6f))
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // ── 1. Left Tone: Sidebar Navigation (surfaceContainer) ──
                    Column(
                        modifier = Modifier
                            .width(220.dp)
                            .fillMaxHeight()
                            .background(colorScheme.surfaceContainer)
                            .padding(horizontal = 16.dp, vertical = 24.dp)
                    ) {
                        Text(
                            text = server.name.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = tokens.Text_Muted,
                                fontSize = 11.sp,
                                letterSpacing = 0.5.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )

                        Spacer(Modifier.height(12.dp))

                        // Overview Navigation Item (Active)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(OrbitConceptDark.brand.copy(alpha = 0.2f))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = "Overview",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            )
                        }
                    }

                    // Divider between tones
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(colorScheme.outlineVariant.copy(alpha = 0.35f))
                    )

                    // ── 2. Right Tone: Main Content (surfaceContainerHigh) ──
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(colorScheme.surfaceContainerHigh)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 36.dp, vertical = 28.dp)
                        ) {
                            Text(
                                text = "Server Overview",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = tokens.Text_White,
                                    fontSize = 20.sp
                                )
                            )
                            Text(
                                text = "Customize how your server looks to members and across Orbit.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = tokens.Text_Muted,
                                    fontSize = 13.sp
                                ),
                                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
                            )

                            // ── Section 1: Server Icon & Name Row ──
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Server Avatar Circle
                                Box(
                                    modifier = Modifier.size(80.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val displayAvatar = currentAvatarUrl ?: server.avatarUrl
                                    val pastel = remember(server.id) { pastelServerColor(server.id) }

                                    Box(
                                        modifier = Modifier
                                            .size(80.dp)
                                            .clip(CircleShape)
                                            .background(colorScheme.surfaceContainerHighest)
                                            .border(1.5.dp, colorScheme.outlineVariant, CircleShape)
                                            .clickable(enabled = !isUploadingAvatar) {
                                                scope.launch {
                                                    isUploadingAvatar = true
                                                    val bytes = util.pickImageFile()
                                                    if (bytes != null && repository != null) {
                                                        val result = repository.uploadSpaceAvatar(server.id, bytes)
                                                        result.onSuccess {
                                                            currentAvatarUrl = it
                                                            OrbitNotices.info("Server icon updated", "Settings")
                                                        }.onFailure { err ->
                                                            OrbitNotices.error("Failed to update icon: ${err.message}", "Settings")
                                                        }
                                                    }
                                                    isUploadingAvatar = false
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (displayAvatar != null) {
                                            AsyncImage(
                                                model = displayAvatar,
                                                contentDescription = server.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                                            )
                                        } else {
                                            Text(
                                                text = server.name.take(2).uppercase(),
                                                color = Color.White,
                                                fontSize = 24.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        // Camera overlay icon or loading spinner
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color(0x33000000)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isUploadingAvatar) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(24.dp),
                                                    color = Color.White,
                                                    strokeWidth = 2.5.dp
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = with(TablerIcons) { CameraPlus },
                                                    contentDescription = "Change icon",
                                                    tint = Color.White.copy(alpha = 0.85f),
                                                    modifier = Modifier.size(26.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                // Server Name Input & Save
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "SERVER NAME",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = tokens.Text_Muted,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        ),
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = serverName,
                                            onValueChange = { serverName = it },
                                            singleLine = true,
                                            placeholder = { Text("Server name", color = tokens.Text_Muted) },
                                            modifier = Modifier.weight(1f),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = OrbitConceptDark.brand,
                                                unfocusedBorderColor = colorScheme.outlineVariant.copy(alpha = 0.6f),
                                                focusedContainerColor = colorScheme.surfaceContainerHighest,
                                                unfocusedContainerColor = colorScheme.surfaceContainerHighest,
                                                focusedTextColor = tokens.Text_White,
                                                unfocusedTextColor = tokens.Text_White
                                            ),
                                            shape = OrbitRadius.smShape
                                        )

                                        if (nameChanged) {
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        isSavingName = true
                                                        val res = repository?.updateSpaceName(server.id, serverName.trim())
                                                        if (res?.isSuccess == true) {
                                                            OrbitNotices.info("Server name updated", "Settings")
                                                        } else {
                                                            OrbitNotices.error("Failed to update name: ${res?.exceptionOrNull()?.message}", "Settings")
                                                        }
                                                        isSavingName = false
                                                    }
                                                },
                                                enabled = !isSavingName && serverName.isNotBlank(),
                                                colors = ButtonDefaults.buttonColors(containerColor = OrbitConceptDark.brand),
                                                shape = OrbitRadius.smShape,
                                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                            ) {
                                                if (isSavingName) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(16.dp),
                                                        color = Color.White,
                                                        strokeWidth = 2.dp
                                                    )
                                                } else {
                                                    Text("Save", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(32.dp))

                            // ── Section 2: Server Banner ──
                            Text(
                                text = "SERVER BANNER",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = tokens.Text_Muted,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            )
                            Text(
                                text = "This banner appears at the top of your server's channel list. Recommended size: 960x540 (16:9).",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = tokens.Text_Muted,
                                    fontSize = 12.sp
                                ),
                                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                            )

                            val displayBanner = currentBannerUrl ?: server.bannerUrl

                            // Banner Card Preview Box
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colorScheme.surfaceContainerHighest)
                                    .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (displayBanner != null) {
                                    AsyncImage(
                                        model = displayBanner,
                                        contentDescription = "Server Banner",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    // Bottom gradient wash
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color.Transparent, Color(0x88000000))
                                                )
                                            )
                                    )
                                } else {
                                    // Dusk pagoda / cosmic placeholder gradient
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Brush.linearGradient(
                                                    listOf(
                                                        tokens.Accent.copy(alpha = 0.25f),
                                                        colorScheme.surfaceContainerHighest
                                                    )
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                imageVector = with(TablerIcons) { CameraPlus },
                                                contentDescription = null,
                                                tint = tokens.Text_Muted,
                                                modifier = Modifier.size(32.dp)
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = "No server banner uploaded",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    color = tokens.Text_Muted,
                                                    fontSize = 12.sp
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            // Banner Action Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isUploadingBanner = true
                                            val bytes = util.pickImageFile()
                                            if (bytes != null && repository != null) {
                                                val result = repository.uploadSpaceBanner(server.id, bytes)
                                                result.onSuccess {
                                                    currentBannerUrl = it
                                                    OrbitNotices.info("Server banner updated", "Settings")
                                                }.onFailure { err ->
                                                    OrbitNotices.error("Failed to upload banner: ${err.message}", "Settings")
                                                }
                                            }
                                            isUploadingBanner = false
                                        }
                                    },
                                    enabled = !isUploadingBanner,
                                    colors = ButtonDefaults.buttonColors(containerColor = OrbitConceptDark.brand),
                                    shape = OrbitRadius.smShape,
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    if (isUploadingBanner) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Uploading…", fontSize = 13.sp)
                                    } else {
                                        Icon(
                                            imageVector = with(TablerIcons) { CameraPlus },
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = if (displayBanner != null) "Change Banner" else "Upload Banner",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Close button (Top-Right "X" + "ESC")
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 20.dp, end = 24.dp)
                        ) {
                            val interaction = remember { MutableInteractionSource() }
                            val isHovered by interaction.collectIsHoveredAsState()
                            val closeBg by animateColorAsState(
                                if (isHovered) colorScheme.surfaceContainerHighest else Color.Transparent,
                                tween(120),
                                label = "closeBg"
                            )

                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(closeBg)
                                    .border(1.5.dp, colorScheme.outlineVariant, CircleShape)
                                    .hoverable(interaction)
                                    .clickable(
                                        interactionSource = interaction,
                                        indication = null,
                                        role = Role.Button,
                                        onClickLabel = "Close",
                                        onClick = onClose
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = with(TablerIcons) { X },
                                    contentDescription = "Close",
                                    tint = if (isHovered) Color.White else tokens.Text_Muted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "ESC",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.Text_Muted
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
