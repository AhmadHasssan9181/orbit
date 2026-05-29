package ui.screen.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ui.theme.rememberCosmicTokens

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

internal val Bg: Color @Composable get() = rememberCosmicTokens().Bg
internal val CardBg: Color @Composable get() = rememberCosmicTokens().CardBg
internal val CardBgHover: Color @Composable get() = rememberCosmicTokens().CardBgHover
internal val Accent: Color @Composable get() = rememberCosmicTokens().Accent
internal val AccentLight: Color @Composable get() = rememberCosmicTokens().AccentLight
internal val AccentGhost: Color @Composable get() = rememberCosmicTokens().AccentGhost
internal val Text_White: Color @Composable get() = rememberCosmicTokens().Text_White
internal val Text_Secondary: Color @Composable get() = rememberCosmicTokens().Text_Secondary
internal val Text_Muted: Color @Composable get() = rememberCosmicTokens().Text_Muted
internal val Separator: Color @Composable get() = rememberCosmicTokens().Separator
internal val Header_Bg: Color @Composable get() = rememberCosmicTokens().Header_Bg
internal val Input_Bg: Color @Composable get() = rememberCosmicTokens().Input_Bg
internal val Members_Bg: Color @Composable get() = rememberCosmicTokens().Members_Bg
internal val Rail_Bg: Color @Composable get() = rememberCosmicTokens().Rail_Bg
internal val Rail_Active: Color @Composable get() = rememberCosmicTokens().Rail_Active
internal val Pill_Color: Color @Composable get() = rememberCosmicTokens().Pill_Color
internal val Drawer_Bg: Color @Composable get() = rememberCosmicTokens().Drawer_Bg
internal val UserBar_Bg: Color @Composable get() = rememberCosmicTokens().UserBar_Bg
internal val ChannelSelected_Bg: Color @Composable get() = rememberCosmicTokens().ChannelSelected_Bg
internal val ChannelHover_Bg: Color @Composable get() = rememberCosmicTokens().ChannelHover_Bg
internal val MentionHighlight: Color @Composable get() = rememberCosmicTokens().MentionHighlight
internal val MessageHover_Bg: Color @Composable get() = rememberCosmicTokens().MessageHover_Bg

// Aliases for ChatScreen.kt
internal val Chat_Bg: Color @Composable get() = rememberCosmicTokens().Bg
