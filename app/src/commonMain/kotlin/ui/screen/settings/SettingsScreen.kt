package ui.screen.settings

import ui.components.OrbitAvatarImage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.TablerIcons
import ui.icons.Accessible
import ui.icons.Bell
import ui.icons.CameraPlus
import ui.icons.Check
import ui.icons.ChevronRight
import ui.icons.Copy
import ui.icons.DeviceDesktop
import ui.icons.Fingerprint
import ui.icons.Lock
import ui.icons.Logout
import ui.icons.Mail
import ui.icons.Message
import ui.icons.MoodSmile
import ui.icons.Palette
import ui.icons.Pencil
import ui.icons.Shield
import ui.icons.User
import ui.icons.Video
import ui.icons.X
import kotlinx.coroutines.launch
import repository.OrbitRepository
import repository.SettingsManager
import ui.components.OrbitListRow
import ui.components.OrbitNotices
import ui.components.OrbitNotices.onFailureOrNotify
import ui.components.OrbitRowLeading
import ui.components.OrbitTextField
import ui.icons.OrbitIcons
import ui.model.OrbitProfileExtras
import ui.model.OrbitUser
import ui.screen.auth.*
import ui.screen.call.*
import ui.screen.drawer.*
import ui.screen.main.*
import ui.screen.media.*
import ui.screen.server.*
import ui.screen.settings.*
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens
import androidx.compose.foundation.hoverable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import ui.assets.OrbitMascotAvatar
import ui.theme.OrbitMotion
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onPreviewKeyEvent
// - Cosmic Design Tokens

enum class SettingsCategory(val title: String, val group: String) {
    // User Settings
    MyAccount("My Account", "USER SETTINGS"),
    Profiles("Profiles", "USER SETTINGS"),

    // App Settings
    VoiceVideo("Voice & Video", "APP SETTINGS"),
    Appearance("Appearance", "APP SETTINGS"),
    Notifications("Notifications", "APP SETTINGS"),
    Chat("Chat", "APP SETTINGS"),
    Accessibility("Accessibility", "APP SETTINGS"),
    Windows("Windows Settings", "APP SETTINGS"),

    // Auth
    Logout("Log Out", "")
}

@Composable
/**
 * Settings shell with Stoat / Discord-style off-centered layout.
 *
 * The sidebar is right-aligned in the left region, placing it close beside
 * the content column rather than pinned to the far-left screen border.
 * The ESC close button stays pinned at the top right of the content area.
 */
