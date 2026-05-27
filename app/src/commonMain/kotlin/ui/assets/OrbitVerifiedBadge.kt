package ui.assets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
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
        imageVector = Icons.Filled.Verified,
        contentDescription = "Verified",
        tint = badgeTint,
        modifier = modifier.size(14.dp)
    )
}
