package ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.theme.rememberCosmicTokens

// ─── Reusable PrefCard ───────────────────────────────────────────

@Composable
internal fun PrefCard(
    title: String,
    description: String? = null,
    vertical: Boolean = false,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val tokens = rememberCosmicTokens()
    
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) tokens.CardBgHover else tokens.CardBg,
        animationSpec = tween(150),
        label = "cardBg"
    )

    if (vertical) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(bgColor)
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = {})
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = tokens.Text_White
                )
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = tokens.Text_Muted,
                        fontSize = 11.sp
                    )
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(bgColor)
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = {})
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = tokens.Text_White
                )
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = tokens.Text_Muted,
                        fontSize = 11.sp
                    )
                )
            }
        }
        content()
        }
    }
}

// ─── Section Header ──────────────────────────────────────────────

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.ExtraBold,
            color = rememberCosmicTokens().Text_White,
            letterSpacing = 0.5.sp
        ),
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

// ─── Hero Info Card ──────────────────────────────────────────────

@Composable
internal fun HeroCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    gradient: List<Color>? = null,
    buttonText: String? = null,
    onButtonClick: () -> Unit = {}
) {
    val tokens = rememberCosmicTokens()
    val bgMod = if (gradient != null) {
        Modifier.background(Brush.horizontalGradient(gradient))
    } else {
        Modifier.background(tokens.CardBg)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(bgMod)
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = if (gradient != null) Color.White else tokens.Text_White, fontWeight = FontWeight.ExtraBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (gradient != null) Color.White.copy(alpha = 0.8f) else tokens.Text_Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 18.sp)
        if (buttonText != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onButtonClick, colors = ButtonDefaults.buttonColors(containerColor = if (gradient != null) Color.White.copy(0.2f) else tokens.Accent)) {
                Text(buttonText, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─── Chip Group ──────────────────────────────────────────────────

@Composable
internal fun ChipGroup(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val tokens = rememberCosmicTokens()
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.widthIn(max = 300.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        items(options) { opt ->
            val isSelected = selected == opt
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) tokens.Accent else tokens.CardBg,
                label = "chipBg"
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(bgColor)
                    .clickable { onSelect(opt) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = opt,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (isSelected) Color.White else tokens.Text_Muted,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 11.sp
                    )
                )
            }
        }
    }
}

// ─── Cosmic Switch ───────────────────────────────────────────────

@Composable
internal fun CosmicSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    activeColor: Color? = null
) {
    val active = activeColor ?: rememberCosmicTokens().Accent
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor   = Color.White,
            checkedTrackColor   = active,
            uncheckedThumbColor = rememberCosmicTokens().Text_Muted,
            uncheckedTrackColor = rememberCosmicTokens().CardBg
        )
    )
}

// ─── Status Badge ────────────────────────────────────────────────

@Composable
internal fun StatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall.copy(
            color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp
        ))
    }
}
