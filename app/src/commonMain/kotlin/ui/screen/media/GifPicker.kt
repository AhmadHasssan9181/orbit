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
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import util.OrbitLog
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
fun GifPicker(commonHttpClient: HttpClient, onGifSelected: (ByteArray) -> Unit) {
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
            val response: HttpResponse = commonHttpClient.get(url)
            if (response.status.value !in 200..299) {
                throw IllegalStateException("Tenor responded ${response.status.value}")
            }
            val tenorRes = tenorJson.decodeFromString<TenorResponse>(response.bodyAsText())
            gifs = tenorRes.results.mapNotNull { it.mediaFormats.tinygif.url }
        } catch (e: Exception) {
            OrbitLog.e("GifPicker", "Tenor fetch failed: ${e.message}")
            errorMessage = "Couldn't load GIFs${e.message?.let { " ($it)" } ?: ""}. Check your Tenor API key."
        } finally {
            isLoading = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().height(300.dp),
        color = t.Input_Bg,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search Tenor GIFs...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = t.Accent,
                    unfocusedIndicatorColor = Color.Gray
                )
            )
            Spacer(Modifier.height(8.dp))
            if (tenorApiKey.isBlank()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Tenor API key not set — paste one below to browse GIFs.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    var keyInput by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            placeholder = { Text("API key") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.width(260.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = t.Accent,
                                unfocusedIndicatorColor = Color.Gray
                            )
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
                        "Free key: developers.google.com/tenor",
                        color = Color.Gray.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            } else if (errorMessage != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        errorMessage!!,
                        color = Color(0xFFD65A54),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            } else if (isLoading || isDownloading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = t.Accent)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(100.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(gifs.size) { index ->
                        val url = gifs[index]
                        Box(
                            modifier = Modifier
                                .height(100.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch {
                                        isDownloading = true
                                        try {
                                            val response: HttpResponse = commonHttpClient.get(url)
                                            if (response.status.value !in 200..299) {
                                                throw IllegalStateException("Download failed ${response.status.value}")
                                            }
                                            onGifSelected(response.readBytes())
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
