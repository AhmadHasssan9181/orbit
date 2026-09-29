package repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The registration path against a real self-hosted Synapse.
 *
 * Almost every self-hosted server with `enable_registration: true` answers the
 * first `/register` with a 401 and a `flows` array, typically demanding
 * `m.login.dummy` and often `m.login.terms`. An app that only understands a
 * bare 200 reports "Registration failed" against every such server, which is
 * what made self-hosting look broken.
 */
class AuthStageTest {

    private fun flows(vararg stages: String): JsonArray =
        JsonArray(listOf(Json.parseToJsonElement("""{"stages":[${stages.joinToString(",")}]}""")))

    private fun stage(
        flows: JsonArray?,
        completed: List<String> = emptyList(),
        session: String? = "sess-1",
        creds: Boolean = false
    ) = nextAuthStage(
        flows = flows,
        completed = JsonArray(completed.map { Json.parseToJsonElement("\"$it\"") }),
        session = session,
        hasCredentials = creds
    )

    @Test
    fun `credentials mean complete`() {
        assertEquals(AuthStage.Complete, stage(null, creds = true))
    }

    @Test
    fun `the classic self-hosted reply asks for dummy`() {
        val s = stage(flows("\"m.login.dummy\""))
        assertEquals(AuthStage.Dummy, s)
    }

    @Test
    fun `dummy is preferred over terms because it is cheapest to satisfy`() {
        val s = stage(flows("\"m.login.dummy\"", "\"m.login.terms\""))
        assertEquals(AuthStage.Dummy, s)
    }

    @Test
    fun `terms is returned with its policy documents`() {
        val flowsJson = Json.parseToJsonElement(
            """
            {"stages":["m.login.terms"],
             "params":{"policies":[
                {"name":"privacy","url":"https://example.org/privacy","version":"1.2"},
                {"name":"rules","url":"https://example.org/rules"}
             ]}}
            """.trimIndent()
        )
        val s = nextAuthStage(JsonArray(listOf(flowsJson)), null, "sess-1", false)
        val terms = assertIs<AuthStage.Terms>(s)
        assertEquals(2, terms.policies.size)
        assertEquals("privacy", terms.policies[0].name)
        assertEquals("1.2", terms.policies[0].version)
        assertEquals("https://example.org/rules", terms.policies[1].url)
        assertEquals("", terms.policies[1].version)
    }

    @Test
    fun `a completed stage is skipped`() {
        // dummy already done, terms now demanded.
        val flowsJson = Json.parseToJsonElement("""{"stages":["m.login.dummy","m.login.terms"]}""")
        val s = nextAuthStage(
            JsonArray(listOf(flowsJson)),
            JsonArray(listOf(Json.parseToJsonElement("\"m.login.dummy\""))),
            "sess-1",
            false
        )
        assertIs<AuthStage.Terms>(s)
    }

    @Test
    fun `a registration token stage is surfaced with its reason`() {
        val flowsJson = Json.parseToJsonElement(
            """{"stages":["m.login.registration_token"],"params":{"reason":"ask the admin"}}"""
        )
        val s = nextAuthStage(JsonArray(listOf(flowsJson)), null, "sess-1", false)
        val token = assertIs<AuthStage.RegistrationToken>(s)
        assertEquals("ask the admin", token.reason)
    }

    @Test
    fun `recaptcha is surfaced rather than looped on`() {
        val flowsJson = Json.parseToJsonElement(
            """{"stages":["m.login.recaptcha"],"params":{"sitekey":"abc"}}"""
        )
        val s = nextAuthStage(JsonArray(listOf(flowsJson)), null, "sess-1", false)
        assertEquals(AuthStage.Recaptcha("abc"), s)
    }

    @Test
    fun `a stage we cannot complete is named, not hidden`() {
        val s = stage(flows("\"m.login.email.identity\""))
        assertEquals(AuthStage.Unsupported("m.login.email.identity"), s)
    }

    @Test
    fun `a missing session is reported instead of silently retrying forever`() {
        val s = stage(flows("\"m.login.dummy\""), session = null)
        assertIs<AuthStage.Malformed>(s)
        assertTrue(s.detail.contains("session"))
    }

    @Test
    fun `everything completed with no credentials leaves the caller to decide`() {
        val s = stage(flows("\"m.login.dummy\""), completed = listOf("m.login.dummy"))
        assertEquals(AuthStage.Complete, s)
    }
}
