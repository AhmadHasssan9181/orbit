package ui.screen.call

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ui.theme.CosmicTokens
import ui.theme.OrbitConceptDark
import ui.theme.OrbitConceptLight
import ui.theme.OrbitConceptStatus
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.jetbrains.skia.Bitmap

/**
 * The call surface's values from the Orbit concept, in one place.
 *
 * `ui/theme/OrbitConcept.kt` already carries everything the concept's `:root`
 * block declares as a `var(--…)`, and the tiles, the dock and the voice panel
 * read those from it. What is left is every *literal* in
 * `src/index.css` — the scrims behind overlays that sit on video, the speaker
 * glow, the hover step on each pill. Those cannot be tokens without editing
 * `CosmicTokens` in `Theme.kt`, so each one lives here instead, named after the
 * rule it came from.
 *
 * The same reasoning applies to the few *geometry* values Orbit's own scale
 * does not contain: `.participant-card` is 12px, and `OrbitRadius` stops at 10
 * and 14. Those are literal too, and are grouped at the bottom.
 */

/**
 * True while the active scheme is one of the dark ones.
 *
 * `CosmicTokens` is chosen in [ui.theme.rememberCosmicTokens] by exactly this
 * test, so asking the same question here keeps the concept surfaces agreeing
 * with the token set instead of guessing from a colour that only happens to be
 * dark today.
 */
private fun isDarkScheme(t: CosmicTokens): Boolean = t.Bg.luminance() < 0.5f

// ── Surfaces ────────────────────────────────────────────────────────────────

/**
 * `.participant-card` / `.participant-card-2col` background: `--bg-call-card`.
 *
 * When [sourceColor] is provided (e.g. sampled from the participant's avatar),
 * it is transformed into a muted pastel surface matching Orbit's tonal aesthetic.
 */
internal fun callCardSurface(t: CosmicTokens, sourceColor: Color? = null): Color {
    val isDark = isDarkScheme(t)
    return if (sourceColor != null) {
        toMutedPastelCardBg(sourceColor, isDark)
    } else {
        if (isDark) OrbitConceptDark.callCard else OrbitConceptLight.sidebar
    }
}

internal fun toMutedPastelCardBg(sourceColor: Color, isDark: Boolean): Color {
    val oklab = ui.theme.colorToOklab(sourceColor)
    val hue = atan2(oklab[2], oklab[1])
    val rawChroma = hypot(oklab[1], oklab[2])

    return if (isDark) {
        val cardL = 0.23f
        val cardChroma = if (rawChroma < 0.02f) {
            0f
        } else {
            (rawChroma * 0.45f).coerceIn(0.030f, 0.065f)
        }
        ui.theme.oklabToColor(cardL, cardChroma * cos(hue), cardChroma * sin(hue))
    } else {
        val cardL = 0.93f
        val cardChroma = if (rawChroma < 0.02f) {
            0f
        } else {
            (rawChroma * 0.45f).coerceIn(0.030f, 0.065f)
        }
        ui.theme.oklabToColor(cardL, cardChroma * cos(hue), cardChroma * sin(hue))
    }
}

