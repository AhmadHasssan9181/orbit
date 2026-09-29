package ui.assets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import ui.theme.LocalActiveTheme
import ui.theme.Theme

/**
 * Chat wallpapers.
 *
 * Every variant used to be a saturated accent sitting behind every single
 * message in every room: a tiled field of outlined icons in electric blue,
 * mint, cyan, violet and pink, permanently on, because `OrbitChatDoodleBackground`
 * was called unconditionally from the message list. On a busy conversation the
 * pattern fought the text for every glyph on screen.
 *
 * Two changes: the strokes are desaturated to sit *behind* the content rather
 * than compete with it, and the default wallpaper no longer draws a pattern at
 * all — it is a flat, very slightly tinted canvas. The doodles remain available,
 * as a deliberate choice.
 */
enum class OrbitDoodleWallpaper(
    val id: String,
    val label: String,
    val background: Color,
    val stroke: Color,
    /** The default is a flat canvas: a pattern behind a wall of text is noise. */
    val patterned: Boolean = true
) {
    Plain("plain", "Plain", Color(0xFF14171D), Color(0xFF2E3542), patterned = false),

    Dark01("dark-01", "Slate", Color(0xFF14171D), Color(0xFF6C7BE0)),
    Dark02("dark-02", "Teal", Color(0xFF0F1512), Color(0xFF4FA3A0)),
    Dark03("dark-03", "Steel", Color(0xFF101519), Color(0xFF5B8FC7)),
    Dark04("dark-04", "Moss", Color(0xFF141A17), Color(0xFF7BA05B)),

    Light01("light-01", "Sand", Color(0xFFFAFBFC), Color(0xFFC08A5E)),
    // Not patterned: this is the Light theme's auto default, so a pattern here
    // was drawn behind every message of every light-theme room — contradicting
    // the "default draws no pattern" rule this file states at the top.
    Light02("light-02", "Paper", Color(0xFFFFFFFF), Color(0xFFB4BCC9), patterned = false),
    Light03("light-03", "Clay", Color(0xFFF7F8FA), Color(0xFFBF6A60)),
    Light04("light-04", "Ice", Color(0xFFFAFBFC), Color(0xFF5C87C4)),

    Grey01("grey-01", "Graphite", Color(0xFF1B1F27), Color(0xFF5B6472)),
    Grey02("grey-02", "Slate Grey", Color(0xFF181C22), Color(0xFF697180)),
    Grey03("grey-03", "Ash", Color(0xFF1F242C), Color(0xFF8B93A3)),
    Grey04("grey-04", "Concrete", Color(0xFF181C22), Color(0xFF565E6D)),

    Amoled01("amoled-01", "OLED Slate", Color(0xFF000000), Color(0xFF3A4250), patterned = false),
    Amoled02("amoled-02", "OLED Moss", Color(0xFF000000), Color(0xFF2F3A32)),
    Amoled03("amoled-03", "OLED Iris", Color(0xFF000000), Color(0xFF3A3660)),
    Amoled04("amoled-04", "OLED Warm", Color(0xFF000000), Color(0xFF4A3A2A)),

    Mixed01("mixed-01", "Dusk", Color(0xFF16171F), Color(0xFF6C7BE0)),
    Mixed02("mixed-02", "Pine", Color(0xFF12161A), Color(0xFF4FA3A0)),
    Mixed03("mixed-03", "Marine", Color(0xFF12161C), Color(0xFF5B8FC7)),
    Mixed04("mixed-04", "Rosewood", Color(0xFF1A1418), Color(0xFFAE7099))
}

val OrbitDoodleWallpaperOptions: List<String> = listOf("Auto") + OrbitDoodleWallpaper.entries.map { it.label }

/** True when this wallpaper's canvas is light, so it only suits a light theme. */
fun orbitDoodleWallpaperIsLight(id: String): Boolean =
    OrbitDoodleWallpaper.entries.firstOrNull { it.id == id }?.background?.let {
        (0.299f * it.red + 0.587f * it.green + 0.114f * it.blue) > 0.5f
    } ?: false

fun orbitDoodleWallpaperLabelForId(id: String): String {
    if (id == "auto") return "Auto"
    return OrbitDoodleWallpaper.entries.firstOrNull { it.id == id }?.label ?: "Auto"
}

fun orbitDoodleWallpaperIdForLabel(label: String): String {
    if (label == "Auto") return "auto"
    return OrbitDoodleWallpaper.entries.firstOrNull { it.label == label }?.id ?: "auto"
}

@Composable
private fun resolvedOrbitDoodleWallpaper(selectedId: String): OrbitDoodleWallpaper {
    OrbitDoodleWallpaper.entries.firstOrNull { it.id == selectedId }?.let { return it }
    // "Auto" and anything unrecognised: match the active theme's canvas, flat.
    return when (LocalActiveTheme.current) {
        Theme.Light -> OrbitDoodleWallpaper.Light02
        Theme.Amoled -> OrbitDoodleWallpaper.Amoled01
        else -> OrbitDoodleWallpaper.Plain
    }
}

