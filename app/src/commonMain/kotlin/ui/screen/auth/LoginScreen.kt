package ui.screen.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import repository.AuthFlow
import repository.MatrixOrbitRepository
import repository.OrbitRepository
import ui.assets.OrbitBrandMark
import ui.components.OrbitButton
import ui.components.OrbitButtonTone
import ui.components.OrbitCallout
import ui.components.OrbitSegmentedControl
import ui.components.OrbitTextField
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * Sign in.
 *
 * This was three full-width `OutlinedTextField`s and three Material buttons
 * stretched across the entire window — the default Android form, dropped into a
 * desktop app, with the word "Orbit" as the only branding. Everything below the
 * fold was also laid out at phone width, so on a 2560px monitor the fields ran
 * for two-thirds of the screen.
 *
 * Now: a proper two-column desktop sign-in. Brand and a line of context on the
 * left, a width-capped form card on the right, which collapses to one column
 * on a narrow window. Errors are a callout rather than a line of red text, and
 * the primary action carries its own loading state.
 */
@Composable
fun LoginScreen(
    onLoginSuccess: (OrbitRepository) -> Unit,
    modifier: Modifier = Modifier
) {
    val t = rememberCosmicTokens()
    var homeserverUrl by remember { mutableStateOf("https://matrix.org") }
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isRegistering by remember { mutableStateOf(false) }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingLabel by remember { mutableStateOf("") }

    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun startLoading(label: String) {
        isLoading = true
        loadingLabel = label
        errorMessage = null
    }

    val performLogin: () -> Unit = {
        startLoading("Connecting to ${homeserverUrl.trimEnd('/')}…")
        val repo = MatrixOrbitRepository(homeserverUrl.trimEnd('/'))

        if (isRegistering) {
            val username = identifier.removePrefix("@").split(":").first()
            scope.launch {
                try {
                    val regResult = repo.register(username, password)
                    if (regResult.authFlow is AuthFlow.Password) {
                        repo.login(identifier = username, password = password, deviceName = "Orbit")
                        onLoginSuccess(repo)
                    } else {
                        errorMessage = "This homeserver needs an extra registration step. Sign in and create the account from its own web client."
                        isLoading = false
                    }
                } catch (e: Exception) {
                    errorMessage = "Could not create that account: ${e.message}"
                    isLoading = false
                }
            }
        } else {
            scope.launch {
                try {
                    repo.login(identifier = identifier, password = password, deviceName = "Orbit")
                    onLoginSuccess(repo)
                } catch (e: Exception) {
                    errorMessage = "Sign in failed: ${e.message}"
                    isLoading = false
                }
            }
        }
    }

    val performSso: () -> Unit = {
        startLoading("Waiting for your browser…")
        val repo = MatrixOrbitRepository(homeserverUrl.trimEnd('/'))
        scope.launch {
            try {
                repo.loginWithSso()
                onLoginSuccess(repo)
            } catch (e: Exception) {
                errorMessage = "Browser sign in failed: ${e.message}"
                isLoading = false
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(t.Bg)
    ) {
        val wide = maxWidth >= 900.dp

        Row(modifier = Modifier.fillMaxSize()) {
        if (wide) {
            // -- Brand column -----------------------------------
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                t.Bg,
                                t.Accent.copy(alpha = 0.06f),
                                t.Bg
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 380.dp).padding(OrbitSpace.huge),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OrbitBrandMark(modifier = Modifier.size(38.dp))
                        Spacer(Modifier.width(OrbitSpace.md))
                        Text(
                            text = "Orbit",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = t.Text_White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp,
                                letterSpacing = 0.2.sp
                            )
                        )
                    }
                    Spacer(Modifier.height(OrbitSpace.xxl))
                    Text(
                        text = "Conversations and calls, on your own homeserver.",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = t.Text_White,
                            fontWeight = FontWeight.Normal,
                            fontSize = 26.sp,
                            lineHeight = 34.sp,
                            letterSpacing = (-0.4).sp
                        )
                    )
                    Spacer(Modifier.height(OrbitSpace.md))
                    Text(
                        text = "End-to-end encrypted messaging, group calls and screen sharing — pointed at any Matrix homeserver you like.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = t.Text_Muted,
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp
                        )
                    )
                }
            }
        }

        // ── Form column ────────────────────────────────────────
        Box(
            modifier = Modifier
                .then(if (wide) Modifier.width(480.dp).fillMaxHeight() else Modifier.fillMaxSize()),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = 380.dp).padding(OrbitSpace.xxxl),
                verticalArrangement = Arrangement.spacedBy(OrbitSpace.md)
            ) {
                if (!wide) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OrbitBrandMark(modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(OrbitSpace.sm))
                        Text(
                            text = "Orbit",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = t.Text_White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        )
                    }
                    Spacer(Modifier.height(OrbitSpace.sm))
                }

                Text(
                    text = if (isRegistering) "Create an account" else "Sign in",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = t.Text_White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 21.sp,
                        letterSpacing = (-0.2).sp
                    )
                )
                Text(
                    text = if (isRegistering) {
                        "Register on the homeserver below. You can change your display name later."
                    } else {
                        "Use your Matrix ID, or just your username on that homeserver."
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = t.Text_Muted,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp
                    )
                )

                Spacer(Modifier.height(OrbitSpace.xs))

                FieldLabel("Homeserver")
                OrbitTextField(
                    value = homeserverUrl,
                    onValueChange = { homeserverUrl = it },
                    placeholder = "https://matrix.org",
                    leadingIcon = Icons.Default.Language,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )

                FieldLabel(if (isRegistering) "Desired username" else "Username or Matrix ID")
                OrbitTextField(
                    value = identifier,
                    onValueChange = { identifier = it },
                    placeholder = if (isRegistering) "alice" else "@alice:example.org",
                    leadingIcon = Icons.Default.Person,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )

                FieldLabel("Password")
                OrbitTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "••••••••",
                    leadingIcon = Icons.Default.Lock,
                    isPassword = true,
                    imeAction = ImeAction.Done,
                    onImeAction = {
                        if (identifier.isNotBlank() && password.isNotBlank()) performLogin()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onKeyEvent { event ->
                            if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                                if (identifier.isNotBlank() && password.isNotBlank()) performLogin()
                                true
                            } else {
                                false
                            }
                        }
                )

                AnimatedVisibility(
                    visible = errorMessage != null,
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(120))
                ) {
                    errorMessage?.let {
                        OrbitCallout(
                            text = it,
                            icon = Icons.Default.ErrorOutline,
                            tone = OrbitButtonTone.Danger
                        )
                    }
                }

                Spacer(Modifier.height(OrbitSpace.xs))

                OrbitButton(
                    text = if (isLoading) loadingLabel.ifBlank { "Working…" } else if (isRegistering) "Create account" else "Sign in",
                    onClick = performLogin,
                    tone = OrbitButtonTone.Primary,
                    enabled = identifier.isNotBlank() && password.isNotBlank(),
                    loading = isLoading,
                    size = 40.dp,
                    fullWidth = true
                )

                OrbitButton(
                    text = "Continue in browser",
                    onClick = performSso,
                    tone = OrbitButtonTone.Neutral,
                    enabled = !isLoading,
                    size = 40.dp,
                    fullWidth = true
                )

                Spacer(Modifier.height(OrbitSpace.xs))

                OrbitSegmentedControl(
                    options = listOf(false, true),
                    selected = isRegistering,
                    onSelect = { isRegistering = it },
                    label = { if (it) "Register" else "Sign in" },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    val t = rememberCosmicTokens()
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            color = t.Text_Subtle,
            fontWeight = FontWeight.SemiBold,
            fontSize = 9.5.sp,
            letterSpacing = 0.7.sp
        )
    )
}
