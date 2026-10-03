package util

import java.io.BufferedWriter
import java.io.File
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Rolling file sink, installed at startup by [installOrbitLogFile].
 *
 * ## Why this exists
 *
 * Every diagnostic in this app is a `println`. In a dev run that lands in the
 * Gradle console and everyone is fine. In the packaged MSI it lands nowhere:
 * jpackage builds `Orbit.exe` as a GUI-subsystem binary (`console = true` is not
 * set in the Compose `windows` block), so it has no console and its
 * stdout/stderr are discarded by the OS. Confirmed by reading the PE subsystem
 * field of the built launcher -- 2, WINDOWS_GUI.
 *
 * The consequence was not "logs are hard to find". It was that a user reporting
 * a real bug had nothing to send: the roster that fed a duplicate LazyColumn key
 * every three seconds logged nothing, so the cause was invisible from the
 * outside. A logger that only works on the machine where the bug reproduces is
 * not a logger.
 *
 * ## Why not just `console = true`
 *
 * That would attach a console window to a messenger app users keep open all day.
 * It also only helps on the machine in front of you. Redirecting the launcher by
 * hand (`start /wait "" Orbit.exe > out.txt`) works and stays the escape hatch,
 * but it cannot be asked of a user who does not know they should.
 *
 * ## What lands here
 *
 * Only lines routed through [OrbitLog.emit] -- `OrbitLog.d/w/e` and
 * [OrbitNotices.error]. Legacy raw `println` sites reach the console filter
 * instead and are deliberately not duplicated; see [installOrbitLogConsoleFilter].
 */
private object OrbitLogFile {

    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val MAX_FILES = 3
    private const val FILE_NAME = "orbit.log"

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** Serializes writes and owns rollover, so rollover cannot race a write. */
    private val lock = Any()

    /** Null before the first write, after a failed open, or during rollover. */
    private var writer: PrintWriter? = null
    private var current: File? = null

    /**
     * `%LOCALAPPDATA%\Orbit\logs`, or `$ORBIT_LOG_DIR` verbatim.
     *
     * `Orbit` is where a per-user jpackage MSI installs (`perUserInstall = true`),
     * so this is writable without elevation and is already a folder the user has
     * seen. The `logs` child is separate so that a support instruction can say
     * "send me the logs folder" without also implying the whole install.
     *
     * @throws java.io.IOException if the directory does not exist and cannot be
     *   created, which the caller turns into "keep the console sink".
     */
    fun logDirectory(): File {
        val override = System.getenv("ORBIT_LOG_DIR")?.trim()
        val base = if (!override.isNullOrBlank()) File(override) else File(installRoot(), "logs")
        // `mkdirs` returns false when the directory already exists, so the
        // result is checked rather than trusted.
        if (!base.isDirectory && !base.mkdirs()) {
            throw java.io.IOException("cannot create log directory $base")
        }
        return base
    }

    /**
     * The per-user install root, `%LOCALAPPDATA%\Orbit`.
     *
     * Read from the environment rather than from `user.home`: on Windows those
     * are different folders, and a per-user MSI install is keyed off
     * %LOCALAPPDATA%. Only used to locate our own logs, so if it is wrong the
     * cost is a log in an unexpected place -- not a wrong answer about anything.
     */
    private fun installRoot(): File {
        val local = System.getenv("LOCALAPPDATA")
            ?: System.getProperty("user.home")
            ?: "."
        return File(local, "Orbit")
    }

    /** Open if needed, append, flush. Never throws. */
    fun write(line: String) {
        try {
            synchronized(lock) {
                val file = open() ?: return
                val w = writer ?: return
                w.println("${LocalDateTime.now().format(stamp)} $line")
                // DEBUG is on for a whole call at ~30 lines/s. Buffering would
                // lose the tail exactly when a crash is what you are debugging.
                w.flush()
                if (file.length() > MAX_BYTES) rotate()
            }
        } catch (_: Throwable) {
            // A logger must never take the app down, and must not retry-storm
            // either: drop the handle so the next line re-attempts the open once.
            synchronized(lock) {
                runCatching { writer?.close() }
                writer = null
                current = null
            }
        }
    }

