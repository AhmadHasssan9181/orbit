package util

import java.io.PrintStream

/**
 * Installs a filtering System.out/System.err that routes the codebase's legacy
 * `println("LEVEL [Subsystem] ...")` output through OrbitLog's level + subsystem
 * gates. Lines without a recognized prefix pass through untouched. Call once at
 * startup, before any subsystem logging.
 *
 * ## Why this does NOT go through [OrbitLog.sink]
 *
 * The ~300 call sites that still say `println("WARN [X] ...")` reach the console
 * through the stream this wraps, not through [OrbitLog.emit]. Routing them into
 * the sink from here would write every line to the file TWICE: once by this
 * filter and once by the sink. So this asks [OrbitLog.shouldPrintLine] — the gate
 * with no side effect — and does its own delivery to the real stdout. Only
 * [OrbitLog.emit] reaches the sink.
 *
 * The consequence worth knowing: a raw `println` appears in the file only if
 * something re-emits it. Use [OrbitLog.d]/[OrbitLog.w]/[OrbitLog.e] for anything
 * you need to read back from a user's machine.
 */
fun installOrbitLogConsoleFilter() {
    val originalOut = System.out
    System.setOut(FilteringPrintStream(originalOut))
}

private class FilteringPrintStream(delegate: PrintStream) : PrintStream(delegate, true) {
    override fun println(x: String?) {
        // A null line is `println()` with no argument: nothing to gate, and
        // nothing worth putting in a log file either.
        if (x != null && !OrbitLog.shouldPrintLine(x)) return
        super.println(x)
    }
}
