package util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrbitLogTest {
    private companion object {
        const val SUB = "CallManager"
    }

    @Test
    fun debugSuppressedByDefault() {
        OrbitLog.debugEnabled = false
        OrbitLog.mutedSubsystems = emptySet()
        assertFalse(OrbitLog.shouldEmit(OrbitLog.Level.DEBUG, SUB))
    }

    @Test
    fun debugPassesWhenEnabled() {
        OrbitLog.debugEnabled = true
        OrbitLog.mutedSubsystems = emptySet()
        assertTrue(OrbitLog.shouldEmit(OrbitLog.Level.DEBUG, SUB))
    }

    @Test
    fun warnAndErrorAlwaysPass() {
        OrbitLog.debugEnabled = false
        assertTrue(OrbitLog.shouldEmit(OrbitLog.Level.WARN, SUB))
        assertTrue(OrbitLog.shouldEmit(OrbitLog.Level.ERROR, SUB))
    }

    @Test
    fun mutedSubsystemSilencesDebugOnlyCaseInsensitively() {
        OrbitLog.debugEnabled = true
        OrbitLog.mutedSubsystems = setOf("matrixrtcvisibilityaudit")
        assertFalse(OrbitLog.shouldEmit(OrbitLog.Level.DEBUG, "MatrixRTCVisibilityAudit"))
        assertTrue(OrbitLog.shouldEmit(OrbitLog.Level.ERROR, "MatrixRTCVisibilityAudit"))
        assertTrue(OrbitLog.shouldEmit(OrbitLog.Level.DEBUG, SUB))
    }

    @Test
    fun parsesLevelAndSubsystemConvention() {
        assertEquals(
            OrbitLog.Level.DEBUG to "WebRTC",
            OrbitLog.parse("DEBUG: [WebRTC] Matrix ICE discovery returned 2 urls")
        )
        assertEquals(
            OrbitLog.Level.ERROR to "CallManager",
            OrbitLog.parse("ERROR [CallManager] join failed")
        )
        assertEquals(
            OrbitLog.Level.WARN to "MatrixRTCVisibilityAudit",
            OrbitLog.parse("WARN [MatrixRTCVisibilityAudit] stale membership cleanup failed=x")
        )
    }

    @Test
    fun linesWithoutKnownPrefixPassThrough() {
        assertTrue(OrbitLog.shouldPrintLine("Failed to restore Trixnity client from store: x"))
        assertTrue(OrbitLog.shouldPrintLine("INFO [Something] hello"))
    }

    @Test
    fun filterHonorsGatesForPrefixedLines() {
        OrbitLog.debugEnabled = false
        OrbitLog.mutedSubsystems = emptySet()
        assertFalse(OrbitLog.shouldPrintLine("DEBUG [LiveKitTokenProvider] Token refresh started"))
        assertTrue(OrbitLog.shouldPrintLine("ERROR [LiveKitTokenProvider] /get_token failed"))
    }

    /**
     * The sink is the whole reason the gate was split out of delivery: before
     * this, `d`/`w`/`e` called `println` and the packaged app had no destination
     * at all. These pin the contract the JVM file sink depends on -- [emit]
     * decides, and a suppressed DEBUG line is never handed to the sink.
     */
    @Test
    fun emitDeliversToTheSinkOnlyWhenTheGatePasses() {
        val seen = mutableListOf<String>()
        val restore = OrbitLog.sink
        try {
            OrbitLog.sink = OrbitLog.Sink { seen += it }
            OrbitLog.debugEnabled = false
            OrbitLog.mutedSubsystems = emptySet()

            OrbitLog.d(SUB, "suppressed")
            assertTrue(seen.isEmpty(), "a gated DEBUG line must not reach the sink: $seen")

            OrbitLog.w(SUB, "kept")
            assertEquals(listOf("WARN [$SUB] kept"), seen)
        } finally {
            OrbitLog.sink = restore
        }
    }

    /** The gate must stay side-effect free, or the console filter double-writes. */
    @Test
    fun shouldPrintLineNeverTouchesTheSink() {
        val seen = mutableListOf<String>()
        val restore = OrbitLog.sink
        try {
            OrbitLog.sink = OrbitLog.Sink { seen += it }
            OrbitLog.debugEnabled = true
            OrbitLog.mutedSubsystems = emptySet()

            assertTrue(OrbitLog.shouldPrintLine("DEBUG [$SUB] hello"))
            assertTrue(seen.isEmpty(), "the gate must only decide, never deliver: $seen")
        } finally {
            OrbitLog.sink = restore
        }
    }
}
