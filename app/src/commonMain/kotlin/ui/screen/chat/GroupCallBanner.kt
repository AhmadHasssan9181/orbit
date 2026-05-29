package ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
internal fun GroupCallBanner(url: String, roomId: String, onJoinCall: (String) -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Row(
        modifier = modifier
            .background(Accent.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .border(1.dp, Accent.copy(alpha=0.3f), RoundedCornerShape(12.dp))
            .padding(16.dp), 
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Active Group Call", color = Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Click to join the ongoing group call.", color = Text_Muted, fontSize = 13.sp)
        }
        Button(
            onClick = { 
                if (url.startsWith("matrix-call://")) {
                    onJoinCall(url)
                } else {
                    uriHandler.openUri(url)
                }
            }, 
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) {
            Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Join Call")
        }
    }
}
