package ui.screen.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ui.components.OrbitButton
import ui.components.OrbitButtonTone
import ui.theme.rememberCosmicTokens

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
fun SessionErrorScreen(
    error: String,
    onRetry: () -> Unit,
    onLogout: () -> Unit
) {
    val tokens = rememberCosmicTokens()
    
    Box(
        modifier = Modifier.fillMaxSize().background(tokens.Bg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Session Error",
                style = MaterialTheme.typography.headlineMedium,
                // Was a bare Color.White: white-on-white in the Light theme.
                color = tokens.Text_White
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.Text_Muted,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = tokens.Accent)
            ) {
                Text("Retry Connection")
            }
            Spacer(modifier = Modifier.height(12.dp))
            // Quiet secondary, no ring: the filled "Retry Connection" above is
            // the action, so this only needs to be readable and clickable.
            OrbitButton(
                text = "Login with New Account",
                onClick = onLogout,
                tone = OrbitButtonTone.Neutral,
                fullWidth = true,
                size = 40.dp
            )
        }
    }
}