internal fun sampleAvatarPerimeterColor(bitmap: Bitmap): Color? {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return null

    val insets = listOf(0.06f, 0.12f, 0.18f)
    var selectedSamples: List<Color> = emptyList()

    for (inset in insets) {
        val ix = (w * inset).toInt().coerceIn(0, w - 1)
        val iy = (h * inset).toInt().coerceIn(0, h - 1)
        val mx = w / 2
        val my = h / 2
        val rx = (w - 1 - ix).coerceIn(0, w - 1)
        val by = (h - 1 - iy).coerceIn(0, h - 1)

        val checkPoints = listOf(
            ix to iy, rx to iy, ix to by, rx to by,
            mx to iy, mx to by, ix to my, rx to my,
            (w * 0.25f).toInt() to iy, (w * 0.75f).toInt() to iy,
            (w * 0.25f).toInt() to by, (w * 0.75f).toInt() to by
        )

        val opaque = mutableListOf<Color>()
        for ((px, py) in checkPoints) {
            val argb = bitmap.getColor(px, py)
            val a = (argb ushr 24) and 0xFF
            if (a >= 100) {
                val r = ((argb ushr 16) and 0xFF) / 255f
                val g = ((argb ushr 8) and 0xFF) / 255f
                val b = (argb and 0xFF) / 255f
                opaque.add(Color(r, g, b, 1f))
            }
        }

        if (opaque.size >= 4) {
            selectedSamples = opaque
            break
        }
    }

    if (selectedSamples.isEmpty()) return null

    var totalR = 0f
    var totalG = 0f
    var totalB = 0f
    for (c in selectedSamples) {
        totalR += c.red
        totalG += c.green
        totalB += c.blue
    }
    return Color(
        red = totalR / selectedSamples.size,
        green = totalG / selectedSamples.size,
        blue = totalB / selectedSamples.size,
        alpha = 1f
    )
}

/**
 * `.call-dock-pill-bar` background: rgba(24, 25, 28, 0.92) in the dark themes
 * (that is `--bg-call-card` at 92%), rgba(255, 255, 255, 0.96) in light.
 */
internal fun callDockSurface(t: CosmicTokens): Color =
    if (isDarkScheme(t)) OrbitConceptDark.callCard.copy(alpha = 0.92f)
    else OrbitConceptLight.floating.copy(alpha = 0.96f)

/**
 * `.voice-connected-bar` background: `--bg-secondary`.
 *
 * A real surface rather than a scrim — it sits on the channel sidebar, not on a
 * video frame — so it tracks the theme, unlike the pill scrims below.
 */
internal fun callVoicePanelSurface(t: CosmicTokens): Color =
    if (isDarkScheme(t)) OrbitConceptDark.secondary else OrbitConceptLight.secondary

/**
 * `.stream-screen-canvas { background-color: #000000 }`, and
 * `.voice-room-maximized { background-color: #000000 }`.
 *
 * Literal black in the light theme too: the canvas behind a screen share is the
 * letterbox, and a letterbox is black whatever the app's theme is.
 */
internal val callStageBlack = Color(0xFF000000)

// ── Scrims behind things that sit on video ──────────────────────────────────

/**
 * `.participant-badge-name` and `.icon-pill` background: rgba(0, 0, 0, 0.7).
 *
 * The name plate and the muted/LIVE badges are drawn over a camera frame, so
 * this is a scrim and not a surface: the same value in every theme, because
 * there is no theme behind it to blend with.
 */
internal val CallOverlayScrim = Color(0xFF000000).copy(alpha = 0.7f)

/**
 * The muted badge on a tile, from the inline style in `VoiceRoom.jsx`:
 * `backgroundColor: 'rgba(242, 63, 67, 0.85)'`.
 */
internal val CallMutedBadgeScrim = OrbitConceptStatus.dnd.copy(alpha = 0.85f)

/** `.stream-action-circle-btn { background-color: rgba(0, 0, 0, 0.7) }`. */
internal val CallActionScrim = Color(0xFF000000).copy(alpha = 0.7f)

/** `.stream-action-circle-btn:hover { background-color: rgba(255, 255, 255, 0.15) }`. */
internal val CallActionScrimHover = Color(0xFFFFFFFF).copy(alpha = 0.15f)

/**
 * `.maximize-btn-highlight { background-color: rgba(43, 45, 49, 0.85) }`.
 *
 * The maximise button of the pair gets a visible plate at rest, which is how
 * the concept marks it as the primary action; its `:hover` goes to
 * `var(--brand-blurple)`.
 */
internal val CallMaximizePlate = OrbitConceptDark.sidebar.copy(alpha = 0.85f)

/** `.stream-action-circle-btn { border: 1px solid rgba(255, 255, 255, 0.1) }`. */
internal val CallActionRing = Color(0xFFFFFFFF).copy(alpha = 0.10f)