fun SettingsScreen(
    repository: OrbitRepository,
    settingsManager: SettingsManager,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf(SettingsCategory.MyAccount) }
    val t = rememberCosmicTokens()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    var currentUser by remember { mutableStateOf<OrbitUser?>(null) }
    LaunchedEffect(Unit) {
        currentUser = repository.getCurrentUser()
        focusRequester.requestFocus()
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(t.Drawer_Bg)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onBack()
                    true
                } else false
            }
    ) {
        // - Left Region: Sidebar aligned to TopEnd (pushed towards center!)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.TopEnd
        ) {
            Column(
                modifier = Modifier
                    .width(236.dp)
                    .fillMaxHeight()
                    .padding(top = 56.dp, start = 12.dp, end = 20.dp, bottom = 32.dp)
            ) {
                // Featured "My Account" user tile at top
                SettingsUserAccountTile(
                    user = currentUser,
                    selected = selectedCategory == SettingsCategory.MyAccount,
                    onClick = { selectedCategory = SettingsCategory.MyAccount }
                )

                Spacer(Modifier.height(14.dp))

                // Sidebar Navigation List
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        val categoriesByGroup = SettingsCategory.entries
                            .filter { it != SettingsCategory.MyAccount }
                            .groupBy { it.group }

                        categoriesByGroup.forEach { (group, cats) ->
                            if (group.isNotEmpty()) {
                                item(key = "group_$group") {
                                    Text(
                                        text = group,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = t.Text_Subtle,
                                            letterSpacing = 0.8.sp,
                                            fontSize = 9.5.sp
                                        ),
                                        modifier = Modifier.padding(
                                            start = 10.dp,
                                            top = OrbitSpace.md,
                                            bottom = OrbitSpace.xs
                                        )
                                    )
                                }
                            }
                            items(cats, key = { it.name }) { cat ->
                                if (cat == SettingsCategory.Logout) {
                                    HorizontalDivider(
                                        color = t.Separator.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = OrbitSpace.sm)
                                    )
                                }
                                val isSelected = cat == selectedCategory
                                val isDanger = cat == SettingsCategory.Logout
                                OrbitListRow(
                                    title = cat.title,
                                    onClick = {
                                        if (isDanger) onLogout() else selectedCategory = cat
                                    },
                                    selected = isSelected,
                                    height = 34.dp,
                                    showSelectionBar = false,
                                    leading = OrbitRowLeading(
                                        icon = settingsIconFor(cat)
                                    ),
                                    titleStyle = when {
                                        isDanger -> MaterialTheme.typography.bodyMedium.copy(color = t.Danger, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        isSelected -> MaterialTheme.typography.bodyMedium.copy(color = t.Text_White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        else -> MaterialTheme.typography.bodyMedium.copy(color = t.Text_Muted, fontSize = 13.sp)
                                    }
                                )
                            }
                        }

                        item(key = "footer") {
                            Spacer(Modifier.height(OrbitSpace.lg))
                            Column(modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 12.dp)) {
                                Text(
                                    text = "Orbit for Desktop",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = t.Text_Subtle,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp
                                    )
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "v0.1.0 • Stable Release",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = t.Text_Muted.copy(alpha = 0.6f),
                                        fontSize = 10.sp
                                    )
                                )
                            }
                        }
                    }
                    ui.components.OrbitScrollbar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd))
                }
            }
        }

        // - Right Region: Content panel container with distinct background + ESC button
        Box(
            modifier = Modifier
                .weight(1.5f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
                .background(t.Sunken)
                .border(
                    width = 1.dp,
                    color = t.Separator.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
                ),
            contentAlignment = Alignment.TopStart
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 48.dp, start = 36.dp, end = 32.dp, bottom = 32.dp)
            ) {
                // Scrollable content column
                Column(
                    modifier = Modifier
                        .widthIn(min = 400.dp, max = 680.dp)
                        .weight(1f, fill = false)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(end = 28.dp)
                ) {
                    if (selectedCategory != SettingsCategory.MyAccount) {
                        Text(
                            text = selectedCategory.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = t.Text_White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            modifier = Modifier.padding(bottom = OrbitSpace.lg)
                        )
                    }

                    when (selectedCategory) {
                        SettingsCategory.MyAccount -> {
                            MyAccountPane(
                                repository = repository,
                                onLogout = onLogout
                            )
                        }
                        SettingsCategory.Profiles -> settingsItem { ProfilesPane(repository) }

                        SettingsCategory.Appearance -> {
                            settingsItem { ThemeSelectorCard(settingsManager) }
                            settingsItem { FontSizeCard(settingsManager) }
                            settingsItem { AppearanceExtrasPane(settingsManager) }
                        }
                        SettingsCategory.Accessibility -> {
                            settingsItem { AccessibilityExtrasPane(settingsManager) }
                        }
                        SettingsCategory.Notifications -> {
                            settingsItem { NotificationsPane(settingsManager) }
                        }
                        SettingsCategory.Chat -> {
                            settingsItem { ChatSettingsPane(settingsManager) }
                        }
                        SettingsCategory.VoiceVideo -> {
                            settingsItem { VoiceVideoFullPane(settingsManager) }
                        }
                        SettingsCategory.Windows -> settingsItem { WindowsSettingsPane(settingsManager) }

                        SettingsCategory.Logout -> {}
                    }
                }

                // Close Button fixed at top right of the content area
                EscCloseButton(
                    onClick = onBack,
                    modifier = Modifier.padding(top = 4.dp, end = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsUserAccountTile(
    user: OrbitUser?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val bg by animateColorAsState(
        targetValue = when {
            selected -> t.ChannelSelected_Bg
            hovered -> t.ChannelHover_Bg
            else -> Color.Transparent
        },
        animationSpec = tween(120),
        label = "userTileBg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .then(
                if (selected) Modifier.border(1.dp, t.Separator.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                else Modifier
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(t.CardBg)
        ) {
            if (!user?.avatarUrl.isNullOrBlank()) {
                OrbitAvatarImage(
                    url = user?.avatarUrl!!,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            } else {
                OrbitMascotAvatar(
                    seed = user?.id ?: "me",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user?.displayName ?: "Rumi",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = t.Text_White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "My Account",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = if (selected) t.Text_White.copy(alpha = 0.85f) else t.Text_Muted,
                    fontSize = 11.sp
                )
            )
        }
    }
}

@Composable
private fun EscCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val borderColor by animateColorAsState(
        targetValue = if (hovered) t.Text_White else t.Text_Muted.copy(alpha = 0.45f),
        animationSpec = OrbitMotion.SnappyColor,
        label = "escBorder"
    )
    val iconColor by animateColorAsState(
        targetValue = if (hovered) t.Text_White else t.Text_Muted,
        animationSpec = OrbitMotion.SnappyColor,
        label = "escIcon"
    )
    val bgColor by animateColorAsState(
        targetValue = if (hovered) t.CardBgHover else t.CardBg.copy(alpha = 0.5f),
        animationSpec = OrbitMotion.SnappyColor,
        label = "escBg"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(bgColor)
                .border(1.5.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = with(TablerIcons) { X },
                contentDescription = "Close (Esc)",
                tint = iconColor,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = "ESC",
            style = MaterialTheme.typography.labelSmall.copy(
                color = iconColor,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp
            )
        )
    }
}

@Composable
private fun ColumnScope.settingsItem(content: @Composable () -> Unit) {
    Box(modifier = Modifier.padding(bottom = OrbitSpace.md)) { content() }
}

private fun settingsIconFor(cat: SettingsCategory): androidx.compose.ui.graphics.vector.ImageVector = when (cat) {
    SettingsCategory.MyAccount -> with(TablerIcons) { User }
    SettingsCategory.Profiles -> with(TablerIcons) { MoodSmile }
    SettingsCategory.Appearance -> with(TablerIcons) { Palette }
    SettingsCategory.Accessibility -> with(TablerIcons) { Accessible }
    SettingsCategory.Notifications -> with(TablerIcons) { Bell }
    SettingsCategory.VoiceVideo -> with(TablerIcons) { Video }
    SettingsCategory.Chat -> with(TablerIcons) { Message }
    SettingsCategory.Windows -> with(TablerIcons) { DeviceDesktop }
    SettingsCategory.Logout -> with(TablerIcons) { Logout }
}

// ── My Account Pane (Stoat / Discord Styled) ─────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyAccountPane(
    repository: OrbitRepository,
    onLogout: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val t = rememberCosmicTokens()
    val clipboard = LocalClipboardManager.current
    var user by remember { mutableStateOf<OrbitUser?>(null) }
    var isEditingName by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var showVerifyDialog by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var avatarUploading by remember { mutableStateOf(false) }
    var bannerUploading by remember { mutableStateOf(false) }

    val profileExtras by repository
        .observeAccountData("im.orbit.profile_extras", OrbitProfileExtras.serializer())
        .collectAsState(null)
    val displayBannerUrl = user?.bannerUrl ?: profileExtras?.bannerUrl

    LaunchedEffect(Unit) {
        user = repository.getCurrentUser()
        newName = user?.displayName ?: ""
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── 1. Top Profile Banner Card ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(t.CardBg)
                .border(1.dp, t.Separator.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Top Banner Graphic (Sunset Pagoda dusk gradient fallback)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(115.dp)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF241432),
                                    Color(0xFF4C1D45),
                                    Color(0xFF8B3654),
                                    Color(0xFFBA544E),
                                    Color(0xFFE4885E)
                                )
                            )
                        )
                ) {
                    val bannerUrl = displayBannerUrl
                    if (!bannerUrl.isNullOrBlank()) {
                        OrbitAvatarImage(
                            url = bannerUrl,
                            contentDescription = "Profile Banner",
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Edit Profile / Change Banner Pencil Button in top-right of banner
                    val bannerBtnInteraction = remember { MutableInteractionSource() }
                    val bannerBtnHovered by bannerBtnInteraction.collectIsHoveredAsState()

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (bannerBtnHovered) Color(0x99000000) else Color(0x66000000))
                            .border(1.dp, Color(0x33FFFFFF), CircleShape)
                            .hoverable(bannerBtnInteraction)
                            .clickable(
                                interactionSource = bannerBtnInteraction,
                                indication = null,
                                enabled = !bannerUploading
                            ) {
                                coroutineScope.launch {
                                    bannerUploading = true
                                    val bytes = util.pickImageFile()
                                    if (bytes != null) {
                                        repository.uploadBanner(bytes)
                                            .onFailureOrNotify("Banner upload", "Settings")
                                            .onSuccess { uploadedUrl ->
                                                val extras = (profileExtras ?: OrbitProfileExtras()).copy(bannerUrl = uploadedUrl)
                                                repository.setAccountData("im.orbit.profile_extras", extras, OrbitProfileExtras.serializer())
                                                user = user?.copy(bannerUrl = uploadedUrl)
                                                OrbitNotices.info("Banner updated", "Settings")
                                            }
                                    }
                                    bannerUploading = false
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (bannerUploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = with(TablerIcons) { Pencil },
                                contentDescription = "Change Banner",
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }

                // Profile Info Row overlapping the banner
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Avatar cutout overlapping the banner
                    val avatarInteraction = remember { MutableInteractionSource() }
                    val isAvatarHovered by avatarInteraction.collectIsHoveredAsState()
                    val avatarOverlayAlpha by animateFloatAsState(
                        targetValue = if (isAvatarHovered || avatarUploading) 1f else 0f,
                        animationSpec = tween(150)
                    )

                    Box(
                        modifier = Modifier
                            .offset(y = (-32).dp)
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(t.CardBg)
                            .border(3.5.dp, t.CardBg, CircleShape)
                            .hoverable(avatarInteraction)
                            .clickable(
                                interactionSource = avatarInteraction,
                                indication = null,
                                enabled = !avatarUploading
                            ) {
                                coroutineScope.launch {
                                    avatarUploading = true
                                    val bytes = util.pickImageFile()
                                    if (bytes != null) {
                                        repository.uploadAvatar(bytes)
                                            .onFailureOrNotify("Avatar upload", "Settings")
                                            .onSuccess {
                                                user = repository.getCurrentUser()
                                                OrbitNotices.info("Avatar updated", "Settings")
                                            }
                                    }
                                    avatarUploading = false
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val avatarUrl = user?.avatarUrl
                        if (!avatarUrl.isNullOrBlank()) {
                            OrbitAvatarImage(
                                url = avatarUrl,
                                contentDescription = "Avatar",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else {
                            OrbitMascotAvatar(
                                seed = user?.id ?: "me",
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Dim sheet overlay on hover or while uploading
                        if (avatarOverlayAlpha > 0.01f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .graphicsLayer { alpha = avatarOverlayAlpha }
                                    .background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (avatarUploading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = with(TablerIcons) { CameraPlus },
                                            contentDescription = "Upload Avatar",
                                            tint = Color.White.copy(alpha = 0.95f),
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = "CHANGE",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = Color.White.copy(alpha = 0.95f),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 9.sp,
                                                letterSpacing = 0.5.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    // User name and handle
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 4.dp)
                    ) {
                        Text(
                            text = user?.displayName?.ifBlank { "Orbit User" } ?: "Orbit User",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = t.Text_White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = user?.id ?: "@unknown:orbit.chat",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = t.Text_Muted,
                                fontSize = 12.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Edit Profile button
                    TextButton(
                        onClick = {
                            newName = user?.displayName ?: ""
                            isEditingName = true
                        },
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = t.Sunken,
                            contentColor = t.Text_White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Edit Profile",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        )
                    }
                }
            }
        }

        // ── 2. Settings Detail Cards ──
        // Card 1: Username
        SettingsDetailCardRow(
            icon = with(TablerIcons) { Mail },
            title = "Username",
            value = user?.displayName ?: "Set display name",
            onClick = {
                newName = user?.displayName ?: ""
                isEditingName = true
            }
        )

        // Card 2: Matrix User ID
        SettingsDetailCardRow(
            icon = with(TablerIcons) { Fingerprint },
            title = "Matrix User ID",
            value = user?.id ?: "@unknown:orbit.chat",
            actionLabel = "Copy",
            trailingIcon = with(TablerIcons) { Copy },
            onClick = {
                val id = user?.id
                if (!id.isNullOrBlank()) {
                    clipboard.setText(AnnotatedString(id))
                    OrbitNotices.info("Copied Matrix ID to clipboard", "Account")
                }
            }
        )

        // Card 3: Password & Session
        SettingsDetailCardRow(
            icon = with(TablerIcons) { Lock },
            title = "Password & Session",
            value = "•••••••••••••••• (Active E2EE Session)",
            onClick = null
        )

        // Card 4: Device Verification
        SettingsDetailCardRow(
            icon = with(TablerIcons) { Shield },
            title = "Device Verification",
            value = "Configure cross-signing trust & verify sessions with SAS emojis",
            actionLabel = "Verify",
            trailingIcon = with(TablerIcons) { ChevronRight },
            onClick = { showVerifyDialog = true }
        )

        // Card 5: Log Out
        SettingsDetailCardRow(
            icon = with(TablerIcons) { Logout },
            iconTint = t.Danger,
            title = "Log Out of Orbit",
            value = "Sign out of your session on this computer",
            valueColor = t.Danger.copy(alpha = 0.85f),
            titleColor = t.Danger,
            trailingIcon = with(TablerIcons) { ChevronRight },
            onClick = { showLogoutConfirm = true }
        )
    }

    // ── Edit Display Name Dialog ──
    if (isEditingName) {
        var saveBusy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!saveBusy) isEditingName = false },
            title = {
                Text(
                    text = "Edit Display Name",
                    style = MaterialTheme.typography.titleMedium.copy(color = t.Text_White, fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column {
                    Text(
                        text = "Enter a new public display name for your profile.",
                        style = MaterialTheme.typography.bodySmall.copy(color = t.Text_Muted)
                    )
                    Spacer(Modifier.height(14.dp))
                    OrbitTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = "Display name",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        saveBusy = true
                        coroutineScope.launch {
                            repository.updateDisplayName(newName)
                                .onFailureOrNotify("Display name", "Settings")
                                .onSuccess {
                                    user = repository.getCurrentUser()
                                    isEditingName = false
                                    OrbitNotices.info("Display name updated", "Settings")
                                }
                            saveBusy = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = t.Accent),
                    enabled = !saveBusy && newName.isNotBlank()
                ) {
                    Text("Save", color = t.OnAccent)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { isEditingName = false },
                    enabled = !saveBusy
                ) {
                    Text("Cancel", color = t.Text_Muted)
                }
            },
            containerColor = t.CardBg,
            shape = RoundedCornerShape(14.dp)
        )
    }

    // ── Device Verification Dialog ──
    if (showVerifyDialog) {
        DeviceVerificationDialog(
            repository = repository,
            onDismiss = { showVerifyDialog = false }
        )
    }

    // ── Logout Confirmation Dialog ──
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = {
                Text(
                    text = "Log out of Orbit?",
                    style = MaterialTheme.typography.titleMedium.copy(color = t.Text_White, fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    text = "You will be returned to the login screen. Your local encryption keys and credentials remain secure.",
                    style = MaterialTheme.typography.bodySmall.copy(color = t.Text_Muted)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutConfirm = false
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = t.Danger)
                ) {
                    Text("Log Out", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("Cancel", color = t.Text_Muted)
                }
            },
            containerColor = t.CardBg,
            shape = RoundedCornerShape(14.dp)
        )
    }
}

@Composable
private fun SettingsDetailCardRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color? = null,
    title: String,
    titleColor: Color? = null,
    value: String,
    valueColor: Color? = null,
    actionLabel: String? = null,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector? = with(TablerIcons) { ChevronRight },
    onClick: (() -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val cardBg by animateColorAsState(
        targetValue = if (hovered && onClick != null) t.CardBgHover else t.CardBg,
        animationSpec = tween(120),
        label = "cardRowBg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(1.dp, t.Separator.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .then(
                if (onClick != null) {
                    Modifier
                        .hoverable(interaction)
                        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Leading Icon badge in circular Sunken background
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(t.Sunken),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint ?: t.Text_Secondary,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        // Center Column: Title & Subtitle/Value
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = titleColor ?: t.Text_White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp
                )
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = valueColor ?: t.Text_Muted,
                    fontSize = 12.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Trailing elements (Action label or chevron)
        if (actionLabel != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = t.Accent,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.5.sp
                ),
                modifier = Modifier.padding(end = 6.dp)
            )
        }

        if (trailingIcon != null && onClick != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = titleColor ?: t.Text_Subtle,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun DeviceVerificationDialog(
    repository: OrbitRepository,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val t = rememberCosmicTokens()
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                text = "Verify another device",
                style = MaterialTheme.typography.titleMedium.copy(color = t.Text_White, fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column {
                Text(
                    text = "Enter the Matrix ID of the other device — the same user, so your own ID. You will be shown emojis to compare.",
                    color = t.Text_Muted,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                OrbitTextField(
                    value = draft,
                    onValueChange = { draft = it; problem = null },
                    placeholder = "@you:matrix.org",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = t.Danger, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            repository.startDeviceVerification(draft)
                                .onSuccess { onDismiss() }
                                .onFailure { problem = it.message ?: "Could not start verification" }
                            busy = false
                        }
                    },
                    enabled = !busy && draft.isNotBlank()
                ) { Text("Start", color = t.Accent) }
                TextButton(onClick = onDismiss, enabled = !busy) {
                    Text("Cancel", color = t.Text_Muted)
                }
            }
        },
        containerColor = t.CardBg,
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable private fun FontSizeCard(settingsManager: SettingsManager) {
    val fontSize by settingsManager.fontSize.collectAsState(50f)
    val tokens = rememberCosmicTokens()
    
    PrefCard("Font Size", "Adjust the size of message text and interface elements", vertical = true) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${(fontSize / 50f * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = tokens.Accent,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp
                    )
                )
                Spacer(Modifier.width(16.dp))
                Slider(
                    value = fontSize,
                    onValueChange = { settingsManager.setFontSize(it) },
                    valueRange = 30f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor        = tokens.Accent,
                        activeTrackColor  = tokens.Accent,
                        inactiveTrackColor = tokens.Text_Muted.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Preview area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.Bg)
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        "Preview Text", 
                        style = MaterialTheme.typography.labelSmall.copy(color = tokens.Text_Muted, fontWeight = FontWeight.Bold)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This is how your messages will look. Drag the slider to find your comfort zone.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = tokens.Text_White)
                    )
                }
            }
        }
    }
}

@Composable private fun ReduceMotionCard(settingsManager: SettingsManager) {
    val on by settingsManager.reduceMotion.collectAsState(false)
    PrefCard("Reduce Motion", "Minimize animations for accessibility") {
        CosmicSwitch(checked = on, onCheckedChange = { settingsManager.setReduceMotion(it) })
    }
}

/** Human label for a theme, from the enum name so new values need no wiring. */
private fun themeLabel(theme: ui.theme.Theme): String = when (theme) {
    ui.theme.Theme.M3Dynamic -> "Material You"
    else -> theme.name.replaceFirstChar { it.uppercase() }
}

@Composable private fun ThemeSelectorCard(settingsManager: SettingsManager) {
    val onThemeChange = ui.theme.LocalOnThemeChange.current
    val currentSeedColor = ui.theme.LocalThemeSeedColor.current
    val dynamicIsDark = ui.theme.LocalDynamicThemeIsDark.current
    val tokens = rememberCosmicTokens()

    PrefCard("App Theme", "Choose between Dark and Light mode, and pick an adaptive color palette", vertical = true) {
        // Mode Toggle: Dark vs Light
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Mode",
                    color = tokens.Text_White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (dynamicIsDark) "Dark mode with rich adaptive tonal shades" else "Light mode with soft adaptive tonal shades",
                    color = tokens.Text_Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            ChipGroup(
                options = listOf("Dark", "Light"),
                selected = if (dynamicIsDark) "Dark" else "Light",
                onSelect = { mode ->
                    val isDark = (mode == "Dark")
                    settingsManager.setDynamicThemeIsDark(isDark)
                    settingsManager.setTheme(ui.theme.Theme.M3Dynamic)
                    onThemeChange(ui.theme.Theme.M3Dynamic)
                }
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = tokens.Separator)
        Spacer(Modifier.height(12.dp))

        // Palette Colors: Always visible in both Dark and Light modes!
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Palette Color",
                color = tokens.Text_White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "The entire interface adapts to shades of your chosen color",
                color = tokens.Text_Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ui.theme.MaterialYouSeeds.forEach { seed ->
                    val isSelected = currentSeedColor.value == seed.color.value
                    val interactionSource = remember { MutableInteractionSource() }
                    val isHovered by interactionSource.collectIsHoveredAsState()

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                settingsManager.setThemeSeedColor(seed.color.value.toLong())
                                settingsManager.setTheme(ui.theme.Theme.M3Dynamic)
                                onThemeChange(ui.theme.Theme.M3Dynamic)
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(seed.color)
                                .border(
                                    width = if (isSelected) 3.dp else if (isHovered) 2.dp else 1.dp,
                                    color = if (isSelected) tokens.Text_White else if (isHovered) tokens.Text_Secondary else Color.Transparent,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = with(TablerIcons) { Check },
                                    contentDescription = "Selected",
                                    tint = if (seed.color.luminance() > 0.4f) Color.Black else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = seed.name,
                            color = if (isSelected) tokens.Text_White else tokens.Text_Muted,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

