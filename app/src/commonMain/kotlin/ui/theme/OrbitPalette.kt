package ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Orbit palette — "Graphite & Iris".
 *
 * The app used to lean on Discord-flavoured near-black blues and hard-coded
 * status reds/greens scattered across ~40 files. That read as neon, and every
 * screen improvised its own contrast, so the product never felt like one thing.
 *
 * Everything below is a *ramp*, not a one-off: neutrals share a single hue
 * (~222°) and a deliberately low chroma, so large surfaces sit next to each
 * other without vibrating. Exactly one accent carries intent.
 */

// ─── Dark neutrals ───────────────────────────────────────────────────────
val Ink950 = Color(0xFF08090C)
val Ink900 = Color(0xFF0B0D11)
val Ink850 = Color(0xFF101318)
val Ink800 = Color(0xFF14171D)
val Ink780 = Color(0xFF181C22)
val Ink750 = Color(0xFF1B1F27)
val Ink700 = Color(0xFF1F242C)
val Ink650 = Color(0xFF262C36)
val Ink600 = Color(0xFF2E3542)
val Ink550 = Color(0xFF3A4250)

// ─── Light neutrals ──────────────────────────────────────────────────────
val Paper0 = Color(0xFFFFFFFF)
val Paper50 = Color(0xFFFAFBFC)
val Paper100 = Color(0xFFF4F6F9)
val Paper150 = Color(0xFFEDF0F5)
val Paper200 = Color(0xFFE3E7EE)
val Paper300 = Color(0xFFD2D8E2)
val Paper400 = Color(0xFFB4BCC9)

// ─── Text ────────────────────────────────────────────────────────────────
val DarkFgStrong = Color(0xFFE9EBF1)
val DarkFgDefault = Color(0xFFC0C6D2)
val DarkFgMuted = Color(0xFF8B93A3)
val DarkFgSubtle = Color(0xFF636B7B)

val LightFgStrong = Color(0xFF13161C)
val LightFgDefault = Color(0xFF3B4351)
val LightFgMuted = Color(0xFF697180)
val LightFgSubtle = Color(0xFF98A0AE)

// ─── Accent · Iris ───────────────────────────────────────────────────────
val Iris600 = Color(0xFF6E67DE)
val Iris500 = Color(0xFF7B74E8)
val Iris400 = Color(0xFF928CF0)
val Iris200 = Color(0xFFC9C5FA)
val Iris900 = Color(0xFF1F1D45)
val Iris800 = Color(0xFF2B2858)
val Iris050 = Color(0xFFEDEBFD)

// ─── Semantic ────────────────────────────────────────────────────────────
val Sage600 = Color(0xFF3E9B72)
val Sage500 = Color(0xFF49AE81)
val Sage900 = Color(0xFF11291F)
val Sage050 = Color(0xFFE6F5ED)

val Amber600 = Color(0xFFBE8F31)
val Amber500 = Color(0xFFD2A03C)
val Amber900 = Color(0xFF2C2210)
val Amber050 = Color(0xFFFBF2DF)

val Rust600 = Color(0xFFC4514B)
val Rust500 = Color(0xFFD65A54)
val Rust900 = Color(0xFF2E1513)
val Rust050 = Color(0xFFFBEAE9)

val Steel500 = Color(0xFF5B8FC7)
val Steel900 = Color(0xFF11202F)
val Steel050 = Color(0xFFE8F1FA)

// ─── Presence ────────────────────────────────────────────────────────────
val PresenceOnline = Color(0xFF49AE81)
val PresenceIdle = Color(0xFFD2A03C)
val PresenceDnd = Color(0xFFD65A54)
val PresenceOffline = Color(0xFF565E6D)

// ─── Avatar seeds ────────────────────────────────────────────────────────
// Muted jewel tones. Saturated primaries made generated avatars shout, and a
// wall of them fought the accent for attention.
val AvatarSeeds = listOf(
    Color(0xFF6C7BE0),
    Color(0xFF4FA3A0),
    Color(0xFFC08A5E),
    Color(0xFFAE7099),
    Color(0xFF7BA05B),
    Color(0xFFBF6A60),
    Color(0xFF5C87C4),
    Color(0xFF9A7BC4)
)

fun avatarSeedColor(seed: String): Color =
    AvatarSeeds[(seed.hashCode() and Int.MAX_VALUE) % AvatarSeeds.size]
