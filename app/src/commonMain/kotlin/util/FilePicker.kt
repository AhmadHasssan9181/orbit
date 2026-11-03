package util

expect suspend fun pickImageFile(): ByteArray?

/**
 * Optimizes an image (downscales and compresses) for lightweight uploads to Matrix.
 * Prevents 429 Too Many Requests and reduces payload sizes by up to 99%.
 */
expect fun compressImage(imageBytes: ByteArray, maxDimension: Int = 1024, quality: Float = 0.85f): ByteArray

