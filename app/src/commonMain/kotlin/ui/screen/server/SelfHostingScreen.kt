package ui.screen.server

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.TablerIcons
import ui.icons.Check
import ui.icons.Copy
import ui.icons.Server
import ui.components.OrbitButton
import ui.components.OrbitButtonTone
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens
/**
 * "Host your own server", in the app.
 *
 * ## Why this screen exists
 *
 * The full deployment bundle already lives in `deploy/` -- a Tailscale option, a
 * public-domain option, a Tuwunel option, Synapse + LiveKit + coturn compose
 * files, and a README walking through all of it. None of it was reachable from
 * the UI. Someone who wanted to self-host had to already know the project had a
 * `deploy/` directory and go read it, which means the feature was effectively
 * invisible: you cannot use what you cannot find.
 *
 * So this screen does not try to replace the README. It does the three things a
 * README cannot do:
 *
 *  1. **It generates the exact commands and config for YOUR setup.** The
 *     `.well-known` body, the Tailscale `serve` line, the Synapse
 *     `extra_well_known_client_content` block -- all built from the homeserver
 *     you typed on the login screen, so there is nothing to fill in by hand and
 *     nothing to get subtly wrong.
 *  2. **It puts each block on the clipboard in one click.** Copy/paste beats
 *     retyping a JSON body into an nginx config at 1am.
 *  3. **It states the limitations plainly**, because the ones that matter are
 *     counter-intuitive:
 *
 *       - Tailscale carries Matrix signalling perfectly but CANNOT proxy
 *         LiveKit's UDP range (7882 and 50000-50200). A participant outside
 *         your tailnet will not be able to join a call without a public TURN
 *         relay. This surprises people and there is no way around it.
 *       - A homeserver at a SUBDIRECTORY is a trap. Synapse's subpath support
 *         has broken more than once, and `serve_server_wellknown` ignores
 *         `public_baseurl`, so it will look configured and then not work.
 *         Serve the homeserver at a domain root, not under a path.
 *       - matrix.org's default call focus is Jitsi, not LiveKit. Orbit joins
 *         LiveKit calls. An Element-started call on such a server is visible to
 *         Orbit but not joinable until the call is started from a LiveKit focus.
 *
 * ## Honesty about what this does not do
 *
 * It does not install anything, deploy anything, or talk to your server. It
 * generates text and copies it to the clipboard. Pretending otherwise would be
 * worse than useless, because a button that says "Set up my server" and does not
 * set up a server teaches people to distrust the rest of the app.
 */
