package ui.assets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

enum class OrbitEmptyScene {
    Chat,
    DirectMessages,
    Servers,
    CreateServer,
    CreateRoom,
    Search,
    Pins,
    Media,
    Notifications,
    Offline,
    Permission,
    Invite,
    Upload,
    VoiceSettings,
    Roles,
    CreateCustom,
    CreateTemplate,
    JoinInvite,
    JoinDiscover,
    TemplateFriends,
    TemplateStudy,
    TemplateCommunity
}

@Composable
fun OrbitBrandMark(
    modifier: Modifier = Modifier,
    showGlow: Boolean = true
) {
    val tokens = rememberCosmicTokens()
    Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val s = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            if (showGlow) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(tokens.Accent.copy(alpha = 0.42f), Color.Transparent),
                        center = center,
                        radius = s * 0.52f
                    ),
                    radius = s * 0.50f,
                    center = center
                )
            }
            drawCircle(tokens.Bg, radius = s * 0.30f, center = center)
            drawCircle(
                color = tokens.Accent,
                radius = s * 0.30f,
                center = center,
                style = Stroke(width = s * 0.050f)
            )
            rotate(degrees = -18f, pivot = center) {
                drawOval(
                    brush = Brush.linearGradient(listOf(tokens.AccentLight, tokens.Accent)),
                    topLeft = Offset(center.x - s * 0.39f, center.y - s * 0.13f),
                    size = Size(s * 0.78f, s * 0.26f),
                    style = Stroke(width = s * 0.052f)
                )
            }
            drawCircle(tokens.Text_White, radius = s * 0.050f, center = Offset(center.x + s * 0.27f, center.y - s * 0.17f))
            val bubble = Path().apply {
                val left = center.x - s * 0.18f
                val top = center.y - s * 0.08f
                moveTo(left + s * 0.03f, top)
                lineTo(left + s * 0.27f, top)
                quadraticTo(left + s * 0.36f, top, left + s * 0.36f, top + s * 0.09f)
                lineTo(left + s * 0.36f, top + s * 0.19f)
                quadraticTo(left + s * 0.36f, top + s * 0.28f, left + s * 0.27f, top + s * 0.28f)
                lineTo(left + s * 0.14f, top + s * 0.28f)
                lineTo(left + s * 0.02f, top + s * 0.38f)
                lineTo(left + s * 0.02f, top + s * 0.28f)
                quadraticTo(left, top + s * 0.28f, left, top + s * 0.19f)
                lineTo(left, top + s * 0.09f)
                quadraticTo(left, top, left + s * 0.03f, top)
                close()
            }
            drawPath(bubble, brush = Brush.linearGradient(listOf(tokens.AccentLight, tokens.Accent)))
        }
    }
}

@Composable
fun OrbitDefaultAvatar(
    label: String,
    seed: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    isBot: Boolean = false
) {
    val tokens = rememberCosmicTokens()
    val accent = color ?: orbitSeedColor(seed)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(tokens.Bg)
            .border(1.dp, tokens.Text_White.copy(alpha = 0.08f), CircleShape)
            .padding(1.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val s = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(accent.copy(alpha = 0.92f), radius = s * 0.48f, center = center)
            drawCircle(Color.White.copy(alpha = 0.12f), radius = s * 0.22f, center = Offset(s * 0.36f, s * 0.32f))
            rotate(-18f, center) {
                drawOval(
                    color = Color.White.copy(alpha = 0.38f),
                    topLeft = Offset(center.x - s * 0.54f, center.y - s * 0.18f),
                    size = Size(s * 1.08f, s * 0.36f),
                    style = Stroke(width = s * 0.035f)
                )
            }
            if (isBot) {
                drawCircle(tokens.AccentLight, radius = s * 0.09f, center = Offset(s * 0.75f, s * 0.25f))
            }
        }
        Text(
            text = initialsFor(label),
            // Bare Color.White over the seed circle: unreadable when the seed
            // colour is light. Picked from the circle's own luminance instead.
            color = if (accent.luminance() > 0.55f) Color(0xFF14161B) else Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp
            )
        )
    }
}