    /** Returns the file being appended to, or null when it could not be opened. */
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
        // A header, so a log that opens with an hour of silence is
        // self-explanatory: it says which build produced it and that it started.
        if (fresh) {
            w.println("${now()} INFO [OrbitLog] ---- Orbit log opened ----")
            w.println(
                "${now()} INFO [OrbitLog] java=${System.getProperty("java.version")}" +
                    " os=${System.getProperty("os.name")}" +
                    " arch=${System.getProperty("os.arch")}"
            )
            // Required, not tidiness. `PrintWriter.println` does not flush, and
            // this block is the only thing written when a session logs no
            // WARN/ERROR at all -- so without it the eagerly-created file stayed
            // zero bytes and the run looked like the sink had failed. Caught by
            // running it with debug off, which is the default.
            w.flush()
        }
        return file
    }

    /**
     * Open the file and write the header NOW, rather than on the first log line.
     *
     * Lazy opening was tried and it is wrong here. DEBUG is off by default, so a
     * session that starts cleanly and logs no WARN/ERROR would never call
     * [write] at all -- and would therefore leave NO log file. That is precisely
     * the session worth a log: "it just closed", "it never came up", "nothing
     * happens when I press Join". A log that only exists once something has gone
     * wrong is not evidence for the case where nothing was.
     *
     * Verified by running it: with debug off and no errors, the lazy version
     * created the directory and no file.
     */
    fun openNow() {
        try {
            synchronized(lock) { open() }
        } catch (_: Throwable) {
            // The caller already handles an unavailable directory by keeping the
            // console sink; this is the second chance, for a file that exists but
            // cannot be written to.
            synchronized(lock) {
                runCatching { writer?.close() }
                writer = null
                current = null
            }
        }
    }

    /**
     * `orbit.log` -> `orbit.1.log` -> `orbit.2.log`, dropping the oldest.
     *
     * In this order rather than the reverse, so no name is ever briefly absent
     * and `orbit.log` is always the newest. Renaming down from the top means each
     * step overwrites the file it replaces, which is the point.
     */
    private fun rotate() {
        val file = current ?: return
        runCatching { writer?.close() }
        writer = null
        current = null

        val dir = file.parentFile ?: return
        File(dir, "orbit.$MAX_FILES.log").delete()
        for (i in MAX_FILES - 1 downTo 1) {
            val from = File(dir, "orbit.$i.log")
            if (from.exists()) from.renameTo(File(dir, "orbit.${i + 1}.log"))
        }
        file.renameTo(File(dir, "orbit.1.log"))
    }

    private fun now(): String = LocalDateTime.now().format(stamp)

    /**
     * The log directory as a user would type it, or null when there is not one.
     * Reported at startup so a bug report identifies its own source.
     */
    fun describeLocation(): String? = runCatching { logDirectory().absolutePath }.getOrNull()
}

/**
 * Points [OrbitLog.sink] at a rolling file under [OrbitLogFile]'s directory.
 *
 * Safe to call more than once. If the directory cannot be created -- a
 * locked-down profile, a redirected %LOCALAPPDATA%, a full disk -- the previous
 * sink is kept and the reason goes to the console, because a logger that fails
 * silently is the exact problem this file is here to solve.
 *
 * Called before the Compose window exists, so the earliest subsystem lines are
 * already on disk by the time anything can go wrong.
 */
fun installOrbitLogFile() {
    val location = OrbitLogFile.describeLocation()
    if (location == null) {
        // `err`, not `out`: this line is about the logger, and the console filter
        // that would gate `out` is installed separately.
        System.err.println("WARN [OrbitLog] log directory unavailable; staying on the console sink")
        return
    }
    OrbitLog.sink = OrbitLog.Sink { line -> OrbitLogFile.write(line) }
    // Before announcing the location, so the file is guaranteed to exist by the
    // time anyone is told where to find it.
    OrbitLogFile.openNow()
    System.err.println("INFO [OrbitLog] file logging to $location${File.separator}orbit.log")
}
