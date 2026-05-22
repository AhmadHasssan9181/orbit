package ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════
// ONICHAT DESIGN SYSTEM — "Cosmic Dark" Palette
// Deep space blacks + violet/indigo gradients + neon glow accents
// ═══════════════════════════════════════════════════════════════

// ─── Base Surfaces ───────────────────────────────────────────────
/** Absolute deepest background — almost pure black with a blue tint */
val Color_DeepBlack    = Color(0xFF080A0F)   // Server Rail bg
/** Deep surface — primary app shell */
val Color_DeepDark     = Color(0xFF0D0E14)   // Old: 0xFF202225
/** Primary surface — channel drawer */
val Color_DarkGray     = Color(0xFF111318)   // Old: 0xFF2F3136
/** Chat area background */
val Color_ChatBackground = Color(0xFF141720) // Old: 0xFF36393F
/** Input field surface */
val Color_InputBackground = Color(0xFF1C1F2B)// Old: 0xFF383A40
/** Bottom user bar */
val Color_UserBar      = Color(0xFF0A0C12)   // Old: 0xFF292B2F

// ─── Glass / Overlay Surfaces ────────────────────────────────────
/** Glassmorphic card surface */
val Color_GlassSurface = Color(0x14FFFFFF)   // white @ 8% alpha
/** Glassmorphic card border */
val Color_GlassBorder  = Color(0x1AFFFFFF)   // white @ 10% alpha
/** Hover background */
val Color_HoverBg      = Color(0x0FFFFFFF)   // white @ 6%
/** Selected / active background */
val Color_ActiveBg     = Color(0x1A7C6AF0)   // accent @ 10%
/** Selected channel highlight */
val Color_ChannelSelected = Color(0x2A7C6AF0)

// ─── Text ────────────────────────────────────────────────────────
val Text_White         = Color(0xFFEAECF5)   // Slightly cool white
val Text_Secondary     = Color(0xFFB4B8D0)   // Bright muted
val Text_Muted         = Color(0xFF6B7094)   // Standard muted
val Text_Disabled      = Color(0xFF3D4160)   // Near-invisible

// ─── Accent — Cosmic Violet ───────────────────────────────────────
/** Primary accent — vibrant violet */
val Color_OrbitAccent  = Color(0xFF7C6AF0)   // Old: 0xFF5865F2
/** Accent lighter — for gradients */
val Color_AccentLight  = Color(0xFFA78BFA)
/** Accent very light — for hover glows */
val Color_AccentGhost  = Color(0x337C6AF0)   // accent @ 20%
/** Accent glow — for shadows/borders on selected */
val Color_AccentGlow   = Color(0x557C6AF0)   // accent @ 33%

// ─── Semantic / Status ────────────────────────────────────────────
/** Online — emerald green with vibrancy */
val Color_SuccessGreen = Color(0xFF34D399)   // Old: 0xFF57F287
/** Idle — warm amber */
val Color_WarningAmber = Color(0xFFFBBF24)   // Old: 0xFFFEE75C
/** Do-not-disturb — coral red */
val Color_DNDRed       = Color(0xFFF87171)   // Old: 0xFFF47B69
/** Offline — very muted */
val Color_OfflineGray  = Color(0xFF3D4160)

// ─── Status Dot Aliases ──────────────────────────────────────────
val StatusDotGreen     = Color_SuccessGreen
val StatusDotAmber     = Color_WarningAmber
val StatusDotRed       = Color_DNDRed

// ─── Gradient Definitions ────────────────────────────────────────
/** Primary gradient — violet to indigo (accent) */
val Color_GradientStart = Color(0xFF7C6AF0)
val Color_GradientMid   = Color(0xFF9B87F5)
val Color_GradientEnd   = Color(0xFFA78BFA)

/** Server icon gradients — each server type */
val Gradient_Blue   = listOf(Color(0xFF3B82F6), Color(0xFF6366F1))
val Gradient_Green  = listOf(Color(0xFF10B981), Color(0xFF059669))
val Gradient_Amber  = listOf(Color(0xFFF59E0B), Color(0xFFD97706))
val Gradient_Red    = listOf(Color(0xFFEF4444), Color(0xFFDC2626))
val Gradient_Pink   = listOf(Color(0xFFEC4899), Color(0xFFDB2777))
val Gradient_Violet = listOf(Color(0xFF7C6AF0), Color(0xFFA78BFA))
val Gradient_Teal   = listOf(Color(0xFF14B8A6), Color(0xFF0D9488))

/** DM / user avatar gradients */
val Gradient_Avatar1 = listOf(Color(0xFF7C6AF0), Color(0xFFA78BFA))
val Gradient_Avatar2 = listOf(Color(0xFF10B981), Color(0xFF6EE7B7))
val Gradient_Avatar3 = listOf(Color(0xFFF59E0B), Color(0xFFFDE68A))
val Gradient_Avatar4 = listOf(Color(0xFFEF4444), Color(0xFFFCA5A5))
val Gradient_Avatar5 = listOf(Color(0xFF3B82F6), Color(0xFF93C5FD))

// ─── Separator / Divider ─────────────────────────────────────────
val Color_Separator    = Color(0xFF1C1F2B)
val Color_SeparatorMid = Color(0xFF252838)

// ─── Surface Variants ────────────────────────────────────────────
val Color_SurfaceLight  = Color(0xFF1C1F2B)
val Color_HoverActive   = Color(0xFF1E2130)

// ─── Lite Mode (WhatsApp-inspired) ───────────────────────────────
val Color_LiteChatBg          = Color(0xFFEAE6DF)
val Color_LiteInputBg         = Color(0xFFF0F2F5)
val Color_LiteHeader          = Color(0xFF075E54)
val Color_LiteTextPrimary     = Color(0xFF111B21)
val Color_LiteTextSecondary   = Color(0xFF54656F)
val Color_LiteBubbleReceived  = Color(0xFFFFFFFF)
val Color_LiteBubbleSent      = Color(0xFFD9FDD3)
