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
import ui.theme.rememberCosmicTokens

// ═══════════════════════════════════════════════════════════════════
//  PROFILES
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun ProfilesPane() {
    val t = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── Banner ──
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
            // Avatar
            Box(
                modifier = Modifier.align(Alignment.BottomStart).offset(x = 24.dp, y = 44.dp)
                    .size(88.dp).clip(CircleShape).background(t.Bg).padding(4.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().clip(CircleShape).background(t.CardBgHover),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.AddAPhoto, null, tint = t.Text_Muted, modifier = Modifier.size(28.dp)) }
            }
        }

        Spacer(Modifier.height(40.dp))

        // ── Identity ──
        SectionHeader("Identity")
        var displayName by remember { mutableStateOf("User") }
        SettingsTextField(label = "Display Name", description = "This is the name others see in chat and servers.", value = displayName, onValueChange = { displayName = it })

        var username by remember { mutableStateOf("@user") }
        SettingsTextField(label = "Username", description = "Your unique @handle used for mentions and friend requests.", value = username, onValueChange = { username = it })

        var pronouns by remember { mutableStateOf("") }
        SettingsTextField(label = "Pronouns", description = "Add your pronouns so others know how to refer to you.", value = pronouns, onValueChange = { pronouns = it }, placeholder = "e.g. they/them, she/her, he/him")

        var email by remember { mutableStateOf("user@orbit.chat") }
        SettingsTextField(label = "Email", description = "Your email address. Used for password recovery and notifications.", value = email, onValueChange = { email = it })

        // ── Profile Customization ──
        SectionHeader("Profile Customization")
        var aboutMe by remember { mutableStateOf("") }
        SettingsTextField(label = "About Me", description = "Write a short bio that appears on your profile card.", value = aboutMe, onValueChange = { aboutMe = it }, placeholder = "Tell the world something about yourself", multiline = true)

        var customStatus by remember { mutableStateOf("") }
        SettingsTextField(label = "Custom Status", description = "Set an emoji or text status visible to your friends.", value = customStatus, onValueChange = { customStatus = it }, placeholder = "🎮 Playing Valorant")

        var profileColor by remember { mutableStateOf("Purple") }
        PrefCard("Profile Accent Color", "Customize the accent color on your profile card.") {
            ChipGroup(
                options = listOf("Purple", "Blue", "Teal", "Green", "Pink", "Orange", "Red"),
                selected = profileColor,
                onSelect = { profileColor = it }
            )
        }

        // ── Avatar & Decorations ──
        SectionHeader("Avatar & Decorations")
        PrefCard("Animated Avatar", "Use an animated GIF as your profile picture.") {
            StatusBadge("Free", Color(0xFF10B981))
        }
        PrefCard("Avatar Decorations", "Add animated rings and effects around your avatar.") {
            var selected by remember { mutableStateOf("None") }
            ChipGroup(
                options = listOf("None", "Flame", "Frost", "Sakura", "Electric"),
                selected = selected,
                onSelect = { selected = it }
            )
        }
        PrefCard("Profile Effect", "Add particle effects that play on your profile card.") {
            var selected by remember { mutableStateOf("None") }
            ChipGroup(
                options = listOf("None", "Sparkle", "Confetti", "Rain", "Stars"),
                selected = selected,
                onSelect = { selected = it }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  PRIVACY & SAFETY
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun PrivacyPane() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Safe Direct Messaging")
        var dmFilter by remember { mutableStateOf("My friends are nice") }
        PrefCard("Explicit content filter", "Automatically scan and filter explicit media in DMs.") {
            ChipGroup(
                options = listOf("Keep me safe", "My friends are nice", "Do not scan"),
                selected = dmFilter,
                onSelect = { dmFilter = it }
            )
        }

        SectionHeader("Server Privacy Defaults")
        var allowDms by remember { mutableStateOf(true) }
        PrefCard("Allow DMs from server members", "When you join a new server, members can message you directly.") {
            CosmicSwitch(checked = allowDms, onCheckedChange = { allowDms = it })
        }
        var friendAll by remember { mutableStateOf(true) }
        PrefCard("Allow friend requests from everyone", "Anyone can send you a friend request. Turn off to restrict.") {
            CosmicSwitch(checked = friendAll, onCheckedChange = { friendAll = it })
        }
        var friendServer by remember { mutableStateOf(true) }
        PrefCard("Allow friend requests from server members", "People who share servers with you can add you.") {
            CosmicSwitch(checked = friendServer, onCheckedChange = { friendServer = it })
        }
        var msgRequests by remember { mutableStateOf(true) }
        PrefCard("Message request inbox", "Messages from non-friends land in a separate inbox.") {
            CosmicSwitch(checked = msgRequests, onCheckedChange = { msgRequests = it })
        }

        SectionHeader("Activity & Data")
        var analytics by remember { mutableStateOf(false) }
        PrefCard("Share usage statistics", "Help Orbit improve by sharing anonymous usage data.") {
            CosmicSwitch(checked = analytics, onCheckedChange = { analytics = it })
        }
        var personalization by remember { mutableStateOf(false) }
        PrefCard("Personalization", "Tailor content based on your activity and interests.") {
            CosmicSwitch(checked = personalization, onCheckedChange = { personalization = it })
        }
        var dataRequest by remember { mutableStateOf(false) }
        PrefCard("Use data to improve Orbit", "Opt in to let Orbit analyze your data for a better experience.") {
            CosmicSwitch(checked = dataRequest, onCheckedChange = { dataRequest = it })
        }

        SectionHeader("Blocked & Hidden")
        PrefCard("Blocked Users", "Manage users you've blocked from contacting you.") {
            StatusBadge("0 blocked", Color(0xFF10B981))
        }
        PrefCard("Hidden Channels", "Channels you've muted or hidden from your sidebar.") {
            StatusBadge("0 hidden", Color(0xFF6366F1))
        }
        PrefCard("Restricted Servers", "Servers where you have restricted your DM settings.") {
            StatusBadge("0 servers", Color(0xFFF59E0B))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  FAMILY CENTRE
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun FamilyCenterPane() {
    val t = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeroCard(
            icon = Icons.Default.FamilyRestroom,
            iconTint = Color(0xFF60A5FA),
            title = "Family Centre",
            subtitle = "Keep your family safe online. Set up parental controls, monitor activity, and manage screen time for linked accounts.",
            buttonText = "Get Started"
        )

        SectionHeader("Parental Controls")
        var restrictDms by remember { mutableStateOf(false) }
        PrefCard("Restrict direct messages", "Prevent your teen from receiving DMs from unknown users.") {
            CosmicSwitch(checked = restrictDms, onCheckedChange = { restrictDms = it })
        }
        var restrictServers by remember { mutableStateOf(false) }
        PrefCard("Restrict server joining", "Require approval before your teen joins new servers.") {
            CosmicSwitch(checked = restrictServers, onCheckedChange = { restrictServers = it })
        }
        var nsfwBlock by remember { mutableStateOf(true) }
        PrefCard("Block NSFW content", "Prevent access to age-restricted channels and media.") {
            CosmicSwitch(checked = nsfwBlock, onCheckedChange = { nsfwBlock = it })
        }
        var restrictFriends by remember { mutableStateOf(false) }
        PrefCard("Restrict friend requests", "Only allow friend requests from existing friends-of-friends.") {
            CosmicSwitch(checked = restrictFriends, onCheckedChange = { restrictFriends = it })
        }

        SectionHeader("Screen Time")
        var screenTime by remember { mutableStateOf(false) }
        PrefCard("Enable screen time limits", "Set a daily limit for Orbit usage.") {
            CosmicSwitch(checked = screenTime, onCheckedChange = { screenTime = it })
        }
        var dailyLimit by remember { mutableStateOf("2 hours") }
        PrefCard("Daily time limit", "Maximum daily usage before Orbit locks.") {
            ChipGroup(
                options = listOf("1 hour", "2 hours", "4 hours", "Unlimited"),
                selected = dailyLimit,
                onSelect = { dailyLimit = it }
            )
        }

        SectionHeader("Reports")
        var weeklyDigest by remember { mutableStateOf(true) }
        PrefCard("Weekly activity digest", "Receive a summary of activity every Sunday.") {
            CosmicSwitch(checked = weeklyDigest, onCheckedChange = { weeklyDigest = it })
        }
        var alertDMs by remember { mutableStateOf(true) }
        PrefCard("Alert on new DM contacts", "Get notified when your teen messages someone new.") {
            CosmicSwitch(checked = alertDMs, onCheckedChange = { alertDMs = it })
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  AUTHORISED APPS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun AuthorizedAppsPane() {
    val t = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Applications that have been granted access to your Orbit account.", color = t.Text_Muted, style = MaterialTheme.typography.bodySmall)

        SectionHeader("Authorized Applications")
        PrefCard("Rythm Bot", "Music bot • Authorized Oct 2024\nPermissions: Read messages, Join voice channels") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.8f)), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Deauthorize", fontSize = 12.sp) }
        }
        PrefCard("Midjourney", "AI Art • Authorized Nov 2024\nPermissions: Send messages, Read message history") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.8f)), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Deauthorize", fontSize = 12.sp) }
        }
        PrefCard("MEE6", "Moderation • Authorized Dec 2024\nPermissions: Manage roles, Kick members, Ban members") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.8f)), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Deauthorize", fontSize = 12.sp) }
        }
        PrefCard("Carl-bot", "Utility • Authorized Jan 2025\nPermissions: Manage channels, Send messages") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.8f)), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Deauthorize", fontSize = 12.sp) }
        }

        SectionHeader("Security")
        var authNotify by remember { mutableStateOf(true) }
        PrefCard("Notify on new authorization", "Get a notification when a new app is authorized on your account.") {
            CosmicSwitch(checked = authNotify, onCheckedChange = { authNotify = it })
        }
        PrefCard("How apps access your data", "Apps can only perform actions you explicitly grant. You can revoke access at any time from this page.") {
            Icon(Icons.Default.Info, null, tint = t.Accent)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  CONNECTIONS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun ConnectionsPane() {
    val t = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Link your accounts to show what you're playing, listening to, or streaming on your profile.", color = t.Text_Muted, style = MaterialTheme.typography.bodySmall)

        SectionHeader("Gaming")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionCard(Icons.Default.VideogameAsset, "Xbox", Color(0xFF107C10))
            ConnectionCard(Icons.Default.SportsEsports, "PlayStation", Color(0xFF003791))
            ConnectionCard(Icons.Default.Gamepad, "Steam", Color(0xFF1B2838))
            ConnectionCard(Icons.Default.SportsEsports, "Epic Games", Color(0xFF2F2F2F))
        }

        SectionHeader("Streaming & Media")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionCard(Icons.Default.VideoCameraBack, "Twitch", Color(0xFF9146FF))
            ConnectionCard(Icons.Default.PlayArrow, "YouTube", Color(0xFFFF0000))
            ConnectionCard(Icons.Default.MusicNote, "Spotify", Color(0xFF1DB954))
            ConnectionCard(Icons.Default.Radio, "SoundCloud", Color(0xFFFF5500))
        }

        SectionHeader("Social & Dev")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionCard(Icons.Default.Code, "GitHub", Color(0xFF8B5CF6))
            ConnectionCard(Icons.Default.Language, "Reddit", Color(0xFFFF4500))
            ConnectionCard(Icons.Default.CameraAlt, "Instagram", Color(0xFFE1306C))
            ConnectionCard(Icons.Default.Send, "Telegram", Color(0xFF26A5E4))
        }

        SectionHeader("Connected Account Settings")
        var showOnProfile by remember { mutableStateOf(true) }
        PrefCard("Display on profile", "Show your linked accounts on your profile card.") {
            CosmicSwitch(checked = showOnProfile, onCheckedChange = { showOnProfile = it })
        }
        var richPresence by remember { mutableStateOf(true) }
        PrefCard("Enable Rich Presence", "Show detailed info like current song or game on your status.") {
            CosmicSwitch(checked = richPresence, onCheckedChange = { richPresence = it })
        }
        var syncName by remember { mutableStateOf(false) }
        PrefCard("Sync display name", "Automatically update your Orbit display name from connected accounts.") {
            CosmicSwitch(checked = syncName, onCheckedChange = { syncName = it })
        }
        var syncAvatar by remember { mutableStateOf(false) }
        PrefCard("Sync avatar", "Use your avatar from a connected account as your Orbit avatar.") {
            CosmicSwitch(checked = syncAvatar, onCheckedChange = { syncAvatar = it })
        }
    }
}

@Composable
private fun ConnectionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, color: Color) {
    val t = rememberCosmicTokens()
    Box(
        modifier = Modifier
            .size(85.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(t.CardBg)
            .border(1.dp, t.Separator, RoundedCornerShape(10.dp))
            .clickable { },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(6.dp))
            Text(name, color = t.Text_White, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Reusable Text Field for Settings
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun SettingsTextField(
    label: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    multiline: Boolean = false
) {
    val t = rememberCosmicTokens()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.CardBgHover)
            .padding(16.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, color = t.Text_White))
        Text(description, style = MaterialTheme.typography.bodySmall.copy(color = t.Text_Muted, fontSize = 11.sp), modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, color = t.Text_Muted.copy(alpha = 0.5f), fontSize = 13.sp) },
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = t.Bg,
                unfocusedContainerColor = t.Bg,
                focusedTextColor = t.Text_White,
                unfocusedTextColor = t.Text_White,
                focusedBorderColor = t.Accent,
                unfocusedBorderColor = t.Separator,
                cursorColor = t.Accent
            ),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        )
    }
}
