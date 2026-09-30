package repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Parsing `m.room.banner` out of a room's `m.state` response.
 *
 * `OrbitServer.bannerUrl` was never assigned anywhere, which is why ServerHeader's
 * banner layout -- `hasBanner`, the image, the gradient, the "title always wins over
 * the picture" rule -- had never executed. Not once, in the life of the app. This
 * is what makes it reachable.
 *
 * Most of these cases are about not throwing. The `m.state` array is untrusted
 * input from a homeserver, and the same `jsonObject` assertion in this codebase
 * threw a real crash in `isVoiceRoom` that took the whole drawer down with an error
 * dialog. A banner is decoration; it must never cost anyone their window.
 */
class RoomBannerParseTest {

    private fun state(vararg events: String): Array<kotlinx.serialization.json.JsonElement> =
        Json.parseToJsonElement("[" + events.joinToString(",") + "]").let {
            (it as JsonArray).toTypedArray()
        }

    @Test
    fun aBannerIsFound() {
        val events = state(
            """{"type":"m.room.name","content":{"name":"Test Space"}}""",
            """{"type":"m.room.banner","content":{"url":"mxc://example.org/abc123"}}"""
        )
        assertEquals("mxc://example.org/abc123", roomBannerMxcFrom(events))
    }

    @Test
    fun aRoomWithNoBannerYieldsNull() {
        // The overwhelmingly common case, and it must be a null rather than a throw.
        val events = state(
            """{"type":"m.room.name","content":{"name":"Test Space"}}""",
            """{"type":"m.room.topic","content":{"topic":"hello"}}"""
        )
        assertNull(roomBannerMxcFrom(events))
    }

    @Test
    fun anEmptyStateArrayYieldsNull() {
        assertNull(roomBannerMxcFrom(emptyArray()))
    }

    @Test
    fun aNonObjectElementIsSkippedRatherThanThrown() {
        // jsonObject on a string or a number throws. That is the crash that already
        // happened once, in a different function, on a real server.
        val events = state(
            """"just a string"""",
            """42""",
            """{"type":"m.room.banner","content":{"url":"mxc://example.org/ok"}}"""
        )
        assertEquals("mxc://example.org/ok", roomBannerMxcFrom(events))
    }

    @Test
    fun aBannerWithNoUrlYieldsNull() {
        val events = state("""{"type":"m.room.banner","content":{}}""")
        assertNull(roomBannerMxcFrom(events))
    }

    @Test
    fun aBannerWithANonStringUrlYieldsNull() {
        val events = state("""{"type":"m.room.banner","content":{"url":{"nested":true}}}""")
        assertNull(roomBannerMxcFrom(events))
    }

    @Test
    fun aBannerWithNoContentYieldsNull() {
        val events = state("""{"type":"m.room.banner"}""")
        assertNull(roomBannerMxcFrom(events))
    }

    @Test
    fun anEmptyUrlIsTreatedAsNoBanner() {
        // Not null-vs-empty pedantry: an empty string would be handed to
        // convertMxcUrl, which produces a request for the homeserver root. Most
        // servers would 404; some would return HTML, and Coil would be asked to
        // decode it as an image.
        assertNull(roomBannerMxcFrom(state("""{"type":"m.room.banner","content":{"url":""}}""")))
        assertNull(roomBannerMxcFrom(state("""{"type":"m.room.banner","content":{"url":"   "}}""")))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals(
            "mxc://example.org/abc",
            roomBannerMxcFrom(state("""{"type":"m.room.banner","content":{"url":"  mxc://example.org/abc  "}}"""))
        )
    }

    @Test
    fun theFirstBannerWins() {
        // A room should only ever have one, but "first wins" is deterministic and
        // "last wins" would depend on the order a server happens to serialise state,
        // which is not something to depend on.
        val events = state(
            """{"type":"m.room.banner","content":{"url":"mxc://example.org/first"}}""",
            """{"type":"m.room.banner","content":{"url":"mxc://example.org/second"}}"""
        )
        assertEquals("mxc://example.org/first", roomBannerMxcFrom(events))
    }