// ── Speaker treatment ───────────────────────────────────────────────────────

/**
 * `.participant-card.speaking { box-shadow: 0 0 20px rgba(35, 165, 90, 0.35) }`.
 * The ring colour itself is `--status-online`.
 */
internal val CallSpeakingGlow = OrbitConceptStatus.online.copy(alpha = 0.35f)

/** `.speaking-wave-ring { border: 3px solid var(--status-online) }`. */
internal val CallWaveRingWidth: Dp = 3.dp

/** `.speaking-wave-ring` is inset by -8px on every side of the avatar. */
internal val CallWaveRingInset: Dp = 8.dp

/** `@keyframes pulseSpeaking` — 1.4s, cubic-bezier(0.2, 0.8, 0.2, 1). */
internal const val CALL_WAVE_PULSE_MS = 1400

/** `@keyframes pulseSpeaking`: 0.95 -> 1.15 and back. */
internal const val CALL_WAVE_SCALE_MIN = 0.95f
internal const val CALL_WAVE_SCALE_MAX = 1.15f

// ── Screen-share tile ───────────────────────────────────────────────────────

/**
 * `.stream-preview-card { border: 2px solid rgba(242, 63, 67, 0.6) }`.
 *
 * A share tile is ringed in red whether or not anybody is hovering it: in a grid
 * of faces, a red ring is the only thing that says "this tile is a screen".
 */
internal val CallShareRing = OrbitConceptStatus.dnd.copy(alpha = 0.6f)

/** `.stream-preview-card:hover { box-shadow: 0 0 24px rgba(242, 63, 67, 0.4) }`. */
internal val CallShareRingHover = OrbitConceptStatus.dnd.copy(alpha = 0.4f)

// ── Call dock pills ─────────────────────────────────────────────────────────

/**
 * `.call-pill-btn { background-color: #2b2d31 }` — the dark theme's `--bg-sidebar`.
 * The light theme's override is #f2f3f5, which is its `--bg-sidebar` too.
 */
internal fun callPillRest(t: CosmicTokens): Color =
    if (isDarkScheme(t)) OrbitConceptDark.sidebar else OrbitConceptLight.sidebar

/**
 * `.call-pill-btn:hover { background-color: #383a40 }` — `--bg-input`. The light
 * override is #e3e5e8, which is the light `--bg-servers`.
 */
internal fun callPillHover(t: CosmicTokens): Color =
    if (isDarkScheme(t)) OrbitConceptDark.input else OrbitConceptLight.servers

/** `.call-pill-btn.danger { background-color: #f23f43 }` == `--status-dnd`. */
internal val CallPillDanger = OrbitConceptStatus.dnd

/** `.call-pill-btn.danger:hover { background-color: #d83a3e }`. */
internal val CallPillDangerHover = Color(0xFFD83A3E)

/** `.call-pill-btn.active-green { background-color: #23a55a }` == `--status-online`. */
internal val CallPillActiveGreen = OrbitConceptStatus.online

/** `.call-pill-btn.active-green:hover { background-color: #1f9450 }`. */
internal val CallPillActiveGreenHover = Color(0xFF1F9450)

/** `.call-pill-btn:hover { transform: translateY(-2px) }`, as `.dock-btn:hover`. */
internal val CallPillHoverLift: Dp = 2.dp

// ── Sidebar voice panel ─────────────────────────────────────────────────────

/** `.voice-quick-disconnect:hover { background-color: rgba(242, 63, 67, 0.15) }`. */
internal val CallDisconnectHover = OrbitConceptStatus.dnd.copy(alpha = 0.15f)

/** `.voice-tool-compact-btn { background-color: rgba(255, 255, 255, 0.05) }`. */
internal val CallVoiceToolRest = Color(0xFFFFFFFF).copy(alpha = 0.05f)