@Composable
fun SelfHostingScreen(
    homeserverUrl: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = rememberCosmicTokens()
    val clipboard = LocalClipboardManager.current

    // Normalise what the login screen has so the generated blocks are valid.
    // A trailing slash produces `https://host//_matrix/...`, which 404s in a way
    // that looks like a server problem, so it is stripped here rather than left
    // for the reader to notice.
    val host = remember(homeserverUrl) {
        homeserverUrl.trim().trimEnd('/').removePrefix("https://").removePrefix("http://")
    }
    val wellKnownBody = remember(host) {
        """
        {
          "m.homeserver": {
            "base_url": "https://$host"
          }
        }
        """.trimIndent()
    }
    val synapseBlock = remember(host) {
        """
        extra_well_known_client_content:
          "m.call": {
            "foci": [
              {
                "type": "livekit",
                "livekit_service_url": "https://livekit.$host"
              }
            ]
          }
          "im.vector.modular.widgets": []
        """.trimIndent()
    }
    val tailscaleServe = remember(host) {
        """
        tailscale serve --bg 443 https://localhost:8008
        tailscale cert $host
        """.trimIndent()
    }
    val coturnNote = remember(host) {
        """
        # LiveKit cannot be reached over Tailscale for non-tailnet participants.
        # Add a public TURN relay and set it in livekit.yaml:
        #   turn:
        #     enabled: true
        #     domain: turn.$host
        #     port: 3478
        #     static_auth_secret: <from your .env>
        """.trimIndent()
    }

    Column(modifier = modifier.fillMaxSize().background(t.Bg)) {

        // ── Header ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(t.Header_Bg)
                .padding(horizontal = OrbitSpace.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(OrbitRadius.smShape)
                    .background(t.Sunken),
                contentAlignment = Alignment.Center
            ) {
                Icon(with(TablerIcons) { Server }, contentDescription = null, tint = t.Text_Muted, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(OrbitSpace.sm))
            Text(
                text = "Host your own server",
                style = MaterialTheme.typography.titleSmall.copy(
                    color = t.Text_White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                ),
                modifier = Modifier.weight(1f, fill = false)
            )
            OrbitButton(
                text = "Back",
                onClick = onBack,
                tone = OrbitButtonTone.Neutral,
                size = 30.dp
            )
        }
        HorizontalDivider(color = t.Separator, thickness = 1.dp)

        if (host.isBlank()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Enter a homeserver above first — the steps below are built from it.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = t.Text_Muted),
                    modifier = Modifier.widthIn(max = 420.dp).padding(OrbitSpace.xl)
                )
            }
            return@Column
        }

        // ── Body ────────────────────────────────────────────────────────
        Row(modifier = Modifier.fillMaxSize()) {

            // Explanation column.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = OrbitSpace.xl, vertical = OrbitSpace.lg),
                verticalArrangement = Arrangement.spacedBy(OrbitSpace.md)
            ) {
                Text(
                    text = "For $host",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = t.Text_White, fontWeight = FontWeight.SemiBold
                    )
                )
                Text(
                    text = "Orbit talks to two independent servers: a Matrix homeserver for " +
                        "chat, and a LiveKit SFU for calls. A self-hosted setup needs both, " +
                        "plus TURN for media when participants are outside your network.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = t.Text_Secondary, lineHeight = 20.sp)
                )

                Section(
                    title = "1 · Tailscale — private, for a group you control",
                    body = "Tailscale gives every device a real HTTPS name and a real Let's " +
                        "Encrypt certificate, so the MagicDNS name can be your Matrix server " +
                        "name. No public DNS, no certbot, no exposed ports. This is the shortest " +
                        "path to a working private server.",
                    bodyColor = t.Text_Secondary,
                    snippet = "Serve the homeserver and take a certificate, then point Synapse " +
                        "at it. Run the stack in the deploy/ directory:\n" +
                        "  cd deploy && docker compose up -d",
                    snippetLang = "shell",
                    onCopy = { clipboard.setText(AnnotatedString(tailscaleServe)) },
                    copiedFlag = "serve"
                )

                Section(
                    title = "2 · The .well-known file",
                    body = "This is how Orbit — and every other Matrix client — finds your " +
                        "homeserver from just the domain. Serve it at " +
                        "https://$host/.well-known/matrix/client. Without it, signing in with " +
                        "your domain fails even though the homeserver itself is healthy.",
                    bodyColor = t.Text_Secondary,
                    snippet = wellKnownBody,
                    snippetLang = "json",
                    onCopy = { clipboard.setText(AnnotatedString(wellKnownBody)) },
                    copiedFlag = "wellknown"
                )

                Section(
                    title = "3 · Tell Synapse about the call stack",
                    body = "Without this the rooms work but calls never appear. Add it to " +
                        "homeserver.yaml alongside server_name, then restart Synapse.",
                    bodyColor = t.Text_Secondary,
                    snippet = synapseBlock,
                    snippetLang = "yaml",
                    onCopy = { clipboard.setText(AnnotatedString(synapseBlock)) },
                    copiedFlag = "synapse"
                )

                Section(
                    title = "4 · Calls from outside your network",
                    body = "This is the part that surprises people. Tailscale carries Matrix " +
                        "signalling fine, but it cannot proxy LiveKit's UDP range " +
                        "(7882 and 50000–50200). Anyone outside your tailnet can read chat and " +
                        "will simply not be able to join a call unless you run a public TURN " +
                        "relay.",
                    bodyColor = t.Text_Muted,
                    snippet = coturnNote,
                    snippetLang = "yaml",
                    onCopy = { clipboard.setText(AnnotatedString(coturnNote)) },
                    copiedFlag = "coturn"
                )

                HorizontalDivider(color = t.Separator, modifier = Modifier.padding(vertical = OrbitSpace.sm))

                WarningCard(
                    heading = "Do not serve Synapse from a subdirectory",
                    body = "Putting the homeserver at https://$host/matrix looks tidy and does " +
                        "not work reliably. Synapse's subpath support has broken more than " +
                        "once, and serve_server_wellknown ignores public_baseurl, so the " +
                        "configuration looks right and then silently fails. Use a domain root.",
                    t = t
                )

                WarningCard(
                    heading = "matrix.org's default calls are Jitsi, not LiveKit",
                    body = "Orbit joins LiveKit calls. On a server whose default focus is Jitsi, " +
                        "a call started from another client shows up in Orbit but cannot be " +
                        "joined. Start the call from Orbit, or configure the LiveKit focus as " +
                        "in step 3.",
                    t = t
                )

                Spacer(Modifier.height(OrbitSpace.lg))
            }

            VerticalRule(t.Separator)

            // Step-by-step column.
            Column(
                modifier = Modifier
                    .width(360.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = OrbitSpace.lg, vertical = OrbitSpace.lg),
                verticalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
            ) {
                Text(
                    text = "CHECKLIST",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = t.Text_Subtle, fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp, letterSpacing = 0.8.sp
                    )
                )
                ChecklistItem(t, "Point a Tailscale (or DNS) name at $host")
                ChecklistItem(t, "docker compose up -d in deploy/")
                ChecklistItem(t, "Serve /.well-known/matrix/client")
                ChecklistItem(t, "Add extra_well_known_client_content to Synapse")
                ChecklistItem(t, "Register coturn if callers are off-tailnet")
                ChecklistItem(t, "Create the first account with register_new_matrix_user")
                ChecklistItem(t, "Sign in from Orbit with your full Matrix ID")
                Spacer(Modifier.height(OrbitSpace.sm))
                Text(
                    text = "Full walkthrough: deploy/README.md",
                    style = MaterialTheme.typography.bodySmall.copy(color = t.Text_Muted, fontSize = 12.sp)
                )
            }
        }
    }
}

