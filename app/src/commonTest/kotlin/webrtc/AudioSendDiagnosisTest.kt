package webrtc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "The Element user can't hear me", with Orbit showing a live unmuted microphone.
 *
 * The log said `enabled=true id=audioTrack`, which proves the track exists and is not
 * muted -- and nothing else. Mic capturing silence, audio never negotiated, and audio
 * sent but undecodable all produce that identical line.
 *
 * These pin the reading of the replacement diagnostic, and most of them are about not
 * lying: a stats callback that throws is swallowed, so the diagnostic silently
 * vanishes and the next run is as blind as this one.
 */
class AudioSendDiagnosisTest {

    @Test
    fun aLiveSenderWithBytesIsSending() {
        // The only verdict that means "the peer can hear you".
        val sample = AudioSendDiagnosis.readOutbound(
            attributes = mapOf("kind" to "outbound-rtp", "bytesSent" to 24_640L, "packetsSent" to 160L),
            trackEnabled = true,
        )!!
        assertEquals("sending", sample.verdict)
        assertTrue(sample.isTransmitting)
    }

    @Test
    fun aMutedTrackIsReportedMuted() {
        // Track-level, and the one cause the old log line already covered.
        val sample = AudioSendDiagnosis.readOutbound(
            attributes = mapOf("kind" to "outbound-rtp", "bytesSent" to 99_000L),
            trackEnabled = false,
        )!!
        assertEquals("muted", sample.verdict)
        assertFalse(sample.isTransmitting)
    }

    @Test
    fun anOpenTrackWithZeroBytesIsNotSending() {
        // The case this whole diagnostic exists for: mic looks live, silence on the
        // wire. Previously indistinguishable from a working call.
        val sample = AudioSendDiagnosis.readOutbound(
            attributes = mapOf("kind" to "outbound-rtp", "bytesSent" to 0L, "packetsSent" to 0L),
            trackEnabled = true,
        )!!
        assertEquals("not-sending", sample.verdict)
    }

    @Test
    fun aSenderWithNoOutboundEntryIsNotReportedAsZeroBytes() {
        // The distinction that makes the diagnostic worth having. libwebrtc omits the
        // entry entirely for a sender that was never negotiated, and collapsing that
        // to bytesSent=0 would name a cause ("mic silent") that is not it.
        val sample = AudioSendDiagnosis.noOutboundEntry(trackEnabled = true)
        assertEquals("no-outbound-entry", sample.verdict)
        assertFalse(sample.isTransmitting)
        assertEquals(0L, sample.bytesSent)
    }

    @Test
    fun anInactiveSenderIsNamedAsSuch() {
        // Negotiated, present, but libwebrtc says it is not sending. Third distinct
        // cause, third distinct word.
        val sample = AudioSendDiagnosis.readOutbound(
            attributes = mapOf("kind" to "outbound-rtp", "bytesSent" to 0L, "active" to false),
            trackEnabled = true,
        )!!
        assertEquals("sender-inactive", sample.verdict)
    }

    @Test
    fun inboundStatsAreNeverMistakenForSending() {
        // An inbound-rtp entry carries bytesReceived. Reading it as send-side would
        // report the peer's audio as proof that ours is going out -- the diagnostic
        // confidently confirming the exact opposite of the truth.
        assertNull(
            AudioSendDiagnosis.readOutbound(
                attributes = mapOf("kind" to "inbound-rtp", "bytesReceived" to 500_000L),
                trackEnabled = true,
            )
        )
    }

    @Test
    fun anEmptyReportIsNoEntryRatherThanACrash() {
        // A stats callback that throws is swallowed by WebRTC and the diagnostic is
        // lost entirely, so every read here has to survive bad input.
        assertNull(AudioSendDiagnosis.readOutbound(emptyMap<String, Any>(), trackEnabled = true))
    }

    @Test
    fun missingOrOddlyTypedCountersDoNotThrow() {
        // The report is built by native code and its keys vary between WebRTC
        // versions. An absent key must read as zero, not blow up the callback.
        val noCounters = AudioSendDiagnosis.readOutbound(mapOf("kind" to "outbound-rtp"), true)!!
        assertEquals(0L, noCounters.bytesSent)
        assertEquals("not-sending", noCounters.verdict)

        // Numbers arriving as strings, which some report versions do.
        val strings = AudioSendDiagnosis.readOutbound(
            mapOf("kind" to "outbound-rtp", "bytesSent" to "1234"), true
        )!!
        assertEquals(0L, strings.bytesSent, "a String bytesSent should read as zero, not parse or throw")
    }

    @Test
    fun anAbsentActiveFlagMeansUnknownNotInactive() {
        // `active` is a later addition. Reading a missing key as false would report
        // "sender-inactive" for every sender on an older build, which is a confident
        // wrong answer rather than an honest unknown.
        val sample = AudioSendDiagnosis.readOutbound(
            attributes = mapOf("kind" to "outbound-rtp", "bytesSent" to 500L),
            trackEnabled = true,
        )!!
        assertNull(sample.active)
        assertEquals("sending", sample.verdict, "an unknown active flag must not block a sending verdict")
    }

    @Test
    fun everyVerdictIsDistinct() {
        // Four causes, four words. If two collapsed, the diagnostic would go back to
        // being unable to separate the cases it exists to separate.
        val verdicts = listOf(
            AudioSendDiagnosis.readOutbound(mapOf("kind" to "outbound-rtp", "bytesSent" to 1L), false)!!.verdict,
            AudioSendDiagnosis.noOutboundEntry(trackEnabled = true).verdict,
            AudioSendDiagnosis.readOutbound(mapOf("kind" to "outbound-rtp", "active" to false), true)!!.verdict,
            AudioSendDiagnosis.readOutbound(mapOf("kind" to "outbound-rtp", "bytesSent" to 0L), true)!!.verdict,
            AudioSendDiagnosis.readOutbound(mapOf("kind" to "outbound-rtp", "bytesSent" to 1L), true)!!.verdict,
        )
        assertEquals(5, verdicts.toSet().size, "verdicts collapsed: $verdicts")
    }
}
