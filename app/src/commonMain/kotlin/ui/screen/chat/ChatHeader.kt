package ui.screen.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.components.OrbitIconButton
import ui.components.OrbitTooltip
import ui.components.orbitClickable
import ui.components.rememberOrbitInteraction
import ui.icons.OrbitIcons
import ui.layout.LocalWindowSizeClass
import ui.model.ChannelType
import ui.model.OrbitChannel
import ui.model.OrbitUser
import ui.theme.OrbitConceptBorder
import ui.theme.OrbitConceptMetrics
import ui.theme.OrbitConceptStatus
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * Room header.
 *
 * Rebuilt from the concept's `.chat-header`:
 *
 * ```
 * .chat-header {
 *   height: var(--header-height);            // 48px
 *   padding: 0 16px; display: flex; align-items: center; justify-content: space-between;
 *   border-bottom: 1px solid var(--border-subtle);
 *   box-shadow: var(--elevation-low); background-color: var(--bg-chat);
 * }
 * .chat-header-left     { display: flex; align-items: center; gap: 12px; overflow: hidden }
 * .chat-header-title    { display: flex; align-items: center; gap: 6px;
 *                         font-size: 16px; font-weight: 700; color: var(--header-primary) }
 * .chat-header-divider  { width: 1px; height: 16px; background-color: var(--border-subtle) }
 * .chat-header-topic    { font-size: 13px; color: var(--text-muted);
 *                         white-space: nowrap; overflow: hidden;
 *                         text-overflow: ellipsis; max-width: 480px }
 * .chat-header-actions  { display: flex; align-items: center; gap: 12px }
 * ```
 *
 * The previous header was a 52dp bar whose topic was a *second line* under the
 * title, which is what made a room with a long name push the actions off the
 * edge of the window — the topic had to go somewhere and it chose to grow. Here
 * the identity is one baseline-tall row: hash, name, 1×16 hairline, topic, all
 * inside 48dp, with the actions pinned right and a `max-width: 480px` ellipsis
 * on the topic so the topic is what truncates and never the buttons.
 */
