package ui.screen

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
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readBytes
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ui.theme.rememberCosmicTokens

@Serializable
data class TenorResponse(val results: List<TenorResult>)
@Serializable
data class TenorResult(val media: List<TenorMedia>)
@Serializable
data class TenorMedia(val tinygif: TenorGif)
@Serializable
data class TenorGif(val url: String)

private val tenorJson = Json { ignoreUnknownKeys = true }
private const val TENOR_API_KEY = "LIVDSRZULELA"

@Composable
fun GifPicker(commonHttpClient: HttpClient, onGifSelected: (ByteArray) -> Unit) {
    val t = rememberCosmicTokens()
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var gifs by remember { mutableStateOf<List<String>>(emptyList()) }
    var isDownloading by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery) {
        isLoading = true
        try {
            val url = if (searchQuery.isBlank()) {
                "https://g.tenor.com/v1/trending?key=$TENOR_API_KEY&limit=20"
            } else {
                "https://g.tenor.com/v1/search?key=$TENOR_API_KEY&q=$searchQuery&limit=20"
            }
            val response: HttpResponse = commonHttpClient.get(url)
            val jsonText = response.bodyAsText()
            val tenorRes = tenorJson.decodeFromString<TenorResponse>(jsonText)
            gifs = tenorRes.results.mapNotNull { it.media.firstOrNull()?.tinygif?.url }
        } catch (e: Exception) {
            e.printStackTrace()
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
            if (isLoading || isDownloading) {
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
                                            val bytes: ByteArray = response.readBytes()
                                            onGifSelected(bytes)
                                        } catch (e: Exception) {
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
