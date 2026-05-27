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
import ui.theme.LocalIsLiteMode
import ui.theme.Theme

enum class OrbitDoodleWallpaper(
    val id: String,
    val label: String,
    val background: Color,
    val stroke: Color
) {
    Dark01("dark-01", "Dark 01", Color(0xFF0B1220), Color(0xFF6EA8FF)),
    Dark02("dark-02", "Dark 02", Color(0xFF071A1A), Color(0xFF5DE4C7)),
    Dark03("dark-03", "Dark 03", Color(0xFF101827), Color(0xFF93C5FD)),
    Dark04("dark-04", "Dark 04", Color(0xFF102018), Color(0xFF7DD3A7)),
    Light01("light-01", "Light 01", Color(0xFFF5EFE3), Color(0xFF8A7A62)),
    Light02("light-02", "Light 02", Color(0xFFF6F7F3), Color(0xFF9CA3AF)),
    Light03("light-03", "Light 03", Color(0xFFEFE2CE), Color(0xFF9B7C54)),
    Light04("light-04", "Light 04", Color(0xFFEDF5F8), Color(0xFF7BA6B4)),
    Grey01("grey-01", "Grey 01", Color(0xFF8D948D), Color(0xFF374151)),
    Grey02("grey-02", "Grey 02", Color(0xFFC7C9C3), Color(0xFF6B7280)),
    Grey03("grey-03", "Grey 03", Color(0xFF51585D), Color(0xFFD1D5DB)),
    Grey04("grey-04", "Grey 04", Color(0xFFA7A89F), Color(0xFF4B5563)),
    Amoled01("amoled-01", "Amoled 01", Color(0xFF000000), Color(0xFF5B6472)),
    Amoled02("amoled-02", "Amoled 02", Color(0xFF020202), Color(0xFF2F855A)),
    Amoled03("amoled-03", "Amoled 03", Color(0xFF000000), Color(0xFF6D5DFB)),
    Amoled04("amoled-04", "Amoled 04", Color(0xFF020003), Color(0xFFA855F7)),
    Mixed01("mixed-01", "Mixed 01", Color(0xFF101729), Color(0xFF7C3AED)),
    Mixed02("mixed-02", "Mixed 02", Color(0xFF0B2A28), Color(0xFF38BDF8)),
    Mixed03("mixed-03", "Mixed 03", Color(0xFF0E2434), Color(0xFF22D3EE)),
    Mixed04("mixed-04", "Mixed 04", Color(0xFF2B0D24), Color(0xFFEC4899))
}

val OrbitDoodleWallpaperOptions: List<String> = listOf("Auto") + OrbitDoodleWallpaper.entries.map { it.label }

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
    return when (LocalActiveTheme.current) {
        Theme.Light -> OrbitDoodleWallpaper.Light02
        Theme.Amoled -> OrbitDoodleWallpaper.Amoled03
        Theme.Ocean -> OrbitDoodleWallpaper.Mixed03
        Theme.Forest -> OrbitDoodleWallpaper.Dark04
        Theme.Sunset -> OrbitDoodleWallpaper.Mixed04
        Theme.Cyberpunk -> OrbitDoodleWallpaper.Mixed04
        else -> if (LocalIsLiteMode.current) OrbitDoodleWallpaper.Dark02 else OrbitDoodleWallpaper.Mixed01
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
        Canvas(Modifier.fillMaxSize()) {
            drawDoodleWallpaper(wallpaper.stroke.copy(alpha = 0.24f * intensity))
            drawRect(Color.Black.copy(alpha = if (wallpaper.background.luminanceApprox() > 0.55f) 0.02f else 0.10f))
        }
    }
}

private fun DrawScope.drawDoodleWallpaper(tint: Color) {
    val tile = 150.dp.toPx()
    val stroke = Stroke(width = 2.1.dp.toPx(), cap = StrokeCap.Round)
    var row = 0
    var y = -tile * 0.30f
    while (y < size.height + tile) {
        var col = 0
        var x = -tile * 0.22f + if (row % 2 == 0) 0f else tile * 0.46f
        while (x < size.width + tile) {
            val center = Offset(x + tile * 0.50f, y + tile * 0.50f)
            val scale = tile / 150f
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

private fun Color.luminanceApprox(): Float {
    return (red * 0.299f) + (green * 0.587f) + (blue * 0.114f)
}

private fun DrawScope.drawDoodleLine(color: Color, start: Offset, end: Offset, stroke: Stroke) {
    drawLine(color = color, start = start, end = end, strokeWidth = stroke.width, cap = StrokeCap.Round)
}