@Composable
fun OrbitServerAssetIcon(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(15.dp))
            .background(tokens.Bg)
            .border(1.dp, tokens.Text_White.copy(alpha = 0.08f), RoundedCornerShape(15.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val s = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            drawRoundRect(
                color = tokens.Bg,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.28f, s * 0.28f)
            )
            drawCircle(color, radius = s * 0.35f, center = center)
            rotate(-18f, center) {
                drawOval(
                    color = Color.White.copy(alpha = 0.42f),
                    topLeft = Offset(center.x - s * 0.50f, center.y - s * 0.17f),
                    size = Size(s, s * 0.34f),
                    style = Stroke(width = s * 0.042f)
                )
            }
            drawCircle(Color.White, radius = s * 0.070f, center = Offset(center.x + s * 0.24f, center.y - s * 0.17f))
            drawLine(
                color = Color.White.copy(alpha = 0.16f),
                start = Offset(s * 0.28f, s * 0.68f),
                end = Offset(s * 0.74f, s * 0.68f),
                strokeWidth = s * 0.06f
            )
        }
        Text(
            text = initialsFor(label),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 10.sp
            )
        )
    }
}

@Composable
fun OrbitRoleBadgeAsset(
    roleId: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()
    Canvas(modifier = modifier.aspectRatio(1f)) {
        val s = size.minDimension
        val shield = Path().apply {
            moveTo(s * 0.50f, s * 0.10f)
            lineTo(s * 0.82f, s * 0.23f)
            lineTo(s * 0.82f, s * 0.50f)
            quadraticTo(s * 0.82f, s * 0.78f, s * 0.50f, s * 0.92f)
            quadraticTo(s * 0.18f, s * 0.78f, s * 0.18f, s * 0.50f)
            lineTo(s * 0.18f, s * 0.23f)
            close()
        }
        drawPath(shield, color)
        val stroke = Stroke(width = s * 0.07f)
        when (roleId) {
            "owner" -> {
                drawCircle(tokens.Text_White.copy(alpha = 0.78f), radius = s * 0.11f, center = Offset(s * 0.50f, s * 0.42f))
                drawCircle(tokens.Text_White.copy(alpha = 0.78f), radius = s * 0.06f, center = Offset(s * 0.34f, s * 0.48f))
                drawCircle(tokens.Text_White.copy(alpha = 0.78f), radius = s * 0.06f, center = Offset(s * 0.66f, s * 0.48f))
                drawLine(tokens.Text_White.copy(alpha = 0.78f), Offset(s * 0.34f, s * 0.64f), Offset(s * 0.66f, s * 0.64f), strokeWidth = s * 0.07f)
            }
            "muted" -> {
                drawLine(tokens.Text_White.copy(alpha = 0.70f), Offset(s * 0.34f, s * 0.34f), Offset(s * 0.66f, s * 0.66f), strokeWidth = s * 0.08f)
                drawLine(tokens.Text_White.copy(alpha = 0.70f), Offset(s * 0.66f, s * 0.34f), Offset(s * 0.34f, s * 0.66f), strokeWidth = s * 0.08f)
            }
            else -> {
                drawLine(
                    tokens.Text_White.copy(alpha = 0.72f),
                    Offset(s * 0.50f, s * 0.30f),
                    Offset(s * 0.50f, s * 0.70f),
                    strokeWidth = s * 0.07f
                )
                drawLine(
                    tokens.Text_White.copy(alpha = 0.72f),
                    Offset(s * 0.32f, s * 0.50f),
                    Offset(s * 0.68f, s * 0.50f),
                    strokeWidth = s * 0.07f
                )
            }
        }
    }
}

