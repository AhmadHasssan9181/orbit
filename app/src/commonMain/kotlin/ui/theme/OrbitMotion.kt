package ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.IntOffset

/**
 * Motion is the single biggest lever on whether an app feels crafted or
 * assembled. Before this file every screen hand-rolled its own
 * `spring(StiffnessMediumLow)` or `tween(200)`, so the same interaction felt
 * different depending on which file you were looking at.
 *
 * Rules encoded here:
 *  1. Spatial changes (position, size, colour) use springs — they carry intent.
 *  2. Opacity changes use short tweens — springs on alpha look like a bug.
 *  3. Everything degrades to a near-instant snap when the user asks for
 *     reduced motion, instead of merely shortening it.
 */
object OrbitMotion {

    // ── Easings ───────────────────────────────────────────────────────────
    /** Material 3 "standard" — the workhorse. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Decelerate: entering the screen. */
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)
    /** Accelerate: leaving the screen. */
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    /** Emphasised, for a single deliberate moment. */
    val Emphasised: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    // ── Durations ─────────────────────────────────────────────────────────
    const val Instant = 90
    const val Fast = 150
    const val Medium = 240
    const val Slow = 380
    const val Lazy = 560

    // ── Springs ───────────────────────────────────────────────────────────
    /** Hover / press feedback. Tight, no overshoot. */
    val Snappy: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 700f
    )

    /** Revealing content (menus, panels, tooltips). A whisper of overshoot. */
    val Bouncy: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = 420f
    )

    /** Large moves — a pane sliding in, a window resizing. */
    val Gentle: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 260f
    )

    val SnappyColor: androidx.compose.animation.core.AnimationSpec<androidx.compose.ui.graphics.Color> =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 700f
        )

    val SnappyDp: AnimationSpec<androidx.compose.ui.unit.Dp> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 700f
    )

    val BouncyDp: AnimationSpec<androidx.compose.ui.unit.Dp> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = 420f
    )

    val GentleDp: AnimationSpec<androidx.compose.ui.unit.Dp> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 260f
    )

    val SnappyOffset: AnimationSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 700f
    )

    val GentleOffset: AnimationSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 260f
    )
}

// ── Reduce-motion aware spec builders ─────────────────────────────────────
// These read `LocalReduceMotion` so callers don't have to branch at every
// call site; passing `remember { false }` keeps them usable in non-composable
// contexts (e.g. remember{} initialisers that still need an AnimationSpec).

// The `rememberReducedMotion` / `motionScale` / `motionTween` / `motionTweenDp` /
// `motionTweenOffset` helpers that used to sit here had zero call sites: every
// animation site either uses an `OrbitMotion.*` spec directly or inlines
// `LocalReduceMotion.current`. They are gone rather than left as an invitation to
// adopt a second, subtly different reduced-motion convention.
