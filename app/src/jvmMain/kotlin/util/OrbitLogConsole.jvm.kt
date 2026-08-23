package util

import java.io.PrintStream

/**
 * Installs a filtering System.out/System.err that routes the codebase's legacy
 * `println("LEVEL [Subsystem] ...")` output through OrbitLog's level + subsystem
 * gates. Lines without a recognized prefix pass through untouched. Call once at
 * startup, before any subsystem logging.
 */
fun installOrbitLogConsoleFilter() {
    val originalOut = System.out
    System.setOut(FilteringPrintStream(originalOut))
}

private class FilteringPrintStream(delegate: PrintStream) : PrintStream(delegate, true) {
    override fun println(x: String?) {
        if (x != null && !OrbitLog.shouldPrintLine(x)) return
        super.println(x)
    }
}
