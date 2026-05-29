package ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

// ═══════════════════════════════════════════════════════════════════
//  ORBIT PLUS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun OrbitPlusPane() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeroCard(
            icon = Icons.Default.RocketLaunch,
            iconTint = Color.White,
            title = "You have Orbit Plus!",
            subtitle = "All premium features are permanently free for every Orbit user. No credit cards, no trials, no subscriptions — just pure, unlimited communication.",
            gradient = listOf(Color(0xFF7C6AF0), Color(0xFF4B32C3)),
            buttonText = "Explore Your Perks"
        )

        SectionHeader("Your Premium Features")
        PrefCard("Custom Emojis", "Upload unlimited custom emojis to any server.") { StatusBadge("Active", Color(0xFF10B981)) }
        PrefCard("HD Video Streaming", "Stream at 1080p 60fps or higher in voice channels.") { StatusBadge("Active", Color(0xFF10B981)) }
        PrefCard("Large File Uploads", "Upload files up to 500 MB per message.") { StatusBadge("Active", Color(0xFF10B981)) }
        PrefCard("Animated Avatar", "Use animated GIFs as your profile picture.") { StatusBadge("Active", Color(0xFF10B981)) }
        PrefCard("Custom Profile Banner", "Set any image as your profile banner.") { StatusBadge("Active", Color(0xFF10B981)) }
        PrefCard("Extended Message History", "Search through your complete message history forever.") { StatusBadge("Active", Color(0xFF10B981)) }
        PrefCard("Priority Support", "Get faster responses from the Orbit support team.") { StatusBadge("Active", Color(0xFF10B981)) }
        PrefCard("Custom Themes", "Access exclusive themes and color palettes.") { StatusBadge("Active", Color(0xFF10B981)) }
        PrefCard("Higher Quality Audio", "128kbps audio in all voice channels.") { StatusBadge("Active", Color(0xFF10B981)) }
        PrefCard("Custom Stickers", "Upload and use custom sticker packs across servers.") { StatusBadge("Active", Color(0xFF10B981)) }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  SERVER OVERDRIVE
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun ServerOverdrivePane() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeroCard(
            icon = Icons.Default.Speed,
            iconTint = Color.White,
            title = "Server Overdrive",
            subtitle = "Supercharge your communities with higher audio quality, custom vanity URLs, increased emoji slots, and more. Unlimited Overdrives for everyone!",
            gradient = listOf(Color(0xFFEC4899), Color(0xFF8B5CF6)),
            buttonText = "Boost a Server"
        )

        SectionHeader("Overdrive Perks (All Unlocked)")
        PrefCard("128kbps Audio", "Crystal clear voice chat audio quality.") { StatusBadge("Unlocked", Color(0xFF10B981)) }
        PrefCard("Custom Server Banner", "Display a banner at the top of your server.") { StatusBadge("Unlocked", Color(0xFF10B981)) }
        PrefCard("Custom Server Icon", "Animated server icons for your community.") { StatusBadge("Unlocked", Color(0xFF10B981)) }
        PrefCard("Vanity Invite URL", "Use a custom link like orbit.chat/yourserver.") { StatusBadge("Unlocked", Color(0xFF10B981)) }
        PrefCard("Server Emoji Slots", "Up to 500 custom emojis per server.") { StatusBadge("500 slots", Color(0xFF6366F1)) }
        PrefCard("Server Sticker Slots", "Up to 100 custom stickers per server.") { StatusBadge("100 slots", Color(0xFF6366F1)) }
        PrefCard("Upload Limit", "50 MB file uploads for all server members.") { StatusBadge("50 MB", Color(0xFF6366F1)) }

        SectionHeader("Your Boosted Servers")
        PrefCard("No servers boosted yet", "Visit any server and click 'Overdrive' to supercharge it.") {
            Icon(Icons.Default.Bolt, null, tint = rememberCosmicTokens().Text_Muted)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  ACTIVE PERKS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun ActivePerksPane() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Core Features")
        PrefCard("Orbit Plus", "Permanently active for all users.") { StatusBadge("Active", Color(0xFF10B981)) }
        PrefCard("HD Streaming", "Stream at 1080p 60fps in voice channels.") { StatusBadge("Included", Color(0xFF10B981)) }
        PrefCard("Custom Emojis", "Unlimited custom emoji uploads.") { StatusBadge("Included", Color(0xFF10B981)) }
        PrefCard("Large File Uploads", "Upload files up to 500 MB.") { StatusBadge("Included", Color(0xFF10B981)) }

        SectionHeader("Community Features")
        PrefCard("Server Overdrive", "Boost any server with premium features.") { StatusBadge("Unlimited", Color(0xFF6366F1)) }
        PrefCard("Custom Stickers", "Create and use sticker packs.") { StatusBadge("Included", Color(0xFF10B981)) }
        PrefCard("Server Discovery", "List your server in the public discovery directory.") { StatusBadge("Included", Color(0xFF10B981)) }

        SectionHeader("Personalization")
        PrefCard("Animated Avatars", "Use GIF profile pictures.") { StatusBadge("Included", Color(0xFF10B981)) }
        PrefCard("Custom Themes", "All themes and palettes unlocked.") { StatusBadge("Included", Color(0xFF10B981)) }
        PrefCard("Profile Banners", "Custom banner images on your profile.") { StatusBadge("Included", Color(0xFF10B981)) }
        PrefCard("Avatar Decorations", "Animated avatar rings and effects.") { StatusBadge("Included", Color(0xFF10B981)) }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  COSMIC GIFTS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun CosmicGiftsPane() {
    val t = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeroCard(
            icon = Icons.Default.CardGiftcard,
            iconTint = Color(0xFFF59E0B),
            title = "Cosmic Gifts",
            subtitle = "Send cosmetic gifts to your friends! Avatar decorations, profile effects, and sticker packs — all completely free.",
            buttonText = "Send a Gift"
        )

        SectionHeader("Available Gifts")
        PrefCard("Avatar Ring — Flame", "A fiery animated ring around your avatar.") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = t.Accent), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text("Send", fontSize = 12.sp) }
        }
        PrefCard("Avatar Ring — Frost", "An icy crystalline ring effect.") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = t.Accent), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text("Send", fontSize = 12.sp) }
        }
        PrefCard("Profile Effect — Sparkle", "Sparkling particles on your profile card.") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = t.Accent), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text("Send", fontSize = 12.sp) }
        }
        PrefCard("Profile Effect — Confetti", "Celebratory confetti particles.") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = t.Accent), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text("Send", fontSize = 12.sp) }
        }
        PrefCard("Sticker Pack — Space Cats", "12 animated space cat stickers.") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = t.Accent), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text("Send", fontSize = 12.sp) }
        }

        SectionHeader("Received Gifts")
        PrefCard("No gifts received yet", "When friends send you gifts, they'll appear here.") {
            Icon(Icons.Default.Inbox, null, tint = t.Text_Muted)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  PERK STATUS — all free, no billing
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun PerkStatusPane() {
    val t = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Celebration banner
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF10B981), Color(0xFF059669))))
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Celebration, null, tint = Color.White, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Everything is Free!", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(4.dp))
                Text("Orbit will never charge you. No credit card needed, no hidden fees, no premium tiers. You get everything, forever.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), lineHeight = 18.sp)
            }
        }

        SectionHeader("Account Status")
        PrefCard("Membership", "Orbit Plus — Permanently Free") { StatusBadge("Free Forever", Color(0xFF10B981)) }
        PrefCard("Server Overdrive", "Unlimited boosts at no cost.") { StatusBadge("Unlimited", Color(0xFF6366F1)) }
        PrefCard("Storage Used", "You have unlimited cloud storage.") { Text("42 MB", style = MaterialTheme.typography.labelMedium.copy(color = t.Accent, fontWeight = FontWeight.Bold)) }
        PrefCard("Account Age", "Member since you signed up.") { Icon(Icons.Default.CalendarMonth, null, tint = t.Text_Muted) }
        PrefCard("Account ID", "Your unique Orbit identifier.") { Text("Copy", style = MaterialTheme.typography.labelMedium.copy(color = t.Accent, fontWeight = FontWeight.Bold)) }

        SectionHeader("Why is Orbit Free?")
        PrefCard("Our Mission", "Orbit is built as an open-source communication platform. Connecting people should never cost money. We're funded by our community and sponsors, not your wallet.") {
            Icon(Icons.Default.Favorite, null, tint = Color(0xFFEF4444))
        }
        PrefCard("Open Source", "Orbit's code is publicly available. Anyone can contribute, audit, and improve it.") {
            Icon(Icons.Default.Code, null, tint = t.Accent)
        }
    }
}
