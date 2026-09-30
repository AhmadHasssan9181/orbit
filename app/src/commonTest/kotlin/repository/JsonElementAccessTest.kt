package repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `JsonElement.jsonPrimitive` is an ASSERTION, not a cast.
 *
 * It throws `IllegalArgumentException` when the element is a `JsonObject`, and
 * the optional-chaining form `raw["content"]?.jsonPrimitive` looks like a safe
 * read right up until it isn't. Two bugs came out of that:
 *
 * - `isVoiceRoom` read `raw["content"]?.jsonPrimitive` on a `m.state` array. For
 *   `m.call.member`, `content` IS an object -- it is the whole membership record
 *   -- so every MatrixRTC room could throw. It was masked by a
 *   `catch (_: Throwable) { false }` around the entire parse, so the observable
 *   effect was a silently wrong answer rather than a crash.
 * - Moving that fetch into a shared `roomStateEvents` took the scan out of the
 *   catch, and the same latent throw became a real crash that took the drawer
 *   down with an error dialog. Confirmed in a run:
 *   `Element class kotlinx.serialization.json.JsonObject (Kotlin reflection is not
 *   available) is not a JsonPrimitive` at `isVoiceRoom`, on the AWT event thread.
 *
 * These pin the behaviour so a future reader does not "simplify" the safe casts
 * back into the throwing form.
 */
class JsonElementAccessTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `jsonPrimitive throws on an object, which is why as-is-not-optional`() {
        val obj = json.parseToJsonElement("""{"a":1}""")
        assertFailsWith<IllegalArgumentException> { obj.jsonPrimitive }
    }

    @Test
    fun `jsonPrimitive returns the value for a real primitive`() {
        val obj = json.parseToJsonElement("""{"a":"voice"}""").jsonObject
        assertEquals("voice", (obj["a"] as? JsonPrimitive)?.content)
    }

    @Test
    fun `as-is-null on an object, which is what the callers rely on`() {
        // The shape of every `m.call.member` content, and the exact input that
        // crashed the drawer.
        val content = json.parseToJsonElement(
            """{"application":"m.call","memberships":[{"device_id":"ABC"}]}"""
        )
        assertEquals(null, content as? JsonPrimitive)
    }

    @Test
    fun `optional chaining on a present object still throws`() {
        // Documents WHY the callers cast instead. `obj["content"]` is non-null
        // here, so the `?.` does not short-circuit and `jsonPrimitive` throws on
        // the object -- the failure is not avoided by the safe call.
        val obj = json.parseToJsonElement("""{"content":{"x":1}}""").jsonObject
        assertFailsWith<IllegalArgumentException> { obj["content"]?.jsonPrimitive }
    }

    @Test
    fun `optional chaining on a null field is genuinely null`() {
        val obj = json.parseToJsonElement("""{"other":1}""").jsonObject
        assertEquals(null, (obj["content"] as? JsonPrimitive)?.content)
    }
}