    @Test
    fun aNonMxcUrlIsStillReturnedForTheConverterToReject() {
        // Deliberately not filtered here. A banner set to an https URL is legal on
        // some clients; convertMxcUrl passes it through, and the decision about what
        // to do with a non-mxc value belongs in one place, not split across two.
        assertEquals(
            "https://example.org/banner.png",
            roomBannerMxcFrom(state("""{"type":"m.room.banner","content":{"url":"https://example.org/banner.png"}}"""))
        )
    }

    @Test
    fun anEventWithNoTypeIsSkipped() {
        val events = state(
            """{"content":{"url":"mxc://example.org/no-type"}}""",
            """{"type":"m.room.banner","content":{"url":"mxc://example.org/right"}}"""
        )
        assertEquals("mxc://example.org/right", roomBannerMxcFrom(events))
    }

    @Test
    fun aNonStringTypeIsSkipped() {
        val events = state(
            """{"type":{"not":"a string"},"content":{"url":"mxc://example.org/wrong"}}""",
            """{"type":"m.room.banner","content":{"url":"mxc://example.org/right"}}"""
        )
        assertEquals("mxc://example.org/right", roomBannerMxcFrom(events))
    }

    @Test
    fun aJsonNullValueYieldsNull() {
        // JsonNull IS a JsonPrimitive, so a naive `as? JsonPrimitive` passes it
        // through and `contentOrNull` returns null -- which is correct, but only by
        // that second step. Pinned so a refactor that swaps in `.content` cannot
        // start handing back the string "null".
        val events = state("""{"type":"m.room.banner","content":{"url":null}}""")
        assertNull(roomBannerMxcFrom(events))
    }

    @Test
    fun aStringThatIsNotAUrlIsPassedThroughForTheConverterToReject() {
        // The parse does not judge URL shape -- that decision lives in one place, in
        // cachedRoomBanner, which requires the converted value to be http(s) before
        // returning it. This test documents the hand-off: "null" as a JSON null is
        // absent (contentOrNull), while "null" as a string is a string, and it is the
        // converter's job to refuse it rather than Coil's job to fail silently.
        assertEquals("null", roomBannerMxcFrom(state("""{"type":"m.room.banner","content":{"url":"null"}}""")))
    }

    @Test
    fun anMxcUrlWithNoMediaIdIsPassedThroughTheSameWay() {
        // "mxc://server" with no /id segment is malformed and convertMxcUrl returns
        // it unchanged. Same hand-off, same reasoning.
        assertEquals(
            "mxc://example.org",
            roomBannerMxcFrom(state("""{"type":"m.room.banner","content":{"url":"mxc://example.org"}}"""))
        )
    }

    @Test
    fun theTypeMatchIsExact() {
        // Guards against a loose `contains("banner")` matching some future event type
        // and putting the wrong picture behind the room title.
        val events = state("""{"type":"m.room.banner_sticker","content":{"url":"mxc://example.org/x"}}""")
        assertNull(roomBannerMxcFrom(events))
    }

    @Test
    fun aJsonNullElementInTheArrayIsSkipped() {
        val events = state(
            """null""",
            """{"type":"m.room.banner","content":{"url":"mxc://example.org/ok"}}"""
        )
        assertEquals("mxc://example.org/ok", roomBannerMxcFrom(events))
    }

    @Test
    fun aBannerContentThatIsNotAnObjectYieldsNull() {
        val events = state("""{"type":"m.room.banner","content":"mxc://example.org/x"}""")
        assertNull(roomBannerMxcFrom(events))
    }

    @Test
    fun theUrlIsReadAsAJsonPrimitiveSoAJsonNullBecomesNull() {
        // The explicit shape of the guarantee, since JsonNull is a JsonPrimitive and
        // only contentOrNull tells it apart from a real string.
        val url = Json.parseToJsonElement("null") as JsonPrimitive
        assertNull(url.contentOrNull)
    }
}