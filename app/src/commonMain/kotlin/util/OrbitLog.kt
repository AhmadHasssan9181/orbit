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
        Level.DEBUG -> debugEnabled && subsystem.lowercase() !in mutedSubsystems
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
