package ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.rememberDialogState
import ui.theme.LocalReduceMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * Dialog.
 *
 * `AlertDialog` was called from a dozen places with different container
 * colours, and in the light theme it kept Material's purple-tinted surface,
 * so dialogs were visibly from a different app than the window behind them.
 * This one is built from the token set: an elevated surface, a hairline
 * border, an optional icon, and a footer whose buttons match the rest of the
 * app.
 */
@Composable
fun OrbitDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    confirmLabel: String = "Confirm",
    dismissLabel: String? = "Cancel",
    onConfirm: (() -> Unit)? = null,
    confirmTone: OrbitButtonTone = OrbitButtonTone.Primary,
    dismissTone: OrbitButtonTone = OrbitButtonTone.Neutral,
    confirmEnabled: Boolean = true,
    width: Dp = 420.dp,
    dismissOnClickOutside: Boolean = true,
    content: @Composable () -> Unit
) {
    val t = rememberCosmicTokens()
    val reduce = LocalReduceMotion.current

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = dismissOnClickOutside,
            usePlatformDefaultWidth = false
        )
    ) {
        val scale by animateFloatAsState(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = 420f
            ),
            label = "dialogScale"
        )
        Column(
            modifier = modifier
                .widthIn(max = width)
                .scale(if (reduce) 1f else scale)
                .clip(OrbitRadius.lgShape)
                .background(t.Elevated)
                .border(1.dp, t.SeparatorStrong, OrbitRadius.lgShape)
        ) {
            // ── Header ─────────────────────────────────────────────
            if (title.isNotBlank() || subtitle != null || icon != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = OrbitSpace.xl, end = OrbitSpace.xl, top = OrbitSpace.xl, bottom = OrbitSpace.md),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(OrbitSpace.md)
                ) {
                    if (icon != null) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(OrbitRadius.mdShape)
                                .background(t.AccentGhost)
                                .padding(7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = t.Accent,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium.copy(
                                color = t.Text_White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                letterSpacing = 0.sp
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (subtitle != null) {
                            Spacer(Modifier.height(OrbitSpace.xs))
                            Text(
                                text = subtitle,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                                    color = t.Text_Muted,
                                    fontSize = 12.5.sp,
                                    lineHeight = 17.sp
                                )
                            )
                        }
                    }
                }
            }

            // ── Body ───────────────────────────────────────────────
            Box(modifier = Modifier.padding(horizontal = OrbitSpace.xl)) {
                content()
            }

            // ── Footer ─────────────────────────────────────────────
            if (onConfirm != null || dismissLabel != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(OrbitSpace.xl),
                    horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm, Alignment.End)
                ) {
                    if (dismissLabel != null) {
                        OrbitButton(
                            text = dismissLabel,
                            onClick = onDismissRequest,
                            tone = dismissTone
                        )
                    }
                    if (onConfirm != null) {
                        OrbitButton(
                            text = confirmLabel,
                            onClick = onConfirm,
                            tone = confirmTone,
                            enabled = confirmEnabled
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(OrbitSpace.xl))
            }
        }
    }
}

/** Small inline notice used inside dialogs and panels. */
@Composable
fun OrbitCallout(
    text: String,
    modifier: Modifier = Modifier,
    tone: OrbitButtonTone = OrbitButtonTone.Neutral,
    icon: ImageVector? = null
) {
    val t = rememberCosmicTokens()
    val (bg, fg) = when (tone) {
        OrbitButtonTone.Danger -> t.DangerGhost to t.Danger
        OrbitButtonTone.Success -> t.SuccessGhost to t.Success
        OrbitButtonTone.Primary -> t.AccentGhost to t.Accent
        else -> t.Sunken to t.Text_Muted
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(OrbitRadius.smShape)
            .background(bg)
            .padding(OrbitSpace.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(15.dp)
            )
        }
        Text(
            text = text,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                color = fg,
                fontSize = 12.5.sp,
                lineHeight = 17.sp
            )
        )
    }
}
