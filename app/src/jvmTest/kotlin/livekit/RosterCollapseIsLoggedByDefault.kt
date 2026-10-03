package livekit

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A bug report arrived with an essentially empty log.
 *
 * A user's group call lost every video tile while audio stayed perfect, and the
 * `orbit.log` they sent contained a startup banner and two unrelated errors from
 * a different room. Nothing about the call was in it. The cause was a
 * token-refresh reconnect that wiped the roster, and every line describing that
 * reconnect was classified DEBUG -- which is OFF by default.
 *
 * So the file sink was not broken. It was faithfully recording a log level that
 * excluded the entire diagnosis. That is a worse failure than no logging at all,
 * because it looks like evidence.
 *
 * These tests pin the rule that came out of it: **anything that explains a
 * user-visible state change must be visible at default settings.** Concretely,
 * `clear()` dropping a populated roster has to produce output when debug logging
 * is off, because that is the moment the screen goes blank.
 *
 * This lives in `jvmTest` rather than `commonTest` because it asserts on
 * `System.out`, which is JVM-only.
 */
class RosterCollapseIsLoggedByDefault {

    private val captured = ByteArrayOutputStream()
    private var originalOut: PrintStream? = null

    /** Runs [body] with stdout redirected, and returns everything written to it. */
    private fun capturing(body: () -> Unit): String {
        originalOut = System.out
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        try {
            body()
            System.out.flush()
        } finally {
            originalOut?.let { System.setOut(it) }
            originalOut = null
        }
        return captured.toString(Charsets.UTF_8)
    }

    @AfterTest
    fun restore() {
        originalOut?.let { System.setOut(it) }
        originalOut = null
    }

    private fun armWithRoster() {
        LiveKitRemoteVideoFrameStore.clear("test-setup")
        LiveKitRemoteVideoFrameStore.beginSession(31L, 9L)
        LiveKitRemoteVideoFrameStore.participantConnected("@peer:example.org:DEV")
        LiveKitRemoteVideoFrameStore.participantConnected("@other:example.org:DEV")
        LiveKitRemoteVideoFrameStore.trackAnnounced("@peer:example.org:DEV", "TR_ALPHA", "camera")
        captured.reset()
    }

    @Test
    fun clearingAPopulatedRosterIsVisibleWithDebugLoggingOff() {
        val out = capturing {
            armWithRoster()
            // The exact call the reconnect makes, and the exact moment the user's
            // screen drops to a single tile.
            LiveKitRemoteVideoFrameStore.clear("reconnect-start")
        }

        assertTrue(
            out.contains("roster cleared while participants were present"),
            "the roster wipe must be logged at DEFAULT level; got:\n$out"
        )
        assertTrue(out.contains("reconnect-start"), "the reason must be in the line: got:\n$out")
        // The counts are what make the line useful rather than merely present.
        assertTrue(out.contains("participants=2"), "expected the participant count: got:\n$out")
        assertTrue(out.contains("tracks=1"), "expected the track count: got:\n$out")
    }

    /**
     * The level itself, asserted rather than assumed. If someone tidies this back
     * to DEBUG the test fails, which is the entire point: the regression WAS a
     * mislabelled line, so the label is what needs pinning.
     */
    @Test
    fun theRosterWipeLineIsWarnNotDebug() {
        val out = capturing {
            armWithRoster()
            LiveKitRemoteVideoFrameStore.clear("reconnect-start")
        }

        val line = out.lineSequence().firstOrNull { it.contains("roster cleared") }
        assertTrue(line != null, "expected the roster line, got:\n$out")
        assertTrue(
            line!!.startsWith("WARN "),
            "must be WARN so it survives with debug logging OFF; was: $line"
        )
    }

    /**
     * The opposite direction, so the fix cannot degenerate into "log everything at
     * WARN". A disconnect with nothing in the store ends every call and must stay
     * quiet, or the default log becomes noise nobody reads.
     */
    @Test
    fun clearingAnEmptyRosterStaysQuiet() {
        val out = capturing {
            LiveKitRemoteVideoFrameStore.clear("test-setup")
            LiveKitRemoteVideoFrameStore.beginSession(31L, 9L)
            captured.reset()
            LiveKitRemoteVideoFrameStore.clear("disconnect")
        }

        assertTrue(!out.contains("roster cleared"), "an empty roster is not an event; got:\n$out")
    }

    /**
     * The other half of the same rule, on the Kotlin side of the reconnect: the
     * lines that bracket it have to be visible without debug logging, because
     * "was this a reconnect, and did the roster come back" is the only question a
     * support log needs to answer.
     *
     * Scans the source rather than the constant pool on purpose. The regression
     * here was a mislabelled `println` prefix, and the prefix lives in the source
     * text -- reading the class file would be asserting on JVM string-constant
     * ordering, which is not a contract and fails for the wrong reasons.
     */
    @Test
    fun everyReconnectLifecycleLineIsAtLeastWarn() {
        val source = locateSource(
            "src/jvmMain/kotlin/livekit/LiveKitNativeMediaSession.kt"
        )

        val lifecycle = listOf(
            "token refresh scheduled in",
            "token refresh RECONNECT started",
            "native reconnect dispatching to SFU",
            "native reconnect CONNECTED",
            "remote video render sink (re)enabled"
        )
        for (marker in lifecycle) {
            val lines = source.lines()
            val index = lines.indexOfFirst { it.contains(marker) }
            assertTrue(index >= 0, "lifecycle line missing from source: $marker")
            // The message may be a continuation of a `println(` that opened on an
            // earlier line, so look at the statement head as well as this line.
            val window = lines
                .drop(index - 3)
                .take(4)
                .joinToString("\n")
            assertTrue(
                window.contains("WARN"),
                "must be WARN to survive default logging (debug is OFF by default): $marker\n--- window ---\n$window"
            )
            assertTrue(
                !window.contains("DEBUG [LiveKitNativeMediaSession]"),
                "must not be DEBUG for this line: $marker\n--- window ---\n$window"
            )
        }
    }

    /** Walks up from the working directory to the module root holding [relative]. */
    private fun locateSource(relative: String): String {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, "app/$relative")
            if (candidate.isFile) return candidate.readText()
            val direct = java.io.File(dir, relative)
            if (direct.isFile) return direct.readText()
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative from ${java.io.File("").absolutePath}")
    }
}