/** `.voice-tool-compact-btn:hover { background-color: rgba(255, 255, 255, 0.1) }`. */
internal val CallVoiceToolHover = Color(0xFFFFFFFF).copy(alpha = 0.10f)

/** `.voice-tool-compact-btn.active-danger { color: #f23f43 }`. */
internal val CallVoiceToolDanger = OrbitConceptStatus.dnd

// ── Header action buttons ───────────────────────────────────────────────────

/**
 * `.header-action-btn { color: var(--interactive-normal) }` — #b5bac1 in dark,
 * #4e5058 in light. `--interactive-normal` is a value of its own in the concept's
 * `:root` and happens to equal `--header-secondary`, so it is read as that.
 */
internal fun callInteractiveNormal(t: CosmicTokens): Color =
    if (isDarkScheme(t)) OrbitConceptDark.textSecondary else OrbitConceptLight.textSecondary

/**
 * `.header-action-btn:hover { color: var(--interactive-hover) }` — the same two
 * values as `--text-normal`.
 */
internal fun callInteractiveHover(t: CosmicTokens): Color =
    if (isDarkScheme(t)) OrbitConceptDark.textNormal else OrbitConceptLight.textNormal

/**
 * `.header-action-btn.active { color: var(--interactive-active) }` — #ffffff in
 * dark, which is one step brighter than `--header-primary` and the only
 * foreground in the concept's `:root` with no named equivalent.
 */
internal fun callInteractiveActive(t: CosmicTokens): Color =
    if (isDarkScheme(t)) Color(0xFFFFFFFF) else OrbitConceptLight.textPrimary

// ── Geometry the radius scale does not have ─────────────────────────────────

/**
 * `.participant-card` / `.participant-card-2col { border-radius: 12px }`.
 *
 * Literal, because `OrbitRadius` offers 10 and 14 and neither is the concept's
 * 12. A tile is the largest repeated surface on the call screen, so its corner
 * is the one people actually look at.
 */
internal val CallTileShape = RoundedCornerShape(24.dp)

/** `.call-dock-pill-bar { border-radius: 30px }`. */
internal val CallDockShape = RoundedCornerShape(30.dp)

/** `.participant-badge-name { border-radius: 6px }` — `OrbitRadius.sm`'s radius. */
internal val CallNamePillShape = RoundedCornerShape(6.dp)

/** `.icon-pill { border-radius: 4px }` — `OrbitRadius.xs`'s radius. */
internal val CallIconPillShape = RoundedCornerShape(4.dp)

/** `.call-pill-btn` is 44x44 with a 22px radius, i.e. exactly a circle. */
internal val CallPillSize: Dp = 44.dp

/** `.stream-action-circle-btn` is 40x40 with a 20px radius, i.e. a circle. */
internal val CallActionCircleSize: Dp = 40.dp

/** `.voice-tool-compact-btn` is 34x32 with a 4px radius. */
internal val CallVoiceToolWidth: Dp = 34.dp
internal val CallVoiceToolHeight: Dp = 32.dp

/** `.stream-bottom-right-actions` sits 24px off the bottom, 28px off the right. */
internal val CallStreamActionsBottom: Dp = 24.dp
internal val CallStreamActionsEnd: Dp = 28.dp

/**
 * `.stream-action-circle-btn:hover { transform: scale(1.08) }` — the one scale in
 * the call surface's CSS, and deliberately not shared with `.call-pill-btn`,
 * which lifts instead.
 */
internal const val CALL_ACTION_HOVER_SCALE = 1.08f

/**
 * Active speaker stroke color: a light shade of the selected theme color
 * on dark mode, and a dark shade on light mode.
 */
internal fun speakingStrokeColor(tokens: ui.theme.CosmicTokens): Color {
    val isDark = tokens.Bg.luminance() < 0.5f
    val accent = tokens.Accent
    return if (isDark) {
        androidx.compose.ui.graphics.lerp(accent, Color.White, 0.42f)
    } else {
        androidx.compose.ui.graphics.lerp(accent, Color.Black, 0.42f)
    }
}
