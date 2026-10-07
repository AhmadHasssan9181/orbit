package ui.screen.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import util.OrbitLog
import ui.components.OrbitTextField
import ui.theme.rememberCosmicTokens

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

// Tenor v2 (Google API). The legacy g.tenor.com/v1 endpoints are dead.
private const val TENOR_CLIENT_KEY = "orbit"

@Serializable
data class TenorResponse(val results: List<TenorResult>)
@Serializable
data class TenorResult(@SerialName("media_formats") val mediaFormats: TenorMediaFormats)
@Serializable
data class TenorMediaFormats(val tinygif: TenorGif)
@Serializable
data class TenorGif(val url: String)

private val tenorJson = Json { ignoreUnknownKeys = true }

/** Minimal RFC 3986 unreserved-set percent encoding, safe in commonMain. */
private fun String.urlEncoded(): String = buildString {
    for (b in this@urlEncoded.encodeToByteArray()) {
        when (val c = b.toInt() and 0xFF) {
            in 'a'.code..'z'.code, in 'A'.code..'Z'.code, in '0'.code..'9'.code,
            '-'.code, '_'.code, '.'.code, '~'.code -> append(c.toChar())
            else -> append('%').append(c.toString(16).padStart(2, '0').uppercase())
        }
    }
}

@Composable
/**
 * The client used for every Tenor call in this picker.
 *
 * SECURITY: production passes [tenorHttpClient], which has explicit request,
 * connect and socket timeouts. The bare `HttpClient()` this used to receive has no
 * plugins installed at all, so a stalled third-party response left the picker in
 * `isLoading` forever. The byte caps live in TenorHttp.kt.
 *
 * The parameter is kept so the composable stays injectable for tests.
 */
fun GifPicker(commonHttpClient: HttpClient, onGifSelected: (ByteArray) -> Unit) {
    val tenorClient = commonHttpClient
    val t = rememberCosmicTokens()
    val scope = rememberCoroutineScope()
    val settingsManager = remember { repository.SettingsManager() }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var gifs by remember { mutableStateOf<List<String>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    // The Tenor key is user-provisioned (inline below); it is deliberately not
    // shipped in source control.
    var tenorApiKey by remember { mutableStateOf(settingsManager.tenorApiKeySync()) }

    LaunchedEffect(tenorApiKey, searchQuery) {
        if (tenorApiKey.isBlank()) {
            isLoading = false
            return@LaunchedEffect
        }
        // Restarting this effect per keystroke with a settle window gives us a
        // debounce: only the final value within 300 ms actually fetches.
        if (searchQuery.isNotBlank()) delay(300)
        isLoading = true
        errorMessage = null
        try {
            val url = if (searchQuery.isBlank()) {
                "https://tenor.googleapis.com/v2/featured?key=$tenorApiKey&client_key=$TENOR_CLIENT_KEY&media_filter=tinygif&limit=20"
            } else {
                "https://tenor.googleapis.com/v2/search?key=$tenorApiKey&client_key=$TENOR_CLIENT_KEY&media_filter=tinygif&limit=20&q=${searchQuery.trim().urlEncoded()}"
            }
            // SECURITY: bounded read + real timeouts. This used to be
            // `commonHttpClient.get(url)` + `response.readBytes()` on a bare
            // `HttpClient()` with no timeout plugin installed at all, so a
            // slow-streaming body was read into the heap without limit and a hung
            // connection hung the coroutine forever. See TenorHttp.kt.
            val payload = fetchTenorJson(tenorClient, url)
            val tenorRes = tenorJson.decodeFromString<TenorResponse>(payload.decodeToString())
            gifs = tenorRes.results.mapNotNull { it.mediaFormats.tinygif.url }
        } catch (e: Exception) {
            OrbitLog.e("GifPicker", "Tenor fetch failed: ${e.message}")
            errorMessage = "Couldn't load GIFs${e.message?.let { " ($it)" } ?: ""}. Check your Tenor API key."
        } finally {
            isLoading = false
        }
    }

    Surface(
        // Capped and floating rather than a 300dp slab that pushed the whole
        // composer down the screen. In the empty state (no API key) it is only
        // a couple of lines tall, because there is nothing to show.
        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
        color = t.Input_Bg,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            if (tenorApiKey.isBlank()) {
                // Compact, and the key field does NOT take focus. The old layout
                // filled the full 300dp with a form whose first field grabbed
                // keyboard focus, so opening the picker silently diverted
                // everything the user typed away from the message box.
                var keyInput by remember { mutableStateOf("") }
                Text(
                    "GIF search needs a free Tenor API key.",
                    color = t.Text_Muted,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OrbitTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        placeholder = "API key",
                        isPassword = true,
                        singleLine = true,
                        modifier = Modifier.width(260.dp)
                    )
                    Button(
                        onClick = {
                            val trimmed = keyInput.trim()
                            if (trimmed.isNotBlank()) {
                                settingsManager.setTenorApiKey(trimmed)
                                tenorApiKey = trimmed
                                isLoading = true
                            }
                        },
                        enabled = keyInput.isNotBlank()
                    ) {
                        Text("Save")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Get one at developers.google.com/tenor",
                    color = t.Text_Subtle,
                    style = MaterialTheme.typography.labelSmall
                )
            } else {
                OrbitTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "Search Tenor GIFs...",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
            }
            // Everything below only exists when there IS a key, so the panel
            // collapses to a couple of lines in the empty state instead of
            // reserving 320dp of blank space.
            if (tenorApiKey.isNotBlank()) {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 300.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        errorMessage != null -> Text(
                            errorMessage!!,
                            color = Color(0xFFD65A54),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        isLoading || isDownloading -> CircularProgressIndicator(color = t.Accent)

                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(100.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // Index-based key: Tenor can legitimately return the same
                            // GIF twice, and duplicate keys throw in a lazy grid.
                            items(gifs.size, key = { it }, contentType = { "gif" }) { index ->
                                val url = gifs[index]
                                Box(
                                    modifier = Modifier
                                        .height(100.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            scope.launch {
                                                isDownloading = true
                                                try {
                                                    // Bounded read, and a non-image response is rejected rather than
                                                    // handed to the uploader as if it were a GIF.
                                                    val bytes = downloadGifBounded(tenorClient, url)
                                                    onGifSelected(bytes)
                                                } catch (e: Exception) {
                                                    OrbitLog.e("GifPicker", "GIF download failed: ${e.message}")
                                                    errorMessage = "Couldn't download that GIF. Try again."
                                                } finally {
                                                    isDownloading = false
                                                }
                                            }
                                        }
                                ) {
                                    coil3.compose.AsyncImage(
                                        model = url,
                                        contentDescription = null,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