@Composable
fun OrbitEmptyIllustration(
    scene: OrbitEmptyScene,
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()
    Box(modifier = modifier.aspectRatio(900f / 560f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val center = Offset(w * 0.50f, h * 0.47f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(tokens.Accent.copy(alpha = 0.34f), Color.Transparent),
                    center = center,
                    radius = w * 0.32f
                ),
                radius = w * 0.30f,
                center = center
            )
            rotate(-14f, center) {
                drawOval(
                    color = tokens.Accent.copy(alpha = 0.22f),
                    topLeft = Offset(w * 0.13f, h * 0.29f),
                    size = Size(w * 0.74f, h * 0.28f),
                    style = Stroke(width = 3.dp.toPx())
                )
            }
            drawRoundRect(
                color = tokens.CardBg.copy(alpha = 0.72f),
                topLeft = Offset(w * 0.23f, h * 0.66f),
                size = Size(w * 0.54f, h * 0.14f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.07f, h * 0.07f)
            )

            when (scene) {
                OrbitEmptyScene.Chat, OrbitEmptyScene.DirectMessages -> drawChatScene(tokens.Accent, tokens.AccentLight)
                OrbitEmptyScene.Servers, OrbitEmptyScene.CreateServer -> drawServerScene(tokens.Accent, tokens.AccentLight)
                OrbitEmptyScene.CreateRoom -> drawRoomScene(tokens.Accent, tokens.AccentLight)
                OrbitEmptyScene.Search -> drawSearchScene(tokens.Accent, tokens.AccentLight)
                OrbitEmptyScene.Pins -> drawPinScene(tokens.Accent, tokens.AccentLight)
                OrbitEmptyScene.Media -> drawMediaScene(tokens.Accent, tokens.AccentLight)
                OrbitEmptyScene.Notifications -> drawNotificationScene(tokens.Accent, tokens.AccentLight)
                OrbitEmptyScene.Offline -> drawOfflineScene(tokens.Accent, tokens.AccentLight)
                OrbitEmptyScene.Permission -> drawPermissionScene(tokens.Accent, tokens.AccentLight)
                OrbitEmptyScene.Invite -> drawInviteScene(tokens.Accent, tokens.AccentLight)
                OrbitEmptyScene.Upload -> drawUploadScene(tokens.Accent, tokens.AccentLight)
                OrbitEmptyScene.VoiceSettings -> drawVoiceScene(tokens.Accent, tokens.AccentLight)
                OrbitEmptyScene.Roles -> drawRolesScene(tokens.Accent, tokens.AccentLight)
                OrbitEmptyScene.CreateCustom,
                OrbitEmptyScene.CreateTemplate,
                OrbitEmptyScene.JoinInvite,
                OrbitEmptyScene.JoinDiscover,
                OrbitEmptyScene.TemplateFriends,
                OrbitEmptyScene.TemplateStudy,
                OrbitEmptyScene.TemplateCommunity -> drawChoiceScene(scene, tokens.Accent, tokens.AccentLight)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChatScene(accent: Color, accentLight: Color) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = Color(0xFF1F242C),
        topLeft = Offset(w * 0.28f, h * 0.22f),
        size = Size(w * 0.28f, h * 0.19f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx(), 18.dp.toPx())
    )
    drawCircle(accentLight, radius = w * 0.010f, center = Offset(w * 0.34f, h * 0.30f))
    drawCircle(accent, radius = w * 0.010f, center = Offset(w * 0.38f, h * 0.30f))
    drawCircle(Color(0xFF49AE81), radius = w * 0.010f, center = Offset(w * 0.42f, h * 0.30f))
    drawRoundRect(
        color = Color(0xFF1F242C),
        topLeft = Offset(w * 0.48f, h * 0.42f),
        size = Size(w * 0.24f, h * 0.16f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx(), 18.dp.toPx())
    )
    drawCircle(accent, radius = w * 0.010f, center = Offset(w * 0.54f, h * 0.49f))
    drawCircle(accentLight, radius = w * 0.010f, center = Offset(w * 0.58f, h * 0.49f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawServerScene(accent: Color, accentLight: Color) {
    val w = size.width
    val h = size.height
    val center = Offset(w * 0.50f, h * 0.42f)
    drawRoundRect(
        color = Color(0xFF1F242C),
        topLeft = Offset(w * 0.36f, h * 0.20f),
        size = Size(w * 0.28f, h * 0.38f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(28.dp.toPx(), 28.dp.toPx())
    )
    drawCircle(accent, radius = w * 0.075f, center = center)
    rotate(-18f, center) {
        drawOval(
            brush = Brush.linearGradient(listOf(accentLight, accent)),
            topLeft = Offset(center.x - w * 0.13f, center.y - h * 0.055f),
            size = Size(w * 0.26f, h * 0.11f),
            style = Stroke(width = 5.dp.toPx())
        )
    }
    drawCircle(Color.White, radius = w * 0.018f, center = Offset(w * 0.59f, h * 0.35f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoomScene(accent: Color, accentLight: Color) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = Color(0xFF1F242C),
        topLeft = Offset(w * 0.32f, h * 0.24f),
        size = Size(w * 0.36f, h * 0.28f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx(), 24.dp.toPx())
    )
    drawLine(accentLight, Offset(w * 0.40f, h * 0.38f), Offset(w * 0.60f, h * 0.38f), strokeWidth = 8.dp.toPx())
    drawLine(accent, Offset(w * 0.50f, h * 0.29f), Offset(w * 0.50f, h * 0.47f), strokeWidth = 8.dp.toPx())
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSearchScene(accent: Color, accentLight: Color) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = Color(0xFF1F242C),
        topLeft = Offset(w * 0.31f, h * 0.24f),
        size = Size(w * 0.30f, h * 0.25f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx(), 22.dp.toPx())
    )
    drawCircle(accentLight, radius = w * 0.070f, center = Offset(w * 0.47f, h * 0.36f), style = Stroke(width = 5.dp.toPx()))
    drawLine(accent, Offset(w * 0.52f, h * 0.43f), Offset(w * 0.61f, h * 0.52f), strokeWidth = 6.dp.toPx())
    drawLine(Color.White.copy(alpha = 0.18f), Offset(w * 0.36f, h * 0.29f), Offset(w * 0.55f, h * 0.29f), strokeWidth = 4.dp.toPx())
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPinScene(accent: Color, accentLight: Color) {
    val w = size.width
    val h = size.height
    val pin = Path().apply {
        moveTo(w * 0.46f, h * 0.22f)
        lineTo(w * 0.62f, h * 0.35f)
        lineTo(w * 0.56f, h * 0.43f)
        lineTo(w * 0.60f, h * 0.56f)
        lineTo(w * 0.50f, h * 0.48f)
        lineTo(w * 0.41f, h * 0.61f)
        lineTo(w * 0.39f, h * 0.58f)
        lineTo(w * 0.47f, h * 0.45f)
        lineTo(w * 0.36f, h * 0.39f)
        close()
    }
    drawPath(pin, brush = Brush.linearGradient(listOf(accentLight, accent)))
    drawLine(Color.White.copy(alpha = 0.26f), Offset(w * 0.40f, h * 0.66f), Offset(w * 0.60f, h * 0.66f), strokeWidth = 5.dp.toPx())
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMediaScene(accent: Color, accentLight: Color) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = Color(0xFF1F242C),
        topLeft = Offset(w * 0.30f, h * 0.22f),
        size = Size(w * 0.40f, h * 0.28f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx(), 20.dp.toPx())
    )
    drawCircle(accentLight, radius = w * 0.026f, center = Offset(w * 0.38f, h * 0.30f))
    val mountain = Path().apply {
        moveTo(w * 0.33f, h * 0.47f)
        lineTo(w * 0.44f, h * 0.36f)
        lineTo(w * 0.51f, h * 0.43f)
        lineTo(w * 0.58f, h * 0.34f)
        lineTo(w * 0.68f, h * 0.47f)
        close()
    }
    drawPath(mountain, brush = Brush.linearGradient(listOf(accentLight, accent)))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNotificationScene(accent: Color, accentLight: Color) {
    val w = size.width
    val h = size.height
    val bell = Path().apply {
        moveTo(w * 0.39f, h * 0.46f)
        quadraticTo(w * 0.42f, h * 0.42f, w * 0.42f, h * 0.34f)
        quadraticTo(w * 0.42f, h * 0.24f, w * 0.50f, h * 0.24f)
        quadraticTo(w * 0.58f, h * 0.24f, w * 0.58f, h * 0.34f)
        quadraticTo(w * 0.58f, h * 0.42f, w * 0.61f, h * 0.46f)
        close()
    }
    drawPath(bell, brush = Brush.linearGradient(listOf(accentLight, accent)))
    drawCircle(Color.White.copy(alpha = 0.75f), radius = w * 0.018f, center = Offset(w * 0.50f, h * 0.50f))
    drawCircle(Color(0xFFAE7099), radius = w * 0.030f, center = Offset(w * 0.61f, h * 0.26f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOfflineScene(accent: Color, accentLight: Color) {
    val w = size.width
    val h = size.height
    drawCircle(Color(0xFF1F242C), radius = w * 0.13f, center = Offset(w * 0.50f, h * 0.38f))
    drawCircle(accent.copy(alpha = 0.72f), radius = w * 0.095f, center = Offset(w * 0.50f, h * 0.38f), style = Stroke(width = 5.dp.toPx()))
    drawLine(accentLight, Offset(w * 0.42f, h * 0.46f), Offset(w * 0.58f, h * 0.30f), strokeWidth = 6.dp.toPx())
    drawLine(Color.White.copy(alpha = 0.18f), Offset(w * 0.38f, h * 0.61f), Offset(w * 0.62f, h * 0.61f), strokeWidth = 5.dp.toPx())
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPermissionScene(accent: Color, accentLight: Color) {
    val w = size.width
    val h = size.height
    val shield = Path().apply {
        moveTo(w * 0.50f, h * 0.20f)
        lineTo(w * 0.66f, h * 0.28f)
        lineTo(w * 0.66f, h * 0.42f)
        quadraticTo(w * 0.66f, h * 0.56f, w * 0.50f, h * 0.64f)
        quadraticTo(w * 0.34f, h * 0.56f, w * 0.34f, h * 0.42f)
        lineTo(w * 0.34f, h * 0.28f)
        close()
    }
    drawPath(shield, brush = Brush.linearGradient(listOf(accentLight, accent)))
    drawLine(Color.White.copy(alpha = 0.72f), Offset(w * 0.43f, h * 0.39f), Offset(w * 0.57f, h * 0.39f), strokeWidth = 7.dp.toPx())
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawInviteScene(accent: Color, accentLight: Color) {
    val w = size.width
    val h = size.height
    drawCircle(accent, radius = w * 0.060f, center = Offset(w * 0.43f, h * 0.34f))
    drawCircle(accentLight, radius = w * 0.052f, center = Offset(w * 0.56f, h * 0.35f))
    drawRoundRect(
        color = Color(0xFF1F242C),
        topLeft = Offset(w * 0.34f, h * 0.43f),
        size = Size(w * 0.32f, h * 0.14f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx(), 20.dp.toPx())
    )
    drawLine(Color.White.copy(alpha = 0.72f), Offset(w * 0.50f, h * 0.46f), Offset(w * 0.50f, h * 0.54f), strokeWidth = 4.dp.toPx())
    drawLine(Color.White.copy(alpha = 0.72f), Offset(w * 0.46f, h * 0.50f), Offset(w * 0.54f, h * 0.50f), strokeWidth = 4.dp.toPx())
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUploadScene(accent: Color, accentLight: Color) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = Color(0xFF1F242C),
        topLeft = Offset(w * 0.35f, h * 0.28f),
        size = Size(w * 0.30f, h * 0.30f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx(), 18.dp.toPx())
    )
    drawLine(accentLight, Offset(w * 0.50f, h * 0.48f), Offset(w * 0.50f, h * 0.32f), strokeWidth = 7.dp.toPx())
    drawLine(accentLight, Offset(w * 0.43f, h * 0.39f), Offset(w * 0.50f, h * 0.32f), strokeWidth = 7.dp.toPx())
    drawLine(accentLight, Offset(w * 0.57f, h * 0.39f), Offset(w * 0.50f, h * 0.32f), strokeWidth = 7.dp.toPx())
    drawLine(accent, Offset(w * 0.41f, h * 0.54f), Offset(w * 0.59f, h * 0.54f), strokeWidth = 5.dp.toPx())
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVoiceScene(accent: Color, accentLight: Color) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = accent,
        topLeft = Offset(w * 0.43f, h * 0.23f),
        size = Size(w * 0.14f, h * 0.24f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx(), 22.dp.toPx())
    )
    drawLine(accentLight, Offset(w * 0.50f, h * 0.48f), Offset(w * 0.50f, h * 0.59f), strokeWidth = 6.dp.toPx())
    drawLine(Color.White.copy(alpha = 0.72f), Offset(w * 0.42f, h * 0.59f), Offset(w * 0.58f, h * 0.59f), strokeWidth = 5.dp.toPx())
    rotate(-18f, Offset(w * 0.50f, h * 0.42f)) {
        drawOval(
            color = Color.White.copy(alpha = 0.22f),
            topLeft = Offset(w * 0.35f, h * 0.34f),
            size = Size(w * 0.30f, h * 0.10f),
            style = Stroke(width = 3.dp.toPx())
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRolesScene(accent: Color, accentLight: Color) {
    val w = size.width
    val h = size.height
    val xs = listOf(0.40f, 0.50f, 0.60f)
    xs.forEachIndexed { index, x ->
        drawCircle(
            color = if (index == 1) accent else accentLight.copy(alpha = 0.86f),
            radius = w * 0.035f,
            center = Offset(w * x, h * (0.31f + index * 0.02f))
        )
        drawRoundRect(
            color = Color(0xFF1F242C),
            topLeft = Offset(w * (x - 0.055f), h * 0.41f),
            size = Size(w * 0.11f, h * 0.14f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx(), 18.dp.toPx())
        )
    }
    drawLine(Color.White.copy(alpha = 0.20f), Offset(w * 0.34f, h * 0.62f), Offset(w * 0.66f, h * 0.62f), strokeWidth = 5.dp.toPx())
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChoiceScene(
    scene: OrbitEmptyScene,
    accent: Color,
    accentLight: Color
) {
    val w = size.width
    val h = size.height
    val main = when (scene) {
        OrbitEmptyScene.CreateTemplate,
        OrbitEmptyScene.JoinDiscover,
        OrbitEmptyScene.TemplateStudy -> accentLight
        OrbitEmptyScene.TemplateFriends -> Color(0xFFAE7099)
        OrbitEmptyScene.TemplateCommunity -> Color(0xFF49AE81)
        else -> accent
    }
    drawRoundRect(
        color = Color(0xFF1F242C),
        topLeft = Offset(w * 0.32f, h * 0.24f),
        size = Size(w * 0.36f, h * 0.30f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx(), 20.dp.toPx())
    )
    drawCircle(main, radius = w * 0.050f, center = Offset(w * 0.43f, h * 0.36f))
    drawCircle(main.copy(alpha = 0.72f), radius = w * 0.044f, center = Offset(w * 0.55f, h * 0.38f))
    if (scene == OrbitEmptyScene.CreateTemplate || scene == OrbitEmptyScene.TemplateStudy) {
        drawLine(Color.White.copy(alpha = 0.66f), Offset(w * 0.40f, h * 0.48f), Offset(w * 0.60f, h * 0.48f), strokeWidth = 4.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.32f), Offset(w * 0.42f, h * 0.43f), Offset(w * 0.58f, h * 0.43f), strokeWidth = 3.dp.toPx())
    } else {
        rotate(-18f, Offset(w * 0.50f, h * 0.39f)) {
            drawOval(
                color = Color.White.copy(alpha = 0.30f),
                topLeft = Offset(w * 0.36f, h * 0.34f),
                size = Size(w * 0.28f, h * 0.10f),
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }
}

private fun initialsFor(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return "?"
    val parts = trimmed.split(Regex("\\s+|-|_")).filter { it.isNotBlank() }
    return if (parts.size >= 2) {
        parts.take(2).joinToString("") { it.first().uppercase() }
    } else {
        trimmed.take(2).uppercase()
    }
}

// Delegated to the shared palette so generated avatars, DM rows and generated
// username colours can never drift apart.
private fun orbitSeedColor(seed: String): Color = ui.theme.avatarSeedColor(seed)
