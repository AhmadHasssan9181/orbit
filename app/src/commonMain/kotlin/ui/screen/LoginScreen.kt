package ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import kotlinx.coroutines.launch
import repository.AuthFlow
import repository.MatrixOrbitRepository
import repository.OrbitRepository

/**
 * Login screen for Matrix authentication.
 * Supports password login, SSO via browser, and registration.
 */
@Composable
fun LoginScreen(
    onLoginSuccess: (OrbitRepository) -> Unit,
    modifier: Modifier = Modifier
) {
    var homeserverUrl by remember { mutableStateOf("https://matrix.org") }
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isRegistering by remember { mutableStateOf(false) }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingLabel by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    val performLogin: () -> Unit = {
        isLoading = true
        loadingLabel = ""
        errorMessage = null
        val repo = MatrixOrbitRepository(homeserverUrl.trimEnd('/'))

        if (isRegistering) {
            val username = identifier.removePrefix("@").split(":").first()
            scope.launch {
                try {
                    val regResult = repo.register(username, password)
                    if (regResult.authFlow is AuthFlow.Password) {
                        repo.login(
                            identifier = username,
                            password = password,
                            deviceName = "Orbit"
                        )
                        onLoginSuccess(repo)
                    } else {
                        errorMessage = "Registration requires additional flow"
                        isLoading = false
                    }
                } catch (e: Exception) {
                    errorMessage = "Register failed: ${e.message}"
                    isLoading = false
                }
            }
        } else {
            scope.launch {
                try {
                    repo.login(
                        identifier = identifier,
                        password = password,
                        deviceName = "Orbit"
                    )
                    onLoginSuccess(repo)
                } catch (e: Exception) {
                    errorMessage = "Login failed: ${e.message}"
                    isLoading = false
                }
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Orbit",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Matrix Client",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = homeserverUrl,
                onValueChange = { homeserverUrl = it },
                label = { Text("Homeserver URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = identifier,
                onValueChange = { identifier = it },
                label = { Text("Username or Matrix ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth().onKeyEvent { event ->
                    if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                        if (identifier.isNotBlank() && password.isNotBlank()) {
                            performLogin()
                        }
                        true
                    } else false
                },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        if (identifier.isNotBlank() && password.isNotBlank()) {
                            performLogin()
                        }
                    }
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            errorMessage?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (isLoading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    if (loadingLabel.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = loadingLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = performLogin,
                            enabled = identifier.isNotBlank() && password.isNotBlank()
                        ) {
                            Text(if (isRegistering) "Register" else "Login")
                        }

                        OutlinedButton(
                            onClick = { isRegistering = !isRegistering }
                        ) {
                            Text(if (isRegistering) "Switch to Login" else "Switch to Register")
                        }
                    }

                    // SSO Login button — opens browser for authentication
                    OutlinedButton(
                        onClick = {
                            isLoading = true
                            loadingLabel = "Waiting for browser login..."
                            errorMessage = null
                            val repo = MatrixOrbitRepository(homeserverUrl.trimEnd('/'))

                            scope.launch {
                                try {
                                    repo.loginWithSso()
                                    onLoginSuccess(repo)
                                } catch (e: Exception) {
                                    errorMessage = "SSO login failed: ${e.message}"
                                    isLoading = false
                                }
                            }
                            Unit
                        }
                    ) {
                        Text("\uD83C\uDF10  Login with Browser (SSO)")
                    }
                }
            }
        }
    }
}