@Composable
fun OrbitChatDoodleBackground(
    selectedId: String,
    modifier: Modifier = Modifier,
    intensity: Float = 1f
) {
    val wallpaper = resolvedOrbitDoodleWallpaper(selectedId)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(wallpaper.background)
    ) {
        if (!wallpaper.patterned) return@Box
        Canvas(Modifier.fillMaxSize()) {
            // 0.11 instead of 0.24: a texture, not a pattern you read.
            drawDoodleWallpaper(wallpaper.stroke.copy(alpha = 0.11f * intensity))
        }
    }
}

private fun DrawScope.drawDoodleWallpaper(tint: Color) {
    val tile = 190.dp.toPx()
    val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
    var row = 0
    var y = -tile * 0.30f
    while (y < size.height + tile) {
        var col = 0
        var x = -tile * 0.22f + if (row % 2 == 0) 0f else tile * 0.46f
        while (x < size.width + tile) {
            val center = Offset(x + tile * 0.50f, y + tile * 0.50f)
            val scale = tile / 190f
            rotate((row * 9 + col * 13) % 28 - 14f, center) {
                when ((row * 5 + col * 7) % 9) {
                    0 -> drawDoodleChat(center, scale, tint, stroke)
                    1 -> drawDoodleOrbit(center, scale, tint, stroke)
                    2 -> drawDoodlePin(center, scale, tint, stroke)
                    3 -> drawDoodleMic(center, scale, tint, stroke)
                    4 -> drawDoodleImage(center, scale, tint, stroke)
                    5 -> drawDoodlePhone(center, scale, tint, stroke)
                    6 -> drawDoodleStar(center, scale, tint, stroke)
                    7 -> drawDoodleGift(center, scale, tint, stroke)
                    else -> drawDoodleCommunity(center, scale, tint, stroke)
                }
            }
            x += tile
            col++
        }
        y += tile
        row++
    }
}

private fun DrawScope.drawDoodleChat(center: Offset, scale: Float, tint: Color, stroke: Stroke) {
    val w = 42f * scale
    val h = 30f * scale
    drawRoundRect(
        color = tint,
        topLeft = Offset(center.x - w / 2f, center.y - h / 2f),
        size = Size(w, h),
        cornerRadius = CornerRadius(10f * scale, 10f * scale),
        style = stroke
    )
    drawDoodleLine(tint, Offset(center.x - 7f * scale, center.y + h / 2f), Offset(center.x - 18f * scale, center.y + 19f * scale), stroke)
    drawDoodleLine(tint, Offset(center.x - 10f * scale, center.y - 2f * scale), Offset(center.x + 11f * scale, center.y - 2f * scale), stroke)
    drawDoodleLine(tint, Offset(center.x - 10f * scale, center.y + 7f * scale), Offset(center.x + 5f * scale, center.y + 7f * scale), stroke)
}

private fun DrawScope.drawDoodleOrbit(center: Offset, scale: Float, tint: Color, stroke: Stroke) {
    rotate(-18f, center) {
        drawOval(
            color = tint,
            topLeft = Offset(center.x - 31f * scale, center.y - 11f * scale),
            size = Size(62f * scale, 22f * scale),
            style = stroke
        )
    }
    drawCircle(tint, radius = 12f * scale, center = center, style = stroke)
    drawCircle(tint, radius = 4f * scale, center = Offset(center.x + 25f * scale, center.y - 10f * scale))
}

private fun DrawScope.drawDoodlePin(center: Offset, scale: Float, tint: Color, stroke: Stroke) {
    val path = Path().apply {
        moveTo(center.x - 2f * scale, center.y - 24f * scale)
        lineTo(center.x + 18f * scale, center.y - 7f * scale)
        lineTo(center.x + 8f * scale, center.y + 3f * scale)
        lineTo(center.x + 13f * scale, center.y + 20f * scale)
        lineTo(center.x - 3f * scale, center.y + 8f * scale)
        lineTo(center.x - 16f * scale, center.y + 24f * scale)
        lineTo(center.x - 19f * scale, center.y + 21f * scale)
        lineTo(center.x - 7f * scale, center.y + 5f * scale)
        lineTo(center.x - 21f * scale, center.y - 5f * scale)
        close()
    }
    drawPath(path, tint, style = stroke)
}

private fun DrawScope.drawDoodleMic(center: Offset, scale: Float, tint: Color, stroke: Stroke) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(center.x - 9f * scale, center.y - 25f * scale),
        size = Size(18f * scale, 34f * scale),
        cornerRadius = CornerRadius(10f * scale, 10f * scale),
        style = stroke
    )
    drawDoodleLine(tint, Offset(center.x, center.y + 10f * scale), Offset(center.x, center.y + 27f * scale), stroke)
    drawDoodleLine(tint, Offset(center.x - 15f * scale, center.y + 27f * scale), Offset(center.x + 15f * scale, center.y + 27f * scale), stroke)
}

