package ui.assets

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ui.icons.TablerIcons
import ui.icons.CircleCheck
import ui.screen.auth.*
import ui.screen.call.*
import ui.screen.drawer.*
import ui.screen.main.*
import ui.screen.media.*
import ui.screen.server.*
import ui.screen.settings.*
import ui.theme.rememberCosmicTokens
private val OrbitVerifiedUsers = setOf("nfak_ism", "ahmad9812")

fun isOrbitVerifiedUser(vararg names: String?): Boolean =
    names.any { name ->
        val normalized = name
            ?.trim()
            ?.removePrefix("@")
            ?.substringBefore(":")
            ?.lowercase()

        normalized in OrbitVerifiedUsers
    }

@Composable
fun OrbitVerifiedBadge(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    val badgeTint = if (tint == Color.Unspecified) rememberCosmicTokens().AccentLight else tint
    Icon(
        imageVector = with(TablerIcons) { CircleCheck },
        contentDescription = "Verified",
        tint = badgeTint,
        modifier = modifier.size(14.dp)
    )
}
