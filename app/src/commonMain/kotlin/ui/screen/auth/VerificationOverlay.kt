package ui.screen.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import de.connect2x.trixnity.client.verification.ActiveDeviceVerification
import de.connect2x.trixnity.client.verification.ActiveSasVerificationMethod
import de.connect2x.trixnity.client.verification.ActiveSasVerificationState
import de.connect2x.trixnity.client.verification.ActiveVerificationState
import de.connect2x.trixnity.core.model.events.m.key.verification.VerificationMethod
import kotlinx.coroutines.launch
import ui.theme.rememberCosmicTokens

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
fun VerificationOverlay(activeVerification: ActiveDeviceVerification?) {
    val coroutineScope = rememberCoroutineScope()

    if (activeVerification == null) return

    val state by activeVerification.state.collectAsState()

    // Terminal states stay on screen until the user closes them, but must be
    // closable — an undismissable modal would brick the whole window. Interactive
    // (mid-verification) states still require an explicit accept/decline action.
    var dismissedTerminal by remember(activeVerification) { mutableStateOf(false) }
    val isTerminal = state is ActiveVerificationState.Done ||
        state is ActiveVerificationState.Cancel ||
        state is ActiveVerificationState.AcceptedByOtherDevice ||
        state is ActiveVerificationState.Undefined
    if (dismissedTerminal && isTerminal) return

    Dialog(onDismissRequest = { if (isTerminal) dismissedTerminal = true }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = rememberCosmicTokens().Bg,
            contentColor = Color.White
        ) {
            Column(
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Device Verification",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(16.dp))

                when (val s = state) {
                    is ActiveVerificationState.TheirRequest -> {
                        Text("Incoming verification request from ${activeVerification.theirUserId.full}")
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { coroutineScope.launch { activeVerification.cancel() } },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) { Text("Decline") }
                            Button(
                                onClick = { coroutineScope.launch { s.ready() } }
                            ) { Text("Accept") }
                        }
                    }
                    is ActiveVerificationState.Ready -> {
                        Text("Preparing verification...")
                        LaunchedEffect(s) {
                            s.start(VerificationMethod.Sas)
                        }
                    }
                    is ActiveVerificationState.Start -> {
                        val method = s.method
                        if (method is ActiveSasVerificationMethod) {
                            val sasState by method.state.collectAsState()
                            when (val ss = sasState) {
                                is ActiveSasVerificationState.TheirSasStart -> {
                                    Text("Accepting SAS method...")
                                    LaunchedEffect(ss) {
                                        ss.accept()
                                    }
                                }
                                is ActiveSasVerificationState.ComparisonByUser -> {
                                    Text("Verify these emojis match the other device:")
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        ss.emojis.forEach { emojiPair ->
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(emojiPair.second, fontSize = 32.sp)
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                                        Button(
                                            onClick = { coroutineScope.launch { ss.noMatch() } },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) { Text("No Match") }
                                        Button(
                                            onClick = { coroutineScope.launch { ss.match() } },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34D399))
                                        ) { Text("Match") }
                                    }
                                }
                                is ActiveSasVerificationState.OwnSasStart,
                                is ActiveSasVerificationState.Accept,
                                is ActiveSasVerificationState.WaitForKeys,
                                is ActiveSasVerificationState.WaitForMacs -> {
                                    Text("Waiting for the other device...")
                                    CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                                }
                            }
                        } else {
                            Text("Unsupported verification method.")
                        }
                    }
                    is ActiveVerificationState.OwnRequest -> {
                        Text("Waiting for other device to accept...")
                        CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                    }
                    is ActiveVerificationState.WaitForDone -> {
                        Text("Completing verification...")
                        CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                    }
                    is ActiveVerificationState.Done -> {
                        Text("Verification Successful!", color = Color(0xFF34D399))
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { dismissedTerminal = true }) {
                            Text("Dismiss")
                        }
                    }
                    is ActiveVerificationState.Cancel -> {
                        Text("Verification Cancelled", color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(s.content.reason)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { dismissedTerminal = true }) {
                            Text("Close")
                        }
                    }
                    is ActiveVerificationState.AcceptedByOtherDevice -> {
                        Text("Accepted by another device.")
                    }
                    is ActiveVerificationState.Undefined -> {
                        Text("Verification state undefined.")
                    }
                }
            }
        }
    }
}
