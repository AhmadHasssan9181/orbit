package ui.screen.server

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import repository.OrbitRepository
import ui.assets.OrbitServerAssetIcon
import ui.components.OrbitEmptyState
import ui.components.orbitClickable
import ui.components.rememberOrbitInteraction
import ui.icons.CircleCheck
import ui.icons.Code
import ui.icons.Compass
import ui.icons.DeviceGamepad
import ui.icons.FileMusic
import ui.icons.Search
import ui.icons.Stars
import ui.icons.TablerIcons
import ui.icons.Users
import ui.icons.X
import ui.screen.orbitGlobalShortcuts
import ui.theme.rememberCosmicTokens

/**
 * Representation of a discoverable public community in the Matrix ecosystem.
 */
data class DiscoverServer(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val tags: List<String>,
    val memberCount: String,
    val onlineCount: String,
    val avatarUrl: String? = null,
    val isVerified: Boolean = true,
    val rawMemberCount: Int = 0
)

private val DiscoverCategories = listOf("All", "Official", "Gaming", "Tech", "Community", "Music", "Creative", "Science")

private val PopularDiscoverServers = listOf(
    DiscoverServer(
        id = "#orbit:matrix.org",
        name = "Orbit Community",
        description = "Official hub for Orbit. Real-time MatrixRTC voice calls, stage channels, feedback and feature hangouts.",
        category = "Official",
        tags = listOf("orbit", "voice", "calls", "matrix", "official", "chat"),
        memberCount = "24.5k",
        onlineCount = "3.4k",
        avatarUrl = "https://images.unsplash.com/photo-1614728894747-a83421e2b9c9?w=200&h=200&fit=crop"
    ),
    DiscoverServer(
        id = "#matrix:matrix.org",
        name = "Matrix HQ",
        description = "The flagship public room for the open Matrix protocol. Discussion about Matrix servers, clients, and federation.",
        category = "Community",
        tags = listOf("matrix", "federation", "open-source", "chat", "protocol"),
        memberCount = "112.4k",
        onlineCount = "14.8k",
        avatarUrl = "https://avatars.githubusercontent.com/u/1018260?s=200&v=4"
    ),
    DiscoverServer(
        id = "#gaming:matrix.org",
        name = "Gaming & Esports Lounge",
        description = "Multiplayer matchmaking, game nights, clip sharing, voice squads for Steam, RPGs and competitive FPS.",
        category = "Gaming",
        tags = listOf("gaming", "esports", "steam", "fps", "rpg", "voice", "games"),
        memberCount = "38.4k",
        onlineCount = "5.1k",
        avatarUrl = "https://images.unsplash.com/photo-1542751371-adc38448a05e?w=200&h=200&fit=crop"
    ),
    DiscoverServer(
        id = "#kotlin:matrix.org",
        name = "Kotlin Developers",
        description = "Multiplatform apps, Compose Desktop & Android, Coroutines, server-side Kotlin, and architecture discussions.",
        category = "Tech",
        tags = listOf("kotlin", "kmp", "compose", "android", "programming", "code", "tech"),
        memberCount = "18.9k",
        onlineCount = "2.3k",
        avatarUrl = "https://avatars.githubusercontent.com/u/1446536?s=200&v=4"
    ),
    DiscoverServer(
        id = "#linux:matrix.org",
        name = "Linux & FOSS Hub",
        description = "Desktop customization, kernel news, distro hopping, self-hosting homeservers, and open-source freedom.",
        category = "Tech",
        tags = listOf("linux", "foss", "arch", "debian", "self-hosting", "open-source", "tech"),
        memberCount = "45.2k",
        onlineCount = "6.7k",
        avatarUrl = "https://avatars.githubusercontent.com/u/1024025?s=200&v=4"
    ),
    DiscoverServer(
        id = "#gamedev:matrix.org",
        name = "Indie Game Devs",
        description = "Showcase devlogs, discuss shaders, game engines (Godot, Unreal, Unity), 3D art, and game jam playtesting.",
        category = "Creative",
        tags = listOf("gamedev", "godot", "unity", "unreal", "pixelart", "3d", "creative"),
        memberCount = "15.3k",
        onlineCount = "1.9k",
        avatarUrl = "https://images.unsplash.com/photo-1550745165-9bc0b252726f?w=200&h=200&fit=crop"
    ),
    DiscoverServer(
        id = "#music-production:matrix.org",
        name = "Music & Audio Lab",
        description = "Sound design, DAW workflows (Ableton, FL, Bitwig), synth patches, mixing feedback, and live listening parties.",
        category = "Music",
        tags = listOf("music", "audio", "daw", "synth", "beats", "production"),
        memberCount = "9.8k",
        onlineCount = "1.2k",
        avatarUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=200&h=200&fit=crop"
    ),
    DiscoverServer(
        id = "#anime:matrix.org",
        name = "Anime & Manga Club",
        description = "Seasonal anime episode discussions, manga recommendations, cosplay showcase, fan art, and voice watch-alongs.",
        category = "Community",
        tags = listOf("anime", "manga", "art", "japan", "shows", "community"),
        memberCount = "22.1k",
        onlineCount = "3.1k",
        avatarUrl = "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=200&h=200&fit=crop"
    ),
    DiscoverServer(
        id = "#security:matrix.org",
        name = "Cyber & Infosec",
        description = "End-to-end encryption, Megolm protocol, privacy rights, reverse engineering, CTF challenges and security audits.",
        category = "Tech",
        tags = listOf("security", "privacy", "crypto", "infosec", "encryption", "tech"),
        memberCount = "16.8k",
        onlineCount = "2.0k",
        avatarUrl = "https://images.unsplash.com/photo-1526374965328-7f61d4dc18c5?w=200&h=200&fit=crop"
    ),
    DiscoverServer(
        id = "#science:matrix.org",
        name = "Cosmos & Science",
        description = "Astrophysics, James Webb Space Telescope discoveries, aerospace engineering, physics debates, and breakthroughs.",
        category = "Science",
        tags = listOf("science", "space", "astronomy", "physics", "cosmos"),
        memberCount = "11.6k",
        onlineCount = "1.4k",
        avatarUrl = "https://images.unsplash.com/photo-1451187580459-43490279c0fa?w=200&h=200&fit=crop"
    )
)