private fun DrawScope.drawDoodleImage(center: Offset, scale: Float, tint: Color, stroke: Stroke) {
    val w = 46f * scale
    val h = 34f * scale
    drawRoundRect(
        color = tint,
        topLeft = Offset(center.x - w / 2f, center.y - h / 2f),
        size = Size(w, h),
        cornerRadius = CornerRadius(8f * scale, 8f * scale),
        style = stroke
    )
    drawCircle(tint, radius = 4f * scale, center = Offset(center.x - 12f * scale, center.y - 7f * scale), style = stroke)
    drawDoodleLine(tint, Offset(center.x - 18f * scale, center.y + 11f * scale), Offset(center.x - 5f * scale, center.y - 2f * scale), stroke)
    drawDoodleLine(tint, Offset(center.x - 5f * scale, center.y - 2f * scale), Offset(center.x + 7f * scale, center.y + 10f * scale), stroke)
    drawDoodleLine(tint, Offset(center.x + 4f * scale, center.y + 8f * scale), Offset(center.x + 18f * scale, center.y - 6f * scale), stroke)
}

private fun DrawScope.drawDoodlePhone(center: Offset, scale: Float, tint: Color, stroke: Stroke) {
    val path = Path().apply {
        moveTo(center.x - 16f * scale, center.y - 22f * scale)
        quadraticTo(center.x - 2f * scale, center.y + 18f * scale, center.x + 21f * scale, center.y + 19f * scale)
    }
    drawPath(path, tint, style = stroke)
    drawDoodleLine(tint, Offset(center.x - 17f * scale, center.y - 22f * scale), Offset(center.x - 7f * scale, center.y - 25f * scale), stroke)
    drawDoodleLine(tint, Offset(center.x + 16f * scale, center.y + 15f * scale), Offset(center.x + 23f * scale, center.y + 7f * scale), stroke)
}

private fun DrawScope.drawDoodleStar(center: Offset, scale: Float, tint: Color, stroke: Stroke) {
    val path = Path().apply {
        moveTo(center.x, center.y - 24f * scale)
        lineTo(center.x + 6f * scale, center.y - 6f * scale)
        lineTo(center.x + 24f * scale, center.y)
        lineTo(center.x + 6f * scale, center.y + 6f * scale)
        lineTo(center.x, center.y + 24f * scale)
        lineTo(center.x - 6f * scale, center.y + 6f * scale)
        lineTo(center.x - 24f * scale, center.y)
        lineTo(center.x - 6f * scale, center.y - 6f * scale)
        close()
    }
    drawPath(path, tint, style = stroke)
}

private fun DrawScope.drawDoodleGift(center: Offset, scale: Float, tint: Color, stroke: Stroke) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(center.x - 23f * scale, center.y - 4f * scale),
        size = Size(46f * scale, 28f * scale),
        cornerRadius = CornerRadius(7f * scale, 7f * scale),
        style = stroke
    )
    drawRoundRect(
        color = tint,
        topLeft = Offset(center.x - 26f * scale, center.y - 15f * scale),
        size = Size(52f * scale, 14f * scale),
        cornerRadius = CornerRadius(6f * scale, 6f * scale),
        style = stroke
    )
    drawDoodleLine(tint, Offset(center.x, center.y - 15f * scale), Offset(center.x, center.y + 24f * scale), stroke)
    drawCircle(tint, radius = 7f * scale, center = Offset(center.x - 8f * scale, center.y - 22f * scale), style = stroke)
    drawCircle(tint, radius = 7f * scale, center = Offset(center.x + 8f * scale, center.y - 22f * scale), style = stroke)
}

private fun DrawScope.drawDoodleCommunity(center: Offset, scale: Float, tint: Color, stroke: Stroke) {
    drawCircle(tint, radius = 8f * scale, center = Offset(center.x, center.y - 14f * scale), style = stroke)
    drawCircle(tint, radius = 6f * scale, center = Offset(center.x - 18f * scale, center.y - 8f * scale), style = stroke)
    drawCircle(tint, radius = 6f * scale, center = Offset(center.x + 18f * scale, center.y - 8f * scale), style = stroke)
    drawRoundRect(
        color = tint,
        topLeft = Offset(center.x - 25f * scale, center.y + 5f * scale),
        size = Size(50f * scale, 23f * scale),
        cornerRadius = CornerRadius(12f * scale, 12f * scale),
        style = stroke
    )
}

private fun DrawScope.drawDoodleLine(color: Color, start: Offset, end: Offset, stroke: Stroke) {
    drawLine(color = color, start = start, end = end, strokeWidth = stroke.width, cap = StrokeCap.Round)
}