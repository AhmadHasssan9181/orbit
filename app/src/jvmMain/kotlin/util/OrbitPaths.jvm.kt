package util

import java.io.File

/**
 * Centralized filesystem and profile path resolver for Orbit Desktop.
 *
 * Supports multi-instance/dual-account testing via:
 * - Environment variable: `ORBIT_PROFILE=test2` (or custom data directory: `ORBIT_DATA_DIR=...`)
 * - System property: `-Dorbit.profile=test2` (or `-Dorbit.dir=...`)
 *
 * When a profile is specified (e.g. "test2"), data is isolated to `~/.orbit-test2`
 * and credentials to the `com/noobdev/orbit/credentials/test2` registry node,
 * allowing multiple instances with different accounts to run concurrently.
 */
object OrbitPaths {
    val profile: String by lazy {
        (System.getProperty("orbit.profile") ?: System.getenv("ORBIT_PROFILE") ?: "").trim()
    }

    val root: File by lazy {
        val custom = System.getProperty("orbit.dir") ?: System.getenv("ORBIT_DATA_DIR")
        if (!custom.isNullOrBlank()) {
            File(custom).apply { mkdirs() }
        } else {
            val folderName = if (profile.isNotEmpty()) ".orbit-$profile" else ".orbit"
            File(System.getProperty("user.home"), folderName).apply { mkdirs() }
        }
    }

    val databaseFile: File get() = File(root, "trixnity.db")
    val credentialsKeyFile: File get() = File(root, ".credkey")
    val cacheDir: File get() = File(root, "cache/rooms").apply { mkdirs() }
    val mediaDir: File get() = File(root, "media").apply { mkdirs() }
    val soundsDir: File get() = File(root, "sounds").apply { mkdirs() }
}
