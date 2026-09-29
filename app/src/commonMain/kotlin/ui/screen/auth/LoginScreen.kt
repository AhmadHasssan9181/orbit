package ui.screen.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
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
import repository.AuthStage
import repository.MatrixOrbitRepository
import repository.OrbitRepository
import ui.assets.OrbitBrandMark
import ui.components.OrbitButton
import ui.components.OrbitButtonTone
import ui.components.OrbitCallout
import ui.components.OrbitSegmentedControl
import ui.components.OrbitTextField
import ui.screen.server.SelfHostingScreen
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

    // Hoisted: performLogin used to build a fresh MatrixOrbitRepository on every
    // invocation, so the IME action and the key handler could each start a login.
    //
    // A failed login closes the repository's HttpClient and scope, so retrying
    // against the same instance failed with a Ktor internal error for the rest of
    // the process -- a wrong password bricked sign-in until the app restarted.
    // So the repository is rebuilt per attempt, and the PREVIOUS one is closed
    // rather than abandoned.
    //
    // Built inside remember(trimmedHomeserver, attempt), not in the composable
    // body: the earlier version called a constructor that assigned MutableState
    // and then nulled it from a LaunchedEffect, so every keystroke in the field
    // built a Ktor client (a socket pool + a selector thread), threw it away, and
    // leaked it. Constructing a repository also must not write state during
    // composition.
    //
    // `attempt` is bumped after a failed sign-in, which is what makes a RETRY
    // get a live client: the previous one was closed by its own failure path.
    val trimmedHomeserver = homeserverUrl.trimEnd('/')
    // Bumped after a failed attempt so the next try is not made against the
    // closed HttpClient that the failure just shut down.
    var attempt by remember { mutableIntStateOf(0) }

    // User-interactive-auth state. Almost every self-hosted Synapse refuses the
    // first /register with a 401 and a `flows` array, so this is the normal
    // path for a self-hoster, not an error state.
    var authStage by remember { mutableStateOf<repository.AuthStage?>(null) }
    var authSession by remember { mutableStateOf<String?>(null) }
    var pendingAuth by remember { mutableStateOf<Map<String, Map<String, String>>>(emptyMap()) }
    var pendingAuthSession by remember { mutableStateOf<String?>(null) }
    var acceptedPolicies by remember { mutableStateOf<Set<String>>(emptySet()) }
    var registrationToken by remember { mutableStateOf("") }

    val repoBuild = remember(trimmedHomeserver, attempt) {
        runCatching { MatrixOrbitRepository(trimmedHomeserver) }
    }
    val repoForAttempt = repoBuild.getOrNull()
    val homeserverError = repoBuild.exceptionOrNull()?.message ?: "Invalid homeserver URL"

    // Close the superseded repository: on URL change, on a retry after failure,
    // and when the screen goes away. Without this, a typo'd URL repeated a few
    // times accumulated CIO selector threads for the life of the process.
    DisposableEffect(repoForAttempt) {
        onDispose { runCatching { repoForAttempt?.close() } }
    }

    // Which flows this homeserver actually offers. Without this the SSO button
    // was shown unconditionally and failed on every server with no OIDC
    // provider configured -- which is most self-hosted servers.
    var loginFlows by remember { mutableStateOf<List<String>>(emptyList()) }
    var flowsProbed by remember { mutableStateOf(false) }
    LaunchedEffect(repoForAttempt) {
        if (repoForAttempt == null || flowsProbed) return@LaunchedEffect
        flowsProbed = true
        // CancellationException IS an Exception, so runCatching treats leaving
        // the composition as a discovery failure -- which logged a scary
        // "login flow discovery failed" on every single launch. A cancelled
        // probe is not a failed probe; let it propagate.
        val flows = try {
            repoForAttempt.getLoginFlows()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            println("DEBUG [LoginScreen] login flow discovery failed: ${e.message}")
            emptyList()
        }
        loginFlows = flows
    }
    // Empty means discovery failed or hasn't run yet, so offer SSO anyway rather
    // than hiding a path that might work.
    val supportsSso = loginFlows.isEmpty() || "m.login.sso" in loginFlows

    var isDiscovering by remember { mutableStateOf(false) }
    var showSelfHosting by remember { mutableStateOf(false) }
    var discoveryNote by remember { mutableStateOf<String?>(null) }

    val performLogin: () -> Unit = {
        val target = repoForAttempt
        if (target == null) {
            errorMessage = homeserverError ?: "Invalid homeserver URL"
            isLoading = false
        } else {
            startLoading("Connecting to $trimmedHomeserver…")

            if (isRegistering) {
                val username = identifier.removePrefix("@").split(":").first()
                val answers = pendingAuth
                val session = pendingAuthSession
                pendingAuth = emptyMap()
                pendingAuthSession = null
                scope.launch {
                    try {
                        val regResult = target.register(username, password, answers, session)
                        when (val stage = regResult.stage) {
                            is AuthStage.Complete -> {
                                target.login(identifier = username, password = password, deviceName = "Orbit")
                                onLoginSuccess(target)
                            }
                            else -> {
                                // Not a failure: a self-hosted Synapse almost
                                // always answers the first /register with a 401
                                // and a `flows` array, typically wanting
                                // m.login.dummy and often m.login.terms.
                                authStage = stage
                                authSession = regResult.authSession
                                isLoading = false
                            }
                        }
                    } catch (e: Exception) {
                        errorMessage = "Could not create that account: ${e.message}"
                        isLoading = false
                        attempt++
                    }
                }
            } else {
                scope.launch {
                    try {
                        target.login(identifier = identifier, password = password, deviceName = "Orbit")
                        onLoginSuccess(target)
                    } catch (e: Exception) {
                        errorMessage = "Sign in failed: ${e.message}"
                        isLoading = false
                        // login() closed the client on the way out, so the next
                        // attempt needs a fresh one.
                        attempt++
                    }
                }
            }
        }
    }

    val performSso: () -> Unit = {
        val target = repoForAttempt
        if (target == null) {
            errorMessage = homeserverError ?: "Invalid homeserver URL"
            isLoading = false
        } else {
            startLoading("Waiting for your browser…")
            scope.launch {
                try {
                    target.loginWithSso()
                    onLoginSuccess(target)
                } catch (e: Exception) {
                    errorMessage = "Browser sign in failed: ${e.message}"
                    isLoading = false
                    // An abandoned or failed SSO round-trip closed the client too.
                    attempt++
                }
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

                // Server discovery. The "continue in browser" SSO flow hands
                // back a URL, not a Matrix ID, so a self-hoster frequently has
                // a domain rather than a base URL -- and their homeserver is
                // often reachable at a name that is not the same as the
                // domain. This resolves the domain through
                // /.well-known/matrix/client, per the Matrix spec.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = OrbitSpace.xs)
                ) {
                    OrbitButton(
                        text = if (isDiscovering) "Looking…" else "Find my server",
                        onClick = {
                            val repo = repoForAttempt ?: return@OrbitButton
                            scope.launch {
                                isDiscovering = true
                                discoveryNote = null
                                when (val result = repo.discoverHomeserver(homeserverUrl)) {
                                    is repository.ServerDiscoveryResult.Delegated -> {
                                        homeserverUrl = result.baseUrl
                                        discoveryNote = "Found ${result.baseUrl}"
                                        attempt++   // rebuild the client for the new URL
                                    }
                                    is repository.ServerDiscoveryResult.NotDelegated -> {
                                        val host = repository.matrixServerNameFrom(homeserverUrl)
                                        if (host != null) {
                                            homeserverUrl = "https://$host"
                                            discoveryNote = "No delegation; using $host directly."
                                            attempt++
                                        }
                                    }
                                    is repository.ServerDiscoveryResult.Direct -> {
                                        homeserverUrl = result.baseUrl
                                        discoveryNote = "Using ${result.baseUrl}"
                                        attempt++
                                    }
                                    is repository.ServerDiscoveryResult.Malformed ->
                                        discoveryNote = result.detail
                                    is repository.ServerDiscoveryResult.Unreachable ->
                                        discoveryNote = result.detail
                                }
                                isDiscovering = false
                            }
                        },
                        tone = OrbitButtonTone.Neutral,
                        enabled = !isDiscovering && homeserverUrl.isNotBlank(),
                        size = 36.dp
                    )
                    discoveryNote?.let { note ->
                        Spacer(Modifier.width(OrbitSpace.sm))
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = t.Text_Muted,
                                fontSize = 12.sp
                            )
                        )
                    }
                }

                // Entry point to the self-hosting guide.
                //
                // The deployment bundle (deploy/: Tailscale, public-domain and
                // Tuwunel stacks, Synapse + LiveKit + coturn) has existed for a
                // while but was unreachable from the UI -- you had to already know
                // the project shipped a deploy/ directory. A feature nobody can
                // find is not a feature.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = OrbitSpace.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Running your own homeserver?",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = t.Text_Muted,
                            fontSize = 12.sp
                        ),
                        modifier = Modifier
                            .clip(OrbitRadius.xsShape)
                            .clickable { showSelfHosting = true }
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.width(OrbitSpace.xs))
                    Text(
                        text = "Get the setup",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = t.AccentLight,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier
                            .clip(OrbitRadius.xsShape)
                            .clickable { showSelfHosting = true }
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }

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
                    // onImeAction ONLY, no companion onKeyEvent. The field is
                    // singleLine with a Done IME action, so Compose Desktop already
                    // fires this on Enter; a second Enter handler on the same press
                    // started a second concurrent login coroutine (in register
                    // mode, two concurrent account-creation requests). The isLoading
                    // guard does not help -- both run before the recomposition that
                    // sets it.
                    onImeAction = {
                        if (!isLoading && identifier.isNotBlank() && password.isNotBlank()) performLogin()
                    },
                    modifier = Modifier.fillMaxWidth()
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

                // ── User-interactive auth ────────────────────────────────
                // A self-hosted Synapse answers the first /register with a 401
                // and a `flows` array (typically m.login.dummy, often plus
                // m.login.terms). Before this, any such server reported
                // "Registration failed" and self-hosting looked broken.
                authStage?.let { stage ->
                    when (stage) {
                        is AuthStage.Dummy -> {
                            OrbitCallout(
                                text = "This homeserver needs to confirm you're not a bot before it will create an account.",
                                icon = Icons.Default.Info,
                                tone = OrbitButtonTone.Neutral
                            )
                            Spacer(Modifier.height(OrbitSpace.xs))
                            OrbitButton(
                                text = "I'm not a bot",
                                onClick = {
                                    pendingAuth = mapOf("m.login.dummy" to emptyMap())
                                    pendingAuthSession = authSession
                                    authStage = null
                                    performLogin()
                                },
                                tone = OrbitButtonTone.Primary,
                                size = 40.dp,
                                fullWidth = true
                            )
                        }

                        is AuthStage.Terms -> {
                            OrbitCallout(
                                text = "This homeserver asks you to accept its policies first.",
                                icon = Icons.Default.Info,
                                tone = OrbitButtonTone.Neutral
                            )
                            Spacer(Modifier.height(OrbitSpace.xs))
                            stage.policies.forEach { policy ->
                                val key = "${policy.name}:${policy.version}"
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                ) {
                                    androidx.compose.material3.Checkbox(
                                        checked = key in acceptedPolicies,
                                        onCheckedChange = { checked ->
                                            acceptedPolicies =
                                                if (checked) acceptedPolicies + key
                                                else acceptedPolicies - key
                                        }
                                    )
                                    Spacer(Modifier.width(OrbitSpace.xs))
                                    Text(
                                        text = if (policy.version.isBlank()) policy.name
                                        else "${policy.name} (v${policy.version})",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = t.Text_White,
                                        modifier = Modifier.clickable { util.openUrlInBrowser(policy.url) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(OrbitSpace.xs))
                            OrbitButton(
                                text = "Accept and continue",
                                onClick = {
                                    pendingAuth = mapOf(
                                        "m.login.terms" to mapOf("accepted" to "true")
                                    )
                                    pendingAuthSession = authSession
                                    authStage = null
                                    performLogin()
                                },
                                tone = OrbitButtonTone.Primary,
                                enabled = stage.policies.isEmpty() ||
                                    stage.policies.all { "${it.name}:${it.version}" in acceptedPolicies },
                                size = 40.dp,
                                fullWidth = true
                            )
                        }

                        is AuthStage.RegistrationToken -> {
                            OrbitCallout(
                                text = stage.reason?.takeIf { it.isNotBlank() }
                                    ?: "This homeserver requires a registration token. Ask its administrator for one.",
                                icon = Icons.Default.Info,
                                tone = OrbitButtonTone.Neutral
                            )
                            Spacer(Modifier.height(OrbitSpace.xs))
                            OrbitTextField(
                                value = registrationToken,
                                onValueChange = { registrationToken = it },
                                placeholder = "Registration token",
                                imeAction = ImeAction.Done,
                                onImeAction = {
                                    if (registrationToken.isNotBlank()) {
                                        pendingAuth = mapOf(
                                            "m.login.registration_token" to mapOf("token" to registrationToken)
                                        )
                                        pendingAuthSession = authSession
                                        authStage = null
                                        performLogin()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(OrbitSpace.xs))
                            OrbitButton(
                                text = "Continue",
                                onClick = {
                                    pendingAuth = mapOf(
                                        "m.login.registration_token" to mapOf("token" to registrationToken)
                                    )
                                    pendingAuthSession = authSession
                                    authStage = null
                                    performLogin()
                                },
                                tone = OrbitButtonTone.Primary,
                                enabled = registrationToken.isNotBlank(),
                                size = 40.dp,
                                fullWidth = true
                            )
                        }

                        is AuthStage.Recaptcha -> {
                            // Honest about not being able to render Google's
                            // widget rather than looping on a captcha we cannot
                            // show.
                            OrbitCallout(
                                text = "This homeserver requires a captcha, which Orbit cannot display. " +
                                    "Create the account once in the homeserver's own web client, then sign in here.",
                                icon = Icons.Default.ErrorOutline,
                                tone = OrbitButtonTone.Danger
                            )
                        }

                        is AuthStage.Unsupported -> OrbitCallout(
                            text = "This homeserver requires an extra sign-in step (${stage.stageType}) " +
                                "that Orbit cannot complete. Finish setting up the account in the " +
                                "homeserver's own web client, then sign in here.",
                            icon = Icons.Default.ErrorOutline,
                            tone = OrbitButtonTone.Danger
                        )

                        is AuthStage.Malformed -> OrbitCallout(
                            text = stage.detail,
                            icon = Icons.Default.ErrorOutline,
                            tone = OrbitButtonTone.Danger
                        )

                        AuthStage.Complete -> Unit
                    }
                    Spacer(Modifier.height(OrbitSpace.xs))
                }

                if (authStage == null) {
                    OrbitButton(
                        text = if (isLoading) loadingLabel.ifBlank { "Working…" } else if (isRegistering) "Create account" else "Sign in",
                        onClick = performLogin,
                        tone = OrbitButtonTone.Primary,
                        enabled = !isLoading && identifier.isNotBlank() && password.isNotBlank(),
                        loading = isLoading,
                        size = 40.dp,
                        fullWidth = true
                    )

                    if (supportsSso) {
                        OrbitButton(
                            text = "Continue in browser",
                            onClick = performSso,
                            tone = OrbitButtonTone.Neutral,
                            enabled = !isLoading,
                            size = 40.dp,
                            fullWidth = true
                        )
                    }
                }

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

        // Self-hosting overlay. A sibling of the Row inside the same
        // BoxWithConstraints, so it covers the whole window rather than living
        // inside the scrolling login card -- it has its own scroll and its own
        // Back button, and it needs the full window width to be readable.
        if (showSelfHosting) {
            SelfHostingScreen(
                homeserverUrl = homeserverUrl,
                onBack = { showSelfHosting = false },
                modifier = Modifier.fillMaxSize()
            )
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
