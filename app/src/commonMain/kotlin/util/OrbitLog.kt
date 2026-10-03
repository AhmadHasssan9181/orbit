package util

/**
 * Central logging facade. Levels and subsystems are enforced by
 * [OrbitLog.shouldPrint]; the JVM installs a real file sink (see
 * OrbitLogFile) *and* a filtering System.out (see OrbitLogConsole), so the
 * whole codebase honors one configuration and the same lines land in both a
 * terminal and a file a user can send you.
 */
object OrbitLog {
    enum class Level(val label: String) {
        DEBUG("DEBUG"), INFO("INFO"), WARN("WARN"), ERROR("ERROR");

        companion object {
            fun fromLabel(label: String): Level? = entries.firstOrNull { it.label == label }
        }
    }

    /** Minimum level emitted. WARN/ERROR always pass; DEBUG requires debugEnabled. */
    @Volatile
    var debugEnabled: Boolean = false

    /**
     * Subsystems whose DEBUG lines are suppressed even when [debugEnabled] is on
     * (empty = none). Subsystem names are matched case-insensitively.
     */
    @Volatile
    var mutedSubsystems: Set<String> = emptySet()

    /**
     * Whether the deep protocol-shape dumps run. **Off unless `ORBIT_AUDIT=1`.**
     *
     * A level above DEBUG, because DEBUG is not coarse enough. Turning debug on
     * produced 2,080 lines in a four-minute call, and 679 of them — a third of
     * the whole log — came from `MatrixRTCVisibilityAudit`, which prints every
     * MatrixRTC state event in the room on every publish. That room had 89 of
     * them. The audit exists to answer "why is Element not seeing my membership",
     * which is a question you ask once, deliberately, with the room to hand — not
     * a thing to leave running through every call.
     *
     * WARN/ERROR from those subsystems are unaffected: a failed state fetch is
     * still reported, because that is a real problem rather than a shape dump.
     */
    @Volatile
    var auditEnabled: Boolean =
        System.getenv("ORBIT_AUDIT")?.trim()?.lowercase() in setOf("1", "true", "on", "yes")

    /** True when [subsystem] is one of the protocol-audit dumps. */
    fun isAuditSubsystem(subsystem: String): Boolean =
        subsystem == "MatrixRTCVisibilityAudit" || subsystem == "ElementReferenceDump"

    /**
     * Where a gated line goes.
     *
     * Split out from the level/subsystem gate so that gating is decided in one
     * place ([shouldPrint]) and delivery is a separate concern. That split is the
     * whole reason a file sink could be added without touching the gate: before
     * it, `d`/`w`/`e` called `println` directly, so the only destination a
     * packaged app had was stdout — and a jpackage GUI-subsystem launcher has no
     * console, so every line was discarded.
     *
     * Implementations must not throw: a logger that takes the app down is worse
     * than a logger that loses a line.
     */
    fun interface Sink {
        fun write(line: String)
    }

    /** The pre-file-sink behaviour, and still the fallback if file install fails. */
    private object ConsoleSink : Sink {
        override fun write(line: String) {
            println(line)
        }
    }

    /**
     * Process-wide destination. Install once at startup, before any subsystem
     * logs. Never null; defaults to the console.
     */
    @Volatile
    var sink: Sink = ConsoleSink

    /**
     * Write [line] if it passes the gates, then return whether it was written.
     *
     * The return value exists for the console filter, which is a
     * [java.io.PrintStream] wrapper and must decide for itself whether to let a
     * line reach the real stdout. The file sink does not consult it: it receives
     * only lines that already passed, via [emit].
     */
    fun shouldPrint(line: String): Boolean {
        if (!shouldPrintLine(line)) return false
        sink.write(line)
        return true
    }

    /** Single entry point for everything that logs, including the console filter. */
    fun emit(line: String) {
        if (!shouldPrintLine(line)) return
        sink.write(line)
    }

    fun d(subsystem: String, message: String) = emit("DEBUG [$subsystem] $message")

    fun w(subsystem: String, message: String) = emit("WARN [$subsystem] $message")

    fun e(subsystem: String, message: String) = emit("ERROR [$subsystem] $message")

    fun shouldEmit(level: Level, subsystem: String): Boolean = when (level) {
        Level.DEBUG ->
            debugEnabled &&
                subsystem.lowercase() !in mutedSubsystems &&
                // The protocol-shape dumps sit one step above debug. See
                // auditEnabled for why, and ORBIT_AUDIT=1 to turn them back on.
                (!isAuditSubsystem(subsystem) || auditEnabled)
        else -> true
    }

    private data class ParsedLine(val level: Level?, val subsystem: String?)

    /**
     * Parses the codebase's `LEVEL [Subsystem] message` / `LEVEL: message`
     * println convention. Returns null for lines without a known prefix.
     */
    fun parse(line: String): Pair<Level?, String?> {
        val parsed = parseLine(line)
        return parsed.level to parsed.subsystem
    }

    /**
     * The level/subsystem gate alone, with no side effect. True for lines
     * without a recognized prefix: a line OrbitLog did not produce is somebody
     * else's, and swallowing it would hide a stack trace.
     */
    fun shouldPrintLine(line: String): Boolean {
        val (level, subsystem) = parseLine(line)
        return if (level == null) true else shouldEmit(level, subsystem ?: "")
    }

    private fun parseLine(line: String): ParsedLine {
        for (level in listOf(Level.DEBUG, Level.WARN, Level.ERROR, Level.INFO)) {
            if (!line.startsWith(level.label)) continue
            var rest = line.removePrefix(level.label)
            if (rest.startsWith(":")) rest = rest.removePrefix(":")
            rest = rest.trimStart()
            val subsystem = if (rest.startsWith("[") && !rest.startsWith("[[")) {
                rest.substringAfter('[').substringBefore(']').takeIf { it.isNotBlank() }
            } else null
            return ParsedLine(level, subsystem)
        }
        return ParsedLine(null, null)
    }
}
