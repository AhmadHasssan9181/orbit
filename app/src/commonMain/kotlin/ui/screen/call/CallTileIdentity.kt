package ui.screen.call

/**
 * Fallback tile colour for a participant with no avatar.
 *
 * Lives here, in its own file, because both call surfaces need it and the first
 * attempt was to hard-code a colour at each site: `LiveKitGroupCallSurface` used
 * this palette, and `CallScreen` used a single red constant. That is exactly the
 * shape of bug this codebase has been bitten by repeatedly -- one instance fixed,
 * the identical one left sitting next to it. There is one palette now, and both
 * surfaces pick from it, so a person looks the same colour in a 1:1 and in a
 * group.
 */
private val tileAvatarPalette = listOf(
    0xFF7B74E8, 0xFF23A55A, 0xFFEB459E, 0xFFFAA61A, 0xFF00A8FC, 0xFFD65A54
).map { it.toInt() }

/**
 * A stable colour for [identity], so the same person keeps the same colour across
 * tiles and across calls rather than being reassigned each time the list is built.
 *
 * The `+ size` before the modulo is not decoration: `String.hashCode()` is free to
 * return `Int.MIN_VALUE`, and `MIN_VALUE % 6` is negative, which would index the
 * palette out of bounds and throw while a call was rendering.
 */
internal fun identityColor(identity: String?): Int =
    tileAvatarPalette[((identity ?: "").hashCode() % tileAvatarPalette.size + tileAvatarPalette.size) % tileAvatarPalette.size]