package util

import java.io.BufferedWriter
import java.io.File
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object StreamLogFile {

    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val MAX_FILES = 2
    private const val FILE_NAME = "orbit-stream.log"

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val lock = Any()

    private var writer: PrintWriter? = null
    private var current: File? = null

    fun logDirectory(): File {
        val override = System.getenv("ORBIT_LOG_DIR")?.trim()
        val base = if (!override.isNullOrBlank()) File(override) else File(installRoot(), "logs")
        if (!base.isDirectory && !base.mkdirs()) {
            throw java.io.IOException("cannot create stream log directory $base")
        }
        return base
    }

    fun write(line: String) {
        try {
            synchronized(lock) {
                val file = open() ?: return
                val w = writer ?: return
                w.println("${LocalDateTime.now().format(stamp)} $line")
                w.flush()
                if (file.length() > MAX_BYTES) rotate()
            }
        } catch (_: Throwable) {
            synchronized(lock) {
                runCatching { writer?.close() }
                writer = null
                current = null
            }
        }
    }

    private fun open(): File? {
        writer?.let { return current }

        val file = File(logDirectory(), FILE_NAME)
        val fresh = !file.exists() || file.length() == 0L
        val opened = Files.newBufferedWriter(
            file.toPath(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND
        )
        val w = PrintWriter(BufferedWriter(opened))
        writer = w
        current = file
        if (fresh) {
            w.println("${now()} ---- Orbit stream log opened ----")
            w.println(
                "${now()} java=${System.getProperty("java.version")}" +
                    " os=${System.getProperty("os.name")}" +
                    " arch=${System.getProperty("os.arch")}"
            )
            w.flush()
        }
        return file
    }

    private fun rotate() {
        val file = current ?: return
        runCatching { writer?.close() }
        writer = null
        current = null

        val dir = file.parentFile ?: return
        File(dir, "orbit-stream.$MAX_FILES.log").delete()
        for (i in MAX_FILES - 1 downTo 1) {
            val from = File(dir, "orbit-stream.$i.log")
            if (from.exists()) from.renameTo(File(dir, "orbit-stream.${i + 1}.log"))
        }
        file.renameTo(File(dir, "orbit-stream.1.log"))
    }

    private fun installRoot(): File {
        val local = System.getenv("LOCALAPPDATA")
            ?: System.getProperty("user.home")
            ?: "."
        val profile = OrbitPaths.profile
        val folder = if (profile.isNotEmpty()) "Orbit-$profile" else "Orbit"
        return File(local, folder)
    }

    private fun now(): String = LocalDateTime.now().format(stamp)

    fun describeLocation(): String? = runCatching {
        File(logDirectory(), FILE_NAME).absolutePath
    }.getOrNull()
}
