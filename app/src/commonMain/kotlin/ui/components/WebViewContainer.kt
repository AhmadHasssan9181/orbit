package ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
expect fun WebViewContainer(
    url: String,
    modifier: Modifier = Modifier
)