/**
 * Discover Public Communities Dialog.
 *
 * Implements Phase 1 of Discover Servers adhering strictly to Material You dynamic theming.
 * Uses matte pastel surfaces from the active Material You ColorScheme (surfaceContainerHigh
 * for the base card, surfaceContainer for the nested cards, and outlineVariant for borders).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ExploreServersDialog(
    repository: OrbitRepository,
    onClose: () -> Unit,
    onOpenServer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val tokens = rememberCosmicTokens()
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var joiningTargetId by remember { mutableStateOf<String?>(null) }
    var joinError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    val trimmed = query.trim()
    val looksLikeTarget = trimmed.contains(":") || trimmed.contains("matrix.to") || (trimmed.startsWith("#") && trimmed.length > 2)

    var publishedServers by remember { mutableStateOf<List<DiscoverServer>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(trimmed) {
        if (trimmed.isEmpty()) {
            publishedServers = repository.getPublicServers("")
        } else {
            kotlinx.coroutines.delay(250)
            isSearching = true
            try {
                publishedServers = repository.getPublicServers(trimmed)
            } finally {
                isSearching = false
            }
        }
    }

    val spaces by remember(repository) { repository.observeSpaces() }
        .collectAsState(emptyList())

    val filteredServers = remember(trimmed, selectedCategory, spaces, publishedServers) {
        val joinedMatches = if (trimmed.isNotEmpty()) {
            spaces.filter { !it.isDM }.filter { space ->
                space.name.contains(trimmed, ignoreCase = true) ||
                    space.description.contains(trimmed, ignoreCase = true) ||
                    space.id.contains(trimmed, ignoreCase = true)
            }.map { space ->
                val inferredCat = when {
                    space.description.contains("[Gaming]", ignoreCase = true) || space.name.contains("game", ignoreCase = true) || space.name.contains("gaming", ignoreCase = true) -> "Gaming"
                    space.description.contains("[Tech]", ignoreCase = true) || space.name.contains("tech", ignoreCase = true) || space.name.contains("code", ignoreCase = true) || space.name.contains("dev", ignoreCase = true) -> "Tech"
                    space.description.contains("[Music]", ignoreCase = true) || space.name.contains("music", ignoreCase = true) -> "Music"
                    space.description.contains("[Creative]", ignoreCase = true) || space.name.contains("art", ignoreCase = true) -> "Creative"
                    space.description.contains("[Science]", ignoreCase = true) || space.name.contains("science", ignoreCase = true) -> "Science"
                    else -> "Community"
                }
                DiscoverServer(
                    id = space.id,
                    name = space.name,
                    description = space.description.ifBlank { "Community space" },
                    category = inferredCat,
                    tags = listOf(space.name.lowercase(), "joined", "community", inferredCat.lowercase()),
                    memberCount = "Joined",
                    onlineCount = "Active",
                    avatarUrl = space.avatarUrl,
                    isVerified = true
                )
            }
        } else emptyList()

        val allPublicServers = (publishedServers + PopularDiscoverServers).distinctBy { it.id }
        val popularMatches = allPublicServers.filter { server ->
            val matchesCategory = selectedCategory == "All" ||
                server.category.equals(selectedCategory, ignoreCase = true) ||
                server.tags.any { it.equals(selectedCategory, ignoreCase = true) }
            val matchesQuery = trimmed.isEmpty() ||
                server.name.contains(trimmed, ignoreCase = true) ||
                server.description.contains(trimmed, ignoreCase = true) ||
                server.category.contains(trimmed, ignoreCase = true) ||
                server.id.contains(trimmed, ignoreCase = true) ||
                server.tags.any { it.contains(trimmed, ignoreCase = true) }
            matchesCategory && matchesQuery
        }.sortedWith(
            compareByDescending<DiscoverServer> { it.name.equals(trimmed, ignoreCase = true) }
                .thenByDescending { it.rawMemberCount }
        )

        val filteredJoined = joinedMatches.filter { server ->
            selectedCategory == "All" ||
                server.category.equals(selectedCategory, ignoreCase = true) ||
                server.tags.any { it.equals(selectedCategory, ignoreCase = true) }
        }
        (filteredJoined + popularMatches).distinctBy { it.id }.map { server ->
            val matchingSpace = spaces.firstOrNull { it.id == server.id || it.name.equals(server.name, ignoreCase = true) }
            if (matchingSpace?.avatarUrl != null) {
                server.copy(avatarUrl = matchingSpace.avatarUrl)
            } else {
                server
            }
        }
    }

    fun joinTarget(target: String) {
        if (target.isBlank() || joiningTargetId != null) return
        joiningTargetId = target
        joinError = null
        scope.launch {
            repository.joinSpace(target)
                .onSuccess { roomId ->
                    joiningTargetId = null
                    onOpenServer(roomId)
                    onClose()
                }
                .onFailure { error ->
                    val rawMsg = error.message ?: "Could not join that server"
                    joinError = if (rawMsg.contains("403") || rawMsg.contains("M_FORBIDDEN") || rawMsg.contains("not invited")) {
                        "Cannot join: this server is private or invite-only."
                    } else {
                        rawMsg
                    }
                    joiningTargetId = null
                }
        }
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
            val widthFraction = if (isFullscreen) 0.70f else 0.75f

            Surface(
                modifier = modifier
                    .orbitGlobalShortcuts()
                    .fillMaxWidth(widthFraction)
                    .fillMaxHeight(0.88f)
                    .heightIn(max = 820.dp)
                    .shadow(28.dp, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                // Material You matte foundation surface (Tone 29, e.g. #322825 on coral, #272B25 on forest)
                color = colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, colorScheme.outlineVariant)
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
                DiscoverHeaderHero(onClose = onClose)

                DiscoverSearchBar(
                    query = query,
                    onQueryChange = { query = it; joinError = null },
                    onClear = { query = ""; joinError = null },
                    onSubmit = { if (looksLikeTarget) joinTarget(trimmed) }
                )

                DiscoverCategoryChips(
                    categories = DiscoverCategories,
                    selected = selectedCategory,
                    onSelect = { selectedCategory = it }
                )

                if (joinError != null) {
                    Text(
                        text = joinError!!,
                        color = tokens.Danger,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(295.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (looksLikeTarget && filteredServers.none { it.id.equals(trimmed, ignoreCase = true) }) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "custom-join-target") {
                            DiscoverDirectJoinCard(
                                target = trimmed,
                                isJoining = joiningTargetId == trimmed,
                                onJoin = { joinTarget(trimmed) }
                            )
                        }
                    }

                    items(items = filteredServers, key = { it.id }) { server ->
                        val isJoined = spaces.any { it.id == server.id || it.name.equals(server.name, ignoreCase = true) }
                        DiscoverServerCard(
                            server = server,
                            isJoined = isJoined,
                            isJoining = joiningTargetId == server.id,
                            onOpen = {
                                val targetRoomId = spaces.firstOrNull { it.id == server.id || it.name.equals(server.name, ignoreCase = true) }?.id ?: server.id
                                onOpenServer(targetRoomId)
                                onClose()
                            },
                            onJoin = { joinTarget(server.id) }
                        )
                    }

                    if (filteredServers.isEmpty() && !looksLikeTarget) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "empty-results") {
                            OrbitEmptyState(
                                title = "No communities found",
                                body = if (trimmed.isEmpty()) {
                                    "No servers match the selected category."
                                } else {
                                    "No servers match \"$trimmed\". Try another search term or paste a room alias like #general:matrix.org"
                                },
                                icon = with(TablerIcons) { Search },
                                modifier = Modifier.fillMaxWidth().height(260.dp)
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
private fun DiscoverHeaderHero(onClose: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val tokens = rememberCosmicTokens()
    val closeInteraction = rememberOrbitInteraction()
    val closeHovered by closeInteraction.hoverSource.collectIsHoveredAsState()

    val closeBg by animateColorAsState(
        targetValue = if (closeHovered) colorScheme.surfaceContainer else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(120),
        label = "closeBg"
    )
    val closeBorder by animateColorAsState(
        targetValue = if (closeHovered) colorScheme.outlineVariant else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(120),
        label = "closeBorder"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Discover Communities",
                style = MaterialTheme.typography.titleLarge.copy(
                    color = tokens.Text_White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp
                )
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Explore popular Matrix servers, voice hangouts, and gaming hubs",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = tokens.Text_Muted,
                    fontSize = 13.sp
                )
            )
        }

        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(closeBg)
                .border(1.dp, closeBorder, CircleShape)
                .hoverable(closeInteraction.hoverSource)
                .orbitClickable(
                    interaction = closeInteraction,
                    role = Role.Button,
                    onClickLabel = "Close",
                    onClick = onClose
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = with(TablerIcons) { X },
                contentDescription = "Close",
                tint = if (closeHovered) tokens.Text_White else tokens.Text_Muted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun DiscoverSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val tokens = rememberCosmicTokens()
    val interaction = rememberOrbitInteraction()
    val focused by interaction.focusSource.collectIsFocusedAsState()

    val borderColor by animateColorAsState(
        targetValue = if (focused) colorScheme.primary else colorScheme.outlineVariant,
        animationSpec = androidx.compose.animation.core.tween(150),
        label = "searchBorder"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .height(48.dp)
            .clip(CircleShape)
            .background(colorScheme.surfaceContainerLow)
            .border(1.dp, borderColor, CircleShape)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = with(TablerIcons) { Search },
                contentDescription = null,
                tint = if (focused) colorScheme.primary else tokens.Text_Muted,
                modifier = Modifier.size(18.dp)
            )

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search by server name, topic, or category (e.g. Gaming, Tech, Music)...",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = tokens.Text_Muted,
                            fontSize = 14.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = tokens.Text_White,
                        fontSize = 14.sp
                    ),
                    interactionSource = interaction.focusSource,
                    cursorBrush = SolidColor(colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { onSubmit() },
                        onDone = { onSubmit() },
                        onGo = { onSubmit() }
                    )
                )
            }

            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(colorScheme.surfaceContainer)
                        .clickable(role = Role.Button, onClickLabel = "Clear search", onClick = onClear),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = with(TablerIcons) { X },
                        contentDescription = "Clear",
                        tint = tokens.Text_Secondary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoverCategoryChips(
    categories: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val tokens = rememberCosmicTokens()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        categories.forEach { category ->
            val isSelected = category == selected
            val chipBg by animateColorAsState(
                targetValue = if (isSelected) colorScheme.primary else colorScheme.surfaceContainer,
                animationSpec = androidx.compose.animation.core.tween(150),
                label = "chipBg"
            )
            val chipBorder = if (isSelected) colorScheme.primary else colorScheme.outlineVariant
            val chipText = if (isSelected) colorScheme.onPrimary else tokens.Text_Muted

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(chipBg)
                    .border(1.dp, chipBorder, RoundedCornerShape(16.dp))
                    .clickable(
                        role = Role.Tab,
                        onClickLabel = category,
                        onClick = { onSelect(category) }
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = category,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = chipText,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp
                    )
                )
            }
        }
    }
}

@Composable
private fun DiscoverServerCard(
    server: DiscoverServer,
    isJoined: Boolean,
    isJoining: Boolean,
    onOpen: () -> Unit,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val tokens = rememberCosmicTokens()
    val interaction = rememberOrbitInteraction()
    val hovered by interaction.hoverSource.collectIsHoveredAsState()
    val pastel = remember(server.id) { ui.theme.pastelServerColor(server.id) }

    val borderColor by animateColorAsState(
        targetValue = if (hovered) colorScheme.primary.copy(alpha = 0.7f) else colorScheme.outlineVariant.copy(alpha = 0.6f),
        animationSpec = androidx.compose.animation.core.tween(120),
        label = "serverCardBorder"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // Nested card surface (Tone 25, e.g. #281F1D on coral/sunset)
            .background(colorScheme.surfaceContainer)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .hoverable(interaction.hoverSource)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            pastel.copy(alpha = 0.18f),
                            colorScheme.surfaceContainer
                        )
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(y = 18.dp)
                    .padding(start = 14.dp)
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colorScheme.surfaceContainer)
                    .padding(3.dp),
                contentAlignment = Alignment.Center
            ) {
                var imageFailed by remember(server.avatarUrl) { mutableStateOf(false) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!server.avatarUrl.isNullOrBlank() && !imageFailed) {
                        coil3.compose.AsyncImage(
                            model = server.avatarUrl,
                            contentDescription = server.name,
                            contentScale = ContentScale.Crop,
                            onError = { imageFailed = true },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val categoryIcon = when (server.category.lowercase()) {
                            "gaming" -> with(TablerIcons) { DeviceGamepad }
                            "tech" -> with(TablerIcons) { Code }
                            "music" -> with(TablerIcons) { FileMusic }
                            "official" -> with(TablerIcons) { Compass }
                            "community", "joined" -> with(TablerIcons) { Users }
                            "creative", "science" -> with(TablerIcons) { Stars }
                            else -> null
                        }
                        if (categoryIcon != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(pastel.copy(alpha = 0.20f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = categoryIcon,
                                    contentDescription = server.name,
                                    tint = pastel,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        } else {
                            OrbitServerAssetIcon(
                                label = server.name,
                                color = pastel,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(pastel)
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 22.dp, bottom = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)
                ) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = tokens.Text_White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (server.isVerified) {
                        Icon(
                            imageVector = with(TablerIcons) { CircleCheck },
                            contentDescription = "Verified community",
                            tint = colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(colorScheme.primary.copy(alpha = 0.14f))
                        .padding(horizontal = 7.dp, vertical = 2.5.dp)
                ) {
                    Text(
                        text = server.category,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colorScheme.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = server.description,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = tokens.Text_Secondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = 32.dp)
            )

            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = with(TablerIcons) { Users },
                    contentDescription = null,
                    tint = tokens.Text_Muted,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "${server.memberCount} members • ${server.onlineCount} online",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = tokens.Text_Muted,
                        fontSize = 11.5.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isJoined) {
                DiscoverActionButton(
                    text = "Joined",
                    isPrimary = false,
                    onClick = onOpen
                )
            } else {
                DiscoverActionButton(
                    text = "Join Server",
                    isPrimary = true,
                    loading = isJoining,
                    onClick = onJoin
                )
            }
        }
    }
}

@Composable
private fun DiscoverActionButton(
    text: String,
    isPrimary: Boolean,
    onClick: () -> Unit,
    loading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val interaction = rememberOrbitInteraction()
    val hovered by interaction.hoverSource.collectIsHoveredAsState()
    val pressed by interaction.pressSource.collectIsPressedAsState()

    val bg by animateColorAsState(
        targetValue = if (isPrimary) {
            when {
                pressed -> colorScheme.primary.copy(alpha = 0.85f)
                hovered -> colorScheme.primary.copy(alpha = 0.92f)
                else -> colorScheme.primary
            }
        } else {
            when {
                pressed -> colorScheme.surfaceContainerHigh
                hovered -> colorScheme.surfaceContainerHigh
                else -> colorScheme.surfaceContainerLow
            }
        },
        animationSpec = androidx.compose.animation.core.tween(100),
        label = "actionBtnBg"
    )

    val contentColor = if (isPrimary) colorScheme.onPrimary else colorScheme.onSurface

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .hoverable(interaction.hoverSource)
            .orbitClickable(interaction, role = Role.Button, onClickLabel = text, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = contentColor,
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 2.dp
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = contentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            )
        }
    }
}

@Composable
private fun DiscoverDirectJoinCard(
    target: String,
    isJoining: Boolean,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val tokens = rememberCosmicTokens()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colorScheme.surfaceContainer)
            .border(1.dp, colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(colorScheme.primary.copy(alpha = 0.18f), colorScheme.surfaceContainer)
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(y = 16.dp)
                    .padding(start = 14.dp)
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colorScheme.surfaceContainer)
                    .padding(2.5.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(9.5.dp))
                        .background(colorScheme.primary.copy(alpha = 0.20f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = with(TablerIcons) { Search },
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 20.dp, bottom = 14.dp)
        ) {
            Text(
                text = target,
                style = MaterialTheme.typography.titleSmall.copy(
                    color = tokens.Text_White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Join this public room or space directly via Matrix address",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = tokens.Text_Muted,
                    fontSize = 12.sp
                ),
                modifier = Modifier.padding(bottom = 12.dp)
            )
            DiscoverActionButton(
                text = "Join Server",
                isPrimary = true,
                loading = isJoining,
                onClick = onJoin
            )
        }
    }
}