@Composable
private fun VerticalRule(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(color)
    )
}

/**
 * A titled block of prose plus a copyable snippet.
 *
 * The copy button reports what it copied by name rather than showing a toast,
 * because "did that actually copy?" is the question you ask every single time,
 * and a button that quietly succeeds is indistinguishable from a dead one.
 */
@Composable
private fun Section(
    title: String,
    body: String,
    bodyColor: androidx.compose.ui.graphics.Color,
    snippet: String,
    snippetLang: String,
    onCopy: () -> Unit,
    copiedFlag: String,
) {
    val t = rememberCosmicTokens()
    var copied by remember(copiedFlag) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            // Background only, no ring. The card fill is what separates a section
            // from the page behind it, and the 1dp border on top of that fill was
            // a second edge saying the same thing -- then the snippet well inside
            // it drew a third. One surface per level is enough.
            .background(t.CardBg)
            .padding(OrbitSpace.md)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = t.Text_White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
            )
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall.copy(color = bodyColor, fontSize = 12.sp, lineHeight = 18.sp)
        )
        Spacer(Modifier.height(OrbitSpace.sm))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                // Sunken, not ringed: the code well is a recess inside the card,
                // and a recessed surface already reads without an outline.
                .background(t.Sunken)
                .padding(OrbitSpace.sm)
        ) {
            // Horizontal scroll, never wrap. A wrapped JSON or YAML block is no
            // longer something you can paste; indenting is part of the syntax.
            // The scrollbar is visible so it is discoverable that it scrolls.
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = t.Text_Secondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            )
        }
        Spacer(Modifier.height(OrbitSpace.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = snippetLang,
                style = MaterialTheme.typography.labelSmall.copy(color = t.Text_Subtle, fontSize = 10.sp)
            )
            Spacer(Modifier.weight(1f))
            OrbitButton(
                text = if (copied) "Copied" else "Copy",
                icon = if (copied) with(TablerIcons) { Check } else with(TablerIcons) { Copy },
                onClick = {
                    onCopy()
                    copied = true
                },
                tone = if (copied) OrbitButtonTone.Primary else OrbitButtonTone.Neutral,
                size = 28.dp
            )
        }
    }
}

/**
 * Warning callout. The one place on this screen where a ring earns its keep: the
 * amber outline *is* the warning signal, and the plain sections next to it are
 * now fill-only, so a bordered amber block reads as deliberately different
 * rather than as just another card.
 */
@Composable
private fun WarningCard(
    heading: String,
    body: String,
    t: ui.theme.CosmicTokens,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(t.Warning.copy(alpha = 0.08f))
            .border(1.dp, t.Warning.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(OrbitSpace.md)
    ) {
        Text(
            text = heading,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = t.Warning, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
            )
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall.copy(color = t.Text_Muted, fontSize = 12.sp, lineHeight = 18.sp)
        )
    }
}

@Composable
private fun ChecklistItem(t: ui.theme.CosmicTokens, label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp, end = OrbitSpace.sm)
                .size(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, t.SeparatorStrong, RoundedCornerShape(4.dp))
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(color = t.Text_Secondary, fontSize = 12.sp, lineHeight = 17.sp)
        )
    }
}
