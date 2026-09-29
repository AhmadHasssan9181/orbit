package util

/**
 * Central logging facade. Levels and subsystems are enforced by
 * [OrbitLog.shouldEmit]; on the JVM the console sink additionally filters the
 * legacy `println("LEVEL [Subsystem] ...")` call sites through the same rule
 * (see OrbitLogConsole), so the whole codebase honors one configuration.
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

    fun d(subsystem: String, message: String) {
        if (shouldEmit(Level.DEBUG, subsystem)) println("DEBUG [$subsystem] $message")
    }

    fun w(subsystem: String, message: String) {
        if (shouldEmit(Level.WARN, subsystem)) println("WARN [$subsystem] $message")
    }

    fun e(subsystem: String, message: String) {
        if (shouldEmit(Level.ERROR, subsystem)) println("ERROR [$subsystem] $message")
    }

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

    /** Single entry point used by the JVM console filter. */
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