@Composable
internal fun ChatHeader(
    channel: OrbitChannel,
    directProfile: OrbitUser? = null,
    onBack: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
    onVoiceCall: (() -> Unit)? = null,
    searchActive: Boolean = false,
    onToggleSearch: () -> Unit = {},
    /**
     * The room search text, and its setter.
     *
     * Non-null puts a `.chat-search-bar` in the actions row, which is where the
     * concept has it. Null leaves the toggle as a plain icon button — the
     * narrow-window path, where a 144dp field has nowhere to go.
     */
    searchQuery: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
    pinsActive: Boolean = false,
    /** Drawn on the pins button's `.header-badge`. */
    pinCount: Int = 0,
    onTogglePins: () -> Unit = {},
    memberListActive: Boolean = false,
    onToggleMemberList: (() -> Unit)? = null,
    notifMode: String = "ALL",
    onCycleNotificationMode: (() -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val light = isLightChatSurface(t)
    val windowSize = LocalWindowSizeClass.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // `.chat-header` has no hover rule of its own. The bar tint is kept because it
    // was already here and it is 2% of a colour -- but it now starts from
    // `--bg-chat` rather than the app's old header grey, because that is what the
    // rule says the bar is painted with.
    val barBg by animateColorAsState(
        targetValue = if (hovered) t.Bg.copy(alpha = 0.98f) else t.Bg,
        animationSpec = OrbitMotion.SnappyColor,
        label = "headerBg"
    )
    val hairline = if (light) ChatConcept.borderSubtleLight else OrbitConceptBorder.subtle()

    // A DM has no topic, so its second line is the handle, which is the closest
    // thing the concept's markup has to a subtitle.
    val subtitle = when {
        directProfile != null && directProfile.username.isNotBlank() -> "@${directProfile.username}"
        channel.topic.isNotBlank() -> channel.topic
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(barBg)
            .hoverable(interaction)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(OrbitConceptMetrics.headerHeight)
                // padding: 0 16px
                .padding(horizontal = OrbitSpace.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── Identity ─────────────────────────────────────────
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = OrbitSpace.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OrbitSpace.md)
            ) {
                if (onBack != null && !windowSize.isExpanded) {
                    OrbitIconButton(
                        icon = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        size = 28.dp,
                        iconSize = 16.dp,
                        shape = OrbitRadius.xsShape
                    )
                }

                // `<Hash size={24} color="var(--interactive-muted)" />`
                //
                // A DM's stand-in is 28dp rather than the hash's 24dp. The concept
                // has no DM header, so there is nothing to match — and a 24dp face
                // with a 9dp presence dot on it is a dot with a person behind it
                // rather than a person. The 4dp is the difference between a glyph
                // and an identity.
                if (directProfile != null) {
                    SmallUserAvatar(directProfile, size = 28.dp)
                } else {
                    Icon(
                        imageVector = if (channel.type == ChannelType.TEXT) {
                            OrbitIcons.TextChannel
                        } else {
                            OrbitIcons.VoiceChannel
                        },
                        contentDescription = null,
                        tint = ChatConcept.interactiveMuted,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Text(
                    text = directProfile?.displayName ?: channelDisplayNameForHeader(channel),
                    style = MaterialTheme.typography.titleMedium.copy(
                        // .chat-header-title { font-size: 16px; font-weight: 700 }
                        fontWeight = FontWeight.Bold,
                        color = if (light) ChatConcept.headerPrimaryLight else t.Text_White,
                        fontSize = 16.sp,
                        letterSpacing = 0.sp,
                        lineHeight = 20.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (subtitle != null) {
                    // .chat-header-divider { width: 1px; height: 16px }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(16.dp)
                            .background(hairline)
                    )
                    // .chat-header-topic
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            color = t.Text_Muted
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 480.dp)
                    )
                }
            }

            // ── Actions ─────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(OrbitSpace.md)) {
                if (searchQuery != null) {
                    ChatSearchBar(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange ?: {},
                        light = light
                    )
                } else {
                    HeaderActionBtn(
                        icon = Icons.Default.Search,
                        contentDescription = "Search this room",
                        active = searchActive,
                        tooltip = "Search",
                        onClick = onToggleSearch
                    )
                }
                if (directProfile != null) {
                    HeaderActionBtn(
                        icon = OrbitIcons.Call,
                        contentDescription = "Start a voice call",
                        tooltip = "Voice call",
                        onClick = { onVoiceCall?.invoke() }
                    )
                    HeaderActionBtn(
                        icon = OrbitIcons.Video,
                        contentDescription = "Start a video call",
                        tooltip = "Video call",
                        onClick = { onVideoCall?.invoke() }
                    )
                }
                if (onCycleNotificationMode != null) {
                    HeaderActionBtn(
                        icon = if (notifMode == "MUTE") {
                            Icons.Default.NotificationsOff
                        } else {
                            Icons.Default.Notifications
                        },
                        contentDescription = when (notifMode) {
                            "MUTE" -> "Notifications muted"
                            "MENTIONS" -> "Notifications: mentions only"
                            else -> "Notifications: all messages"
                        },
                        active = notifMode != "ALL",
                        tooltip = when (notifMode) {
                            "MUTE" -> "Muted"
                            "MENTIONS" -> "Mentions only"
                            else -> "All messages"
                        },
                        onClick = onCycleNotificationMode
                    )
                }
                HeaderActionBtn(
                    icon = OrbitIcons.Pin,
                    contentDescription = "Pinned messages",
                    active = pinsActive,
                    tooltip = "Pins",
                    badge = pinCount,
                    onClick = onTogglePins
                )
                // Hidden when the layout has no member panel to show, rather than
                // offering a button that toggles state nobody can see.
                if (onToggleMemberList != null) {
                    HeaderActionBtn(
                        icon = OrbitIcons.Members,
                        contentDescription = "Members",
                        active = memberListActive,
                        tooltip = "Members",
                        onClick = onToggleMemberList
                    )
                }
            }
        }

        // `border-bottom: 1px solid var(--border-subtle)`, plus the 1dp lift that
        // `box-shadow: var(--elevation-low)` contributes.
        //
        // A hairline the header owns, rather than a `HorizontalDivider` the screen
        // below draws: the rule belongs to `.chat-header` in the stylesheet, and a
        // border drawn by the caller is one that survives the caller being
        // rewritten.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(hairline)
        )
    }
}

