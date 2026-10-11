package ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Orbit palette — Discord's dark theme, as a ramp.
 *
 * These values are pinned to Discord's real dark-theme greys *on purpose*.
 * The ramps previously ran on a single blue-black hue (~222°, e.g. #14171D),
 * which looked handsome in isolation but made the product read as a different
 * app from the one it was imitating: side by side, a ~222° cast against
 * Discord's essentially neutral grey is obvious at a glance. The hue cast has
 * therefore been removed rather than dialled down, and the accent is now
 * Discord blurple (#5865F2).
 *
 * Everything below is a *ramp*, not a one-off, so the names other files
 * reference still resolve — only the underlying values move. Exactly one
 * accent carries intent.
 */

// ─── Dark neutrals ───────────────────────────────────────────────────────
// Pinned to Discord's actual surface colours.
val Ink950 = Color(0xFF111214)   // Discord "modal/dropdown" — deepest surface
val Ink900 = Color(0xFF1E1F22)   // Discord server rail
val Ink850 = Color(0xFF2B2D31)   // Discord channel sidebar
val Ink800 = Color(0xFF313338)   // Discord chat background  <- the app's main surface
val Ink780 = Color(0xFF383A40)   // Discord chat input
val Ink750 = Color(0xFF2E3035)   // Discord message hover
val Ink700 = Color(0xFF404249)   // Discord selected channel row
val Ink650 = Color(0xFF4E5058)   // Discord modal border / strong divider
val Ink600 = Color(0xFF5C5E66)
val Ink550 = Color(0xFF80848E)   // Discord offline status grey

// ─── Light neutrals ──────────────────────────────────────────────────────
val Paper0 = Color(0xFFFFFFFF)
val Paper50 = Color(0xFFFAFBFC)
val Paper100 = Color(0xFFF4F6F9)
val Paper150 = Color(0xFFEDF0F5)
val Paper200 = Color(0xFFE3E7EE)
val Paper300 = Color(0xFFD2D8E2)
val Paper400 = Color(0xFFB4BCC9)

// ─── Text ────────────────────────────────────────────────────────────────
// DarkFgDefault is Discord's message-body grey (#dbdee1). Message text binds to
// Text_Secondary, so getting this one value right is what makes chat read right.
val DarkFgStrong = Color(0xFFF2F3F5)
val DarkFgDefault = Color(0xFFDBDEE1)
val DarkFgMuted = Color(0xFF949BA4)
val DarkFgSubtle = Color(0xFF6D6F78)

val LightFgStrong = Color(0xFF13161C)
val LightFgDefault = Color(0xFF3B4351)
val LightFgMuted = Color(0xFF697180)
val LightFgSubtle = Color(0xFF98A0AE)

// ─── Accent · Iris ───────────────────────────────────────────────────────
// Still called Iris, but now Discord blurple (#5865F2). Ramp ordering is
// unchanged: 600 is the darker/hover step, 500 is the base.
val Iris600 = Color(0xFF4752C4)   // Discord blurple hover/pressed
val Iris500 = Color(0xFF5865F2)   // Discord blurple
val Iris400 = Color(0xFF7C86F5)
val Iris200 = Color(0xFFC0C5FC)
val Iris900 = Color(0xFF23244A)
val Iris800 = Color(0xFF32357A)
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
val PresenceOnline = Color(0xFF23A55A)
val PresenceIdle = Color(0xFFF0B232)
val PresenceDnd = Color(0xFFF23F43)
val PresenceOffline = Color(0xFF80848E)

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

// ─── Pastel avatar seeds (for DM mascot avatars) ──────────────────────────
// Bright, cheerful pastel pairs: (highlight, subtle shade)
val PastelAvatarSeeds: List<Pair<Color, Color>> = listOf(
    Color(0xFFFFD1DC) to Color(0xFFFFAAA6), // Soft Strawberry Rose
    Color(0xFFD8F3DC) to Color(0xFFB7E4C7), // Fresh Mint
    Color(0xFFE8D7FF) to Color(0xFFD0B3FF), // Lavender Mist
    Color(0xFFFFE5D9) to Color(0xFFFFCAD4), // Warm Peach
    Color(0xFFCCE7FF) to Color(0xFFA6D4FF), // Soft Sky Blue
    Color(0xFFFFF3BF) to Color(0xFFFFEE99), // Buttercream
    Color(0xFFFFD6CC) to Color(0xFFFFB8A8), // Soft Coral
    Color(0xFFC4F1F9) to Color(0xFFA0E7F5), // Seafoam Aqua
    Color(0xFFDCE2FF) to Color(0xFFBAC7FF), // Periwinkle
    Color(0xFFE2F0D9) to Color(0xFFC5E0B4), // Pistachio
    Color(0xFFFFD6E8) to Color(0xFFFFB3D9), // Bubblegum
    Color(0xFFD0F0FD) to Color(0xFFAEE4FB), // Glacier Blue
    Color(0xFFFFDFBA) to Color(0xFFFFC988), // Apricot
    Color(0xFFF3D5F5) to Color(0xFFE5B8E8), // Orchid
    Color(0xFFD7F8E8) to Color(0xFFB3EDCE), // Spring Meadow
    Color(0xFFFFF9DB) to Color(0xFFFFF3B0)  // Vanilla Butter
)

fun pastelAvatarSeedColors(seed: String): Pair<Color, Color> =
    PastelAvatarSeeds[(seed.hashCode() and Int.MAX_VALUE) % PastelAvatarSeeds.size]

// ─── Pastel server tiles ─────────────────────────────────────────────────
// The fills behind server initials in the rail.
//
// These replace a hash-to-ARGB scheme that took three bytes straight off
// `String.hashCode()`, so a server's colour was effectively random at full
// saturation: a rail of them read as confetti, and two adjacent servers could
// land on near-identical or clashing hues. Fixed to a curated set so every
// tile is a deliberate matte pastel.
//
// Deliberately LIGHT and low-chroma, which inverts the usual relationship to the
// text: [pastelServerInk] is near-black, not white. A white initial on these
// fills lands around 1.4:1 and is unreadable.
//
// PastelAvatarSeeds above is the opposite choice on purpose -- those back
// illustrated mascot avatars, where a light fill carries artwork rather than
// glyphs. Do not merge the two lists.
val PastelServerSeeds: List<Color> = listOf(
    Color(0xFFE8C9A8), // Sand
    Color(0xFFB9DCA6), // Sage
    Color(0xFFAFD0EC), // Sky
    Color(0xFFD4C2EE), // Lilac
    Color(0xFFF2BDC4), // Rose
    Color(0xFFA3DCD6), // Teal
    Color(0xFFC4C9EC), // Periwinkle
    Color(0xFFE8D9A8), // Wheat
    Color(0xFFE9C4D8), // Mauve
    Color(0xFFEBC4A4), // Clay
    Color(0xFFB2DCC0), // Seafoam
    Color(0xFFD3C9DE), // Heather
)

/**
 * Glyph colour for a server tile.
 *
 * Near-black rather than white. Every fill in [PastelServerSeeds] is light
 * enough that dark ink clears 9.9:1 against the worst of them, while white
 * would sit at roughly 1.4:1 and simply vanish.
 */
val pastelServerInk: Color = Color(0xFF2B2A33)

/**
 * The pastel assigned to a server, stable for a given id.
 *
 * Hashed off [seed] so a server keeps its colour across restarts and across
 * every surface that renders it. `and Int.MAX_VALUE` guards the negative half
 * of `hashCode()`: without it a server could index negative and throw.
 */
fun pastelServerColor(seed: String): Color =
    PastelServerSeeds[(seed.hashCode() and Int.MAX_VALUE) % PastelServerSeeds.size]

