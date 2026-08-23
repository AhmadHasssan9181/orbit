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
}