/**
 * `.header-action-btn` — one quiet icon control in the header's actions row.
 *
 * ```
 * background: transparent; border: none; color: var(--interactive-normal);
 * cursor: pointer; padding: 4px; border-radius: 4px; position: relative;
 * display: flex; align-items: center; justify-content: center;
 * ```
 * ```
 * .header-action-btn:hover  { color: var(--interactive-hover) }
 * .header-action-btn.active { color: var(--interactive-active) }
 * ```
 *
 * `--interactive-active` is white, i.e. a fully lit icon; the resting colour is
 * `--interactive-normal`, which is *lighter* than the `--text-muted` the shared
 * `OrbitIconButton` uses at rest. That gap is the whole difference between
 * "chrome" and "a control in the chrome", and it is why this is not
 * `OrbitIconButton`.
 *
 * A 20px glyph inside 4px of padding is a 28dp target, the same target the old
 * 32dp button had, so nothing shrank under the cursor.
 */
@Composable
private fun HeaderActionBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tooltip: String,
    onClick: () -> Unit,
    active: Boolean = false,
    badge: Int = 0
) {
    val t = rememberCosmicTokens()
    val light = isLightChatSurface(t)
    val interaction = rememberOrbitInteraction()
    val hovered by interaction.hoverSource.collectIsHoveredAsState()

    val tint = when {
        active -> if (light) ChatConcept.headerPrimaryLight else Color.White
        hovered -> t.Text_Secondary                                            // --interactive-hover
        else -> if (light) ChatConcept.interactiveNormalLight else ChatConcept.interactiveNormal
    }

    val core: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(OrbitRadius.xsShape)
                // Both are needed and neither subsumes the other:
                // `hoverable` raises the Hovered interaction the tint above reads,
                // and `orbitClickable` supplies the click, the pressed state and the
                // keyboard focus path. `OrbitIconButton` does the same pair for the
                // same reason.
                .hoverable(interaction.hoverSource)
                .orbitClickable(interaction, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            if (badge > 0) {
                // .header-badge { position: absolute; top: -2px; right: -2px;
                //   background-color: var(--status-dnd); color: #fff;
                //   font-size: 10px; font-weight: 700; padding: 0 4px;
                //   border-radius: 6px; height: 14px; }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-2).dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(OrbitConceptStatus.dnd)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (badge > 99) "99+" else badge.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            letterSpacing = 0.sp
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
    OrbitTooltip(tooltip, core)
}

/**
 * `.chat-search-bar` — the room search field, inline in the actions row.
 *
 * ```
 * display: flex; align-items: center; gap: 6px;
 * background-color: var(--bg-servers); border-radius: 4px; padding: 4px 8px;
 * color: var(--text-muted); width: 144px; transition: width .2s ease;
 * ```
 * ```
 * .chat-search-bar:focus-within { width: 220px; color: var(--text-normal) }
 * .chat-search-bar input { background: transparent; border: none; outline: none;
 *   font-size: 13px; color: var(--text-normal); width: 100% }
 * ```
 *
 * The 144 → 220dp growth on focus is animated rather than snapped, because a
 * field that jumps sideways moves the buttons either side of it — and the pins
 * and members buttons are what you were reaching for when you decided to search.
 */
@Composable
private fun ChatSearchBar(value: String, onValueChange: (String) -> Unit, light: Boolean) {
    val t = rememberCosmicTokens()
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()

    val width by animateDpAsState(
        targetValue = if (focused) 220.dp else 144.dp,
        animationSpec = OrbitMotion.SnappyDp,
        label = "chatSearchWidth"
    )

    Row(
        modifier = Modifier
            .width(width)
            .height(28.dp)
            .clip(RoundedCornerShape(4.dp))
            // [data-theme='light'] .chat-search-bar { background-color: #e3e5e8 }
            .background(if (light) ChatConcept.pillHoverLight else ChatConcept.servers)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = if (focused) t.Text_Secondary else t.Text_Muted,
            modifier = Modifier.size(16.dp)
        )
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = "Search",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        color = t.Text_Muted
                    ),
                    maxLines = 1
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                interactionSource = focus,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    color = t.Text_Secondary
                ),
                cursorBrush = SolidColor(t.Accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Voice rooms get their " (Call)" suffix stripped in the header too. */
private fun channelDisplayNameForHeader(channel: OrbitChannel): String =
    if (channel.type == ChannelType.VOICE) {
        channel.name.removeSuffix(" (Call)")
    } else {
        channel.name
    }
