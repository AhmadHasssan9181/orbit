package ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import repository.OrbitRepository
import ui.model.OrbitConnectionSettings
import ui.model.OrbitFamilyCenterSettings
import ui.model.OrbitPrivacySettings
import ui.model.OrbitProfileExtras
import ui.theme.rememberCosmicTokens

// ═══════════════════════════════════════════════════════════════════
//  PROFILES
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun ProfilesPane(repository: OrbitRepository) {
    val t = rememberCosmicTokens()
    val scope = rememberCoroutineScope()
    
    val profileExtras by repository.observeAccountData("im.orbit.profile_extras", OrbitProfileExtras.serializer()).collectAsState(null)
    
    val currentExtras = profileExtras ?: OrbitProfileExtras()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── Banner & Avatar ──
        Box(
            modifier = Modifier.fillMaxWidth().padding(bottom = 44.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFA78BFA))))
            ) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f))
                        .clickable { }.padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Edit, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Change Banner", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            
            // Avatar
            Box(
                modifier = Modifier.align(Alignment.BottomStart).offset(x = 24.dp, y = 44.dp)
                    .size(100.dp).clip(CircleShape).background(t.Bg).padding(6.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().clip(CircleShape).background(t.CardBgHover),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.AddAPhoto, null, tint = t.Text_Muted, modifier = Modifier.size(32.dp)) }
            }
        }

        // ── Identity ──
        SectionHeader("Identity")
        
        // These fields are standard OrbitProfileExtras for now
        var pronouns by remember(currentExtras.pronouns) { mutableStateOf(currentExtras.pronouns) }
        SettingsTextField(label = "Pronouns", description = "Add your pronouns so others know how to refer to you.", value = pronouns, onValueChange = { pronouns = it }, placeholder = "e.g. they/them, she/her, he/him", onBlur = {
            scope.launch { repository.setAccountData("im.orbit.profile_extras", currentExtras.copy(pronouns = pronouns), OrbitProfileExtras.serializer()) }
        })

        var email by remember(currentExtras.email) { mutableStateOf(currentExtras.email) }
        SettingsTextField(label = "Email", description = "Your email address. Used for password recovery and notifications.", value = email, onValueChange = { email = it }, onBlur = {
            scope.launch { repository.setAccountData("im.orbit.profile_extras", currentExtras.copy(email = email), OrbitProfileExtras.serializer()) }
        })

        // ── Profile Customization ──
        SectionHeader("Profile Customization")
        var aboutMe by remember(currentExtras.aboutMe) { mutableStateOf(currentExtras.aboutMe) }
        SettingsTextField(label = "About Me", description = "Write a short bio that appears on your profile card.", value = aboutMe, onValueChange = { aboutMe = it }, placeholder = "Tell the world something about yourself", multiline = true, onBlur = {
            scope.launch { repository.setAccountData("im.orbit.profile_extras", currentExtras.copy(aboutMe = aboutMe), OrbitProfileExtras.serializer()) }
        })

        var customStatus by remember(currentExtras.customStatus) { mutableStateOf(currentExtras.customStatus) }
        SettingsTextField(label = "Custom Status", description = "Set an emoji or text status visible to your friends.", value = customStatus, onValueChange = { customStatus = it }, placeholder = "🎮 Playing Valorant", onBlur = {
            scope.launch { repository.setAccountData("im.orbit.profile_extras", currentExtras.copy(customStatus = customStatus), OrbitProfileExtras.serializer()) }
        })

        PrefCard("Profile Accent Color", "Customize the accent color on your profile card.") {
            ChipGroup(
                options = listOf("Purple", "Blue", "Teal", "Green", "Pink", "Orange", "Red"),
                selected = currentExtras.profileColor,
                onSelect = { color -> scope.launch { repository.setAccountData("im.orbit.profile_extras", currentExtras.copy(profileColor = color), OrbitProfileExtras.serializer()) } }
            )
        }

        // ── Avatar & Decorations ──
        SectionHeader("Avatar & Decorations")
        PrefCard("Animated Avatar", "Use an animated GIF as your profile picture.") {
            StatusBadge("Free", Color(0xFF10B981))
        }
        PrefCard("Avatar Decoration", "Add a border or effect around your avatar.") {
            ChipGroup(
                options = listOf("None", "Fire", "Sparkles", "Neon Border", "Glitch"),
                selected = currentExtras.avatarDecoration,
                onSelect = { dec -> scope.launch { repository.setAccountData("im.orbit.profile_extras", currentExtras.copy(avatarDecoration = dec), OrbitProfileExtras.serializer()) } }
            )
        }
        PrefCard("Profile Effect", "Add a background effect to your profile card.") {
            ChipGroup(
                options = listOf("None", "Falling Stars", "Matrix Rain", "Cherry Blossoms", "Snow"),
                selected = currentExtras.profileEffect,
                onSelect = { eff -> scope.launch { repository.setAccountData("im.orbit.profile_extras", currentExtras.copy(profileEffect = eff), OrbitProfileExtras.serializer()) } }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  PRIVACY
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun PrivacyPane(repository: OrbitRepository) {
    val t = rememberCosmicTokens()
    val scope = rememberCoroutineScope()
    
    val privacyData by repository.observeAccountData("im.orbit.privacy", OrbitPrivacySettings.serializer()).collectAsState(null)
    val priv = privacyData ?: OrbitPrivacySettings()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Direct Messages")
        PrefCard("Safe Direct Messaging", "Automatically scan and delete direct messages you receive that contain explicit media.") {
            ChipGroup(
                options = listOf("Keep me safe", "My friends are nice", "Do not scan"),
                selected = priv.dmFilter,
                onSelect = { f -> scope.launch { repository.setAccountData("im.orbit.privacy", priv.copy(dmFilter = f), OrbitPrivacySettings.serializer()) } }
            )
        }
        PrefCard("Allow direct messages from server members", "If disabled, you won't receive DMs from people in a server unless you are friends.") {
            CosmicSwitch(checked = priv.allowDmsFromServerMembers, onCheckedChange = { b -> scope.launch { repository.setAccountData("im.orbit.privacy", priv.copy(allowDmsFromServerMembers = b), OrbitPrivacySettings.serializer()) } })
        }
        PrefCard("Message Request Inbox", "Route messages from people you don't know into a separate inbox.") {
            CosmicSwitch(checked = priv.messageRequestInbox, onCheckedChange = { b -> scope.launch { repository.setAccountData("im.orbit.privacy", priv.copy(messageRequestInbox = b), OrbitPrivacySettings.serializer()) } })
        }

        SectionHeader("Friend Requests")
        PrefCard("Everyone can send requests", "Anyone with your username can send you a friend request.") {
            CosmicSwitch(checked = priv.allowFriendRequestsFromEveryone, onCheckedChange = { b -> scope.launch { repository.setAccountData("im.orbit.privacy", priv.copy(allowFriendRequestsFromEveryone = b), OrbitPrivacySettings.serializer()) } })
        }
        PrefCard("Server members can send requests", "People who share a server with you can send a friend request.") {
            CosmicSwitch(checked = priv.allowFriendRequestsFromServerMembers, onCheckedChange = { b -> scope.launch { repository.setAccountData("im.orbit.privacy", priv.copy(allowFriendRequestsFromServerMembers = b), OrbitPrivacySettings.serializer()) } })
        }

        SectionHeader("Data & Analytics")
        PrefCard("Help improve Orbit", "Allow Orbit to collect anonymous crash reports and usage statistics to improve the app.") {
            CosmicSwitch(checked = priv.shareAnalytics, onCheckedChange = { b -> scope.launch { repository.setAccountData("im.orbit.privacy", priv.copy(shareAnalytics = b), OrbitPrivacySettings.serializer()) } })
        }
        PrefCard("Personalization", "Use my data to customize my Orbit experience.") {
            CosmicSwitch(checked = priv.personalization, onCheckedChange = { b -> scope.launch { repository.setAccountData("im.orbit.privacy", priv.copy(personalization = b), OrbitPrivacySettings.serializer()) } })
        }
        PrefCard("Request Data", "Receive a copy of all your messages, images, and account data via email.") {
            Button(onClick = { scope.launch { repository.setAccountData("im.orbit.privacy", priv.copy(dataRequest = true), OrbitPrivacySettings.serializer()) } }, colors = ButtonDefaults.buttonColors(containerColor = t.Accent)) {
                Text("Request Data Archive")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  FAMILY CENTER
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun FamilyCenterPane(repository: OrbitRepository) {
    val t = rememberCosmicTokens()
    val scope = rememberCoroutineScope()
    
    val famData by repository.observeAccountData("im.orbit.family", OrbitFamilyCenterSettings.serializer()).collectAsState(null)
    val fam = famData ?: OrbitFamilyCenterSettings()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeroCard(
            icon = Icons.Default.FamilyRestroom,
            iconTint = Color(0xFF10B981),
            title = "Family Centre",
            subtitle = "Manage your teen's account settings and review their activity to keep them safe.",
            buttonText = "Link Teen Account"
        )

        SectionHeader("Teen Safety Controls")
        PrefCard("Restrict Direct Messages", "Prevent teen from receiving DMs from non-friends.") {
            CosmicSwitch(checked = fam.restrictDms, onCheckedChange = { b -> scope.launch { repository.setAccountData("im.orbit.family", fam.copy(restrictDms = b), OrbitFamilyCenterSettings.serializer()) } })
        }
        PrefCard("Restrict Server Joining", "Require approval before joining new communities.") {
            CosmicSwitch(checked = fam.restrictServerJoining, onCheckedChange = { b -> scope.launch { repository.setAccountData("im.orbit.family", fam.copy(restrictServerJoining = b), OrbitFamilyCenterSettings.serializer()) } })
        }
        PrefCard("Block NSFW Content", "Strictly enforce NSFW filters across all channels.") {
            CosmicSwitch(checked = fam.blockNsfw, onCheckedChange = { b -> scope.launch { repository.setAccountData("im.orbit.family", fam.copy(blockNsfw = b), OrbitFamilyCenterSettings.serializer()) } })
        }
        PrefCard("Restrict Friend Requests", "Only allow friend requests from server members or stop them entirely.") {
            CosmicSwitch(checked = fam.restrictFriendRequests, onCheckedChange = { b -> scope.launch { repository.setAccountData("im.orbit.family", fam.copy(restrictFriendRequests = b), OrbitFamilyCenterSettings.serializer()) } })
        }

        SectionHeader("Screen Time & Activity")
        PrefCard("Enable Screen Time Limits", "Automatically lock access to Orbit after a certain amount of time.") {
            CosmicSwitch(checked = fam.enableScreenTimeLimits, onCheckedChange = { b -> scope.launch { repository.setAccountData("im.orbit.family", fam.copy(enableScreenTimeLimits = b), OrbitFamilyCenterSettings.serializer()) } })
        }
        if (fam.enableScreenTimeLimits) {
            PrefCard("Daily Time Limit", "Maximum hours allowed per day.") {
                ChipGroup(
                    options = listOf("1 hour", "2 hours", "4 hours", "Custom"),
                    selected = fam.dailyTimeLimit,
                    onSelect = { l -> scope.launch { repository.setAccountData("im.orbit.family", fam.copy(dailyTimeLimit = l), OrbitFamilyCenterSettings.serializer()) } }
                )
            }
        }
        PrefCard("Weekly Activity Digest", "Send a summary of friends added and servers joined to your email.") {
            CosmicSwitch(checked = fam.weeklyDigest, onCheckedChange = { b -> scope.launch { repository.setAccountData("im.orbit.family", fam.copy(weeklyDigest = b), OrbitFamilyCenterSettings.serializer()) } })
        }
        PrefCard("Alert on new DM from unknown user", "Send a notification when your teen is messaged by a stranger.") {
            CosmicSwitch(checked = fam.alertOnNewDm, onCheckedChange = { b -> scope.launch { repository.setAccountData("im.orbit.family", fam.copy(alertOnNewDm = b), OrbitFamilyCenterSettings.serializer()) } })
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  AUTHORIZED APPS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun AuthorizedAppsPane() {
    val t = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Active Authorizations")
        Text("These apps and services have access to your Orbit account.", color = t.Text_Secondary, style = MaterialTheme.typography.bodyMedium)

        AppCard("Midjourney Bot", "Authorized on Oct 12, 2024", "Can read your messages and generate images.", Icons.Default.Brush, Color(0xFFF59E0B))
        AppCard("Rythm", "Authorized on Jan 3, 2024", "Can join voice channels and play audio.", Icons.Default.MusicNote, Color(0xFFEF4444))
        AppCard("Carl-bot", "Authorized on Nov 22, 2023", "Can manage roles and read message history.", Icons.Default.SmartToy, Color(0xFF3B82F6))
        AppCard("Tracker.gg", "Authorized on May 5, 2023", "Can see your username and activity status.", Icons.Default.Analytics, Color(0xFF10B981))

        Spacer(Modifier.height(16.dp))
        SectionHeader("Security")
        var notifyOnAuth by remember { mutableStateOf(true) }
        PrefCard("Notify on new authorization", "Send a direct message when a new app is authorized to access your account.") {
            CosmicSwitch(checked = notifyOnAuth, onCheckedChange = { notifyOnAuth = it })
        }
        PrefCard("Deauthorize all apps", "Instantly revoke access for all bots and third-party services.") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.8f))) {
                Text("Revoke All")
            }
        }
    }
}

@Composable
private fun AppCard(name: String, date: String, perms: String, icon: androidx.compose.ui.graphics.vector.ImageVector, iconColor: Color) {
    val t = rememberCosmicTokens()
    Box(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(t.CardBg).border(1.dp, t.Separator, RoundedCornerShape(8.dp))
            .clickable { }.padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(iconColor.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, color = t.Text_White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(date, color = t.Text_Muted, style = MaterialTheme.typography.bodySmall)
                Text(perms, color = t.Text_Secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = t.CardBgHover, contentColor = Color(0xFFEF4444))) {
                Text("Revoke", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  CONNECTIONS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun ConnectionsPane(repository: OrbitRepository) {
    val t = rememberCosmicTokens()
    val scope = rememberCoroutineScope()
    
    val connData by repository.observeAccountData("im.orbit.connections", OrbitConnectionSettings.serializer()).collectAsState(null)
    val conn = connData ?: OrbitConnectionSettings()
    
    fun setLinked(service: String, isLinked: Boolean) {
        val newLinks = conn.linkedServices.toMutableMap()
        if (isLinked) newLinks[service] = true else newLinks.remove(service)
        scope.launch { repository.setAccountData("im.orbit.connections", conn.copy(linkedServices = newLinks), OrbitConnectionSettings.serializer()) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeroCard(
            icon = Icons.Default.Link,
            iconTint = Color(0xFF3B82F6),
            title = "Connections",
            subtitle = "Connect your other accounts to find friends and show off your activity.",
            buttonText = ""
        )

        SectionHeader("Gaming & Streaming")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionCard(Icons.Default.Gamepad, "Xbox", Color(0xFF107C10), conn.linkedServices["Xbox"] == true) { setLinked("Xbox", it) }
            ConnectionCard(Icons.Default.SportsEsports, "PlayStation", Color(0xFF003087), conn.linkedServices["PlayStation"] == true) { setLinked("PlayStation", it) }
            ConnectionCard(Icons.Default.Computer, "Steam", Color(0xFF171A21), conn.linkedServices["Steam"] == true) { setLinked("Steam", it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionCard(Icons.Default.LiveTv, "Twitch", Color(0xFF9146FF), conn.linkedServices["Twitch"] == true) { setLinked("Twitch", it) }
            ConnectionCard(Icons.Default.PlayArrow, "YouTube", Color(0xFFFF0000), conn.linkedServices["YouTube"] == true) { setLinked("YouTube", it) }
            ConnectionCard(Icons.Default.Games, "Epic Games", Color(0xFF313131), conn.linkedServices["Epic Games"] == true) { setLinked("Epic Games", it) }
        }

        SectionHeader("Social & Music")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionCard(Icons.Default.MusicNote, "Spotify", Color(0xFF1DB954), conn.linkedServices["Spotify"] == true) { setLinked("Spotify", it) }
            ConnectionCard(Icons.Default.CameraAlt, "Instagram", Color(0xFFE1306C), conn.linkedServices["Instagram"] == true) { setLinked("Instagram", it) }
            ConnectionCard(Icons.Default.Message, "Twitter / X", Color(0xFF000000), conn.linkedServices["Twitter"] == true) { setLinked("Twitter", it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionCard(Icons.Default.Tag, "Reddit", Color(0xFFFF4500), conn.linkedServices["Reddit"] == true) { setLinked("Reddit", it) }
            ConnectionCard(Icons.Default.Forum, "GitHub", Color(0xFF333333), conn.linkedServices["GitHub"] == true) { setLinked("GitHub", it) }
            ConnectionCard(Icons.Default.Send, "Telegram", Color(0xFF26A5E4), conn.linkedServices["Telegram"] == true) { setLinked("Telegram", it) }
        }

        SectionHeader("Connection Settings")
        PrefCard("Display on profile", "Show your connected accounts on your public profile card.") {
            CosmicSwitch(checked = conn.displayOnProfile, onCheckedChange = { b -> scope.launch { repository.setAccountData("im.orbit.connections", conn.copy(displayOnProfile = b), OrbitConnectionSettings.serializer()) } })
        }
        PrefCard("Display status", "Show what you're playing/listening to from connected accounts as your status.") {
            CosmicSwitch(checked = conn.enableRichPresence, onCheckedChange = { b -> scope.launch { repository.setAccountData("im.orbit.connections", conn.copy(enableRichPresence = b), OrbitConnectionSettings.serializer()) } })
        }
        PrefCard("Sync Display Name", "Automatically update your Orbit name to match your primary gaming account.") {
            CosmicSwitch(checked = conn.syncDisplayName, onCheckedChange = { b -> scope.launch { repository.setAccountData("im.orbit.connections", conn.copy(syncDisplayName = b), OrbitConnectionSettings.serializer()) } })
        }
        PrefCard("Sync Avatar", "Automatically update your Orbit avatar to match your primary gaming account.") {
            CosmicSwitch(checked = conn.syncAvatar, onCheckedChange = { b -> scope.launch { repository.setAccountData("im.orbit.connections", conn.copy(syncAvatar = b), OrbitConnectionSettings.serializer()) } })
        }
    }
}

@Composable
private fun RowScope.ConnectionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, color: Color, isLinked: Boolean, onLinkChange: (Boolean) -> Unit) {
    val t = rememberCosmicTokens()
    Box(
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
            .background(t.CardBg).border(1.dp, if (isLinked) color.copy(alpha=0.5f) else t.Separator, RoundedCornerShape(8.dp))
            .clickable { onLinkChange(!isLinked) }.padding(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, null, tint = color, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text(name, color = t.Text_White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(if (isLinked) "Linked" else "Connect", color = if (isLinked) color else t.Text_Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  HELPER: SettingsTextField (Actual working text input)
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun SettingsTextField(
    label: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    multiline: Boolean = false,
    onBlur: () -> Unit = {}
) {
    val t = rememberCosmicTokens()
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.CardBg)
            .border(1.dp, t.Separator, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(label, color = t.Text_White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (description.isNotEmpty()) {
                    Text(description, color = t.Text_Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = if (multiline) 100.dp else 56.dp),
            placeholder = { Text(placeholder, color = t.Text_Muted.copy(alpha = 0.5f)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = t.Accent,
                unfocusedBorderColor = t.Separator,
                focusedContainerColor = t.Bg,
                unfocusedContainerColor = t.Bg,
                focusedTextColor = t.Text_White,
                unfocusedTextColor = t.Text_White
            ),
            shape = RoundedCornerShape(8.dp),
            singleLine = !multiline,
            maxLines = if (multiline) 5 else 1
        )
        
        // Save hint or button can go here if needed.
        // I will add a small save button.
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = onBlur, colors = ButtonDefaults.buttonColors(containerColor = t.Accent)) {
                Text("Save")
            }
        }
    }
}
