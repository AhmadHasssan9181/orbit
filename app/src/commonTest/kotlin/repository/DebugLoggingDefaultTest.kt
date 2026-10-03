package repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Debug logging is on by default, and the two readers must agree about that.
 *
 * Two releases shipped with DEBUG off by default and it cost a real bug report:
 * a user lost every video tile in a group call, sent the log, and it held nothing
 * about the call because the entire reconnect lifecycle was DEBUG. The file sink
 * was working perfectly; the level was excluding the diagnosis.
 *
 * The subtle part is not the default itself, it is that TWO defaults exist and
 * they are far apart in the codebase:
 *
 *  - `debugLoggingSync()` -- read once at startup by `Main.kt`;
 *  - `debugLogging` flow -- read by the Diagnostics switch.
 *
 * When they disagree, the switch renders "off" on a fresh install while DEBUG
 * lines are genuinely being written to disk. That is a control that misreports
 * the only state it exists to report, and this codebase has a whole audit about
 * settings that lie. These tests pin the pair so the next person to change one
 * default has to change both.
 *
 * No Android/JVM store is constructed here: `multiplatform-settings-no-arg` needs
 * a platform, so these assert the constants the two readers actually pass to the
 * store, by reading the source. Reading source is the point -- the bug being
 * guarded is a divergence between two literals in two files, and a runtime test
 * would only ever see one of them at a time.
 */
class DebugLoggingDefaultTest {

    private fun locateSource(relative: String): String {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            java.io.File(dir, "app/$relative").takeIf { it.isFile }?.let { return it.readText() }
            java.io.File(dir, relative).takeIf { it.isFile }?.let { return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative from ${java.io.File("").absolutePath}")
    }

    private val settingsManager = locateSource("src/commonMain/kotlin/repository/SettingsManager.kt")

    @Test
    fun theStartupReaderDefaultsToOn() {
        assertTrue(
            settingsManager.contains("""settings.getBoolean("debugLogging", true)"""),
            "debugLoggingSync must default to true -- a bug report has to be " +
                "diagnosable without the user finding a settings pane first"
        )
    }

    /**
     * The one that actually bites: if the flow still defaults to false, the
     * Diagnostics switch shows "off" while DEBUG is on. Nobody would notice,
     * because both halves "work".
     */
    @Test
    fun theSwitchReaderDefaultsToOnToo() {
        assertTrue(
            settingsManager.contains("""flowSettings.getBooleanFlow("debugLogging", true)"""),
            "the debugLogging flow must default to the SAME value as " +
                "debugLoggingSync, or the switch misreports the real state"
        )
    }

    @Test
    fun bothDefaultsAreTrueAndThereIsNoStrayFalse() {
        val defaults = Regex("""(getBoolean|getBooleanFlow)\("debugLogging",\s*(true|false)\)""")
            .findAll(settingsManager)
            .map { it.groupValues[2] }
            .toList()

        assertEquals(2, defaults.size, "expected exactly two readers, found: $defaults")
        assertTrue(
            defaults.all { it == "true" },
            "both defaults must be true; found: $defaults"
        )
    }

    /** The ORBIT_DEBUG override is an escape hatch and must survive the flip. */
    @Test
    fun theEnvironmentOverrideStillForcesDebugOn() {
        assertTrue(
            settingsManager.contains("ORBIT_DEBUG"),
            "ORBIT_DEBUG=1 must keep working as a one-run override"
        )
    }

    /**
     * The audit dump stays behind its own flag. Turning DEBUG on by default must
     * NOT drag in `MatrixRTCVisibilityAudit`, which is a third of a whole call's
     * output and prints every MatrixRTC state event in the room on every publish.
     * `OrbitLog.auditEnabled` reads ORBIT_AUDIT and defaults false.
     */
    @Test
    fun theProtocolAuditStaysBehindItsOwnFlag() {
        val orbitLog = locateSource("src/commonMain/kotlin/util/OrbitLog.kt")
        assertTrue(
            orbitLog.contains("ORBIT_AUDIT") && orbitLog.contains("auditEnabled"),
            "the audit dump must remain independently gated"
        )
    }
}
