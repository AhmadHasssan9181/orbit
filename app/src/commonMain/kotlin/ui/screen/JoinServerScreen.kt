package ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import repository.OrbitRepository
import ui.theme.rememberCosmicTokens

@Composable
fun JoinServerScreen(
    repository: OrbitRepository,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()
    var input by remember { mutableStateOf("") }
    var isJoining by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(400.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(tokens.Bg)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Join a Server / Room",
                color = tokens.Text_White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = input,
                onValueChange = { input = it; errorMsg = null },
                placeholder = { Text("Enter room ID or alias (e.g., #room:server.com)", color = tokens.Text_Muted) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = tokens.Accent,
                    unfocusedBorderColor = tokens.Separator,
                    focusedTextColor = tokens.Text_White,
                    unfocusedTextColor = tokens.Text_White,
                    cursorColor = tokens.Accent
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )

            if (errorMsg != null) {
                Text(
                    text = errorMsg!!,
                    color = Color.Red,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel", color = tokens.Text_Muted)
                }

                Button(
                    onClick = {
                        if (input.isBlank()) return@Button
                        isJoining = true
                        errorMsg = null
                        scope.launch {
                            val result = repository.joinSpace(input.trim())
                            if (result.isSuccess) {
                                onClose()
                            } else {
                                errorMsg = result.exceptionOrNull()?.message ?: "Failed to join"
                                isJoining = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.Accent),
                    enabled = !isJoining && input.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    if (isJoining) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Join", color = Color.White)
                    }
                }
            }
        }
    }
}
