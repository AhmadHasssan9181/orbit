package ui.components

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size

/**
 * High-fidelity avatar image loader.
 *
 * Preserves the user's original image sharpness by decoding at full resolution
 * ([Size.ORIGINAL]) and applying [FilterQuality.High] (bicubic filtering with
 * mipmaps) to prevent downsampling aliasing, moiré patterns, or blurry/crumpled artifacts.
 */
@Composable
fun OrbitAvatarImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalPlatformContext.current
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(Size.ORIGINAL)
        .build()

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = contentScale,
        filterQuality = FilterQuality.High,
        modifier = modifier
    )
}
