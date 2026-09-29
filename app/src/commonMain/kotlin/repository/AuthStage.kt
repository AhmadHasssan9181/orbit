package repository

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One stage of a Matrix user-interactive authentication (UIAA) challenge.
 *
 * A homeserver answers a `/register` or `/login` it cannot complete with a 401
 * and a `flows` array, plus whatever stage it wants next. Almost every
 * self-hosted Synapse with `enable_registration: true` answers the first request
 * this way -- typically demanding `m.login.dummy` (the bot-check) and often
 * `m.login.terms`. So an app that only understands a bare 200 is, in practice,
 * broken against real self-hosted servers.
 */
sealed interface AuthStage {
    /** Nothing left to satisfy; the response carried credentials. */
    data object Complete : AuthStage

    /** `m.login.dummy` -- the "prove you are not a bot" stage. One click. */
    data object Dummy : AuthStage

    /** `m.login.terms` -- accept the server's policies before registering. */
    data class Terms(
        val policies: List<Policy>
    ) : AuthStage {
        data class Policy(val name: String, val url: String, val version: String)
    }

    /** `m.login.registration_token` -- a token the server's admin issued. */
    data class RegistrationToken(val reason: String?) : AuthStage

    /**
     * A stage we cannot complete, so the user has to finish it in the
     * homeserver's own web client. Rendered as an explanation, not a dead end.
     */
    data class Unsupported(val stageType: String) : AuthStage

    /**
     * `m.login.recaptcha` needs a webview to render Google's widget, which this
     * app has no path to. Surfaced honestly instead of looping.
     */
    data class Recaptcha(val siteKey: String) : AuthStage

    /** A stage the server listed but supplied no session for. */
    data class Malformed(val detail: String) : AuthStage
}

/**
 * Reads a homeserver auth response and decides what is still required.
 *
 * @param flows the `flows` array from the response, when present.
 * @param completed the `completed` array, when present.
 * @return the next stage to satisfy, or [AuthStage.Complete] when the response
 *   carried credentials.
 */
fun nextAuthStage(
    flows: JsonArray?,
    completed: JsonArray?,
    session: String?,
    hasCredentials: Boolean
): AuthStage {
    if (hasCredentials) return AuthStage.Complete

    val doneTypes = (completed ?: JsonArray(emptyList()))
        .mapNotNull { (it as? JsonPrimitive)?.content }
        .toSet()

    val remaining = (flows ?: JsonArray(emptyList()))
        .mapNotNull { it as? JsonObject }
        .filter { flow ->
            val stages = flow["stages"] as? JsonArray ?: return@filter false
            stages.mapNotNull { (it as? JsonPrimitive)?.content }
                .any { it !in doneTypes }
        }

    val sessionOk = !session.isNullOrBlank()
    if (!sessionOk) {
        return AuthStage.Malformed("The homeserver asked for more steps but did not give a session to continue them.")
    }

    // In order of how often each appears on a self-hosted Synapse, and how
    // easily we can satisfy it. `m.login.dummy` is checked first because Synapse
    // sends it alongside terms and it is the cheapest to complete.
    for (stage in remaining) {
        val stages = stage["stages"] as? JsonArray ?: continue
        for (raw in stages) {
            val type = (raw as? JsonPrimitive)?.content ?: continue
            if (type in doneTypes) continue
            when (type) {
                "m.login.dummy" -> return AuthStage.Dummy
                "m.login.terms" -> {
                    val raw = (stage["params"] as? JsonObject)?.get("policies")
                    val policies = mutableListOf<AuthStage.Terms.Policy>()
                    for (policy in (raw as? JsonArray).orEmpty()) {
                        val obj = policy as? JsonObject ?: continue
                        val url = (obj["url"] as? JsonPrimitive)?.content
                            ?: obj["url"]?.toString()?.trim('"')
                            ?: continue
                        val name = (obj["name"] as? JsonPrimitive)?.content ?: "terms"
                        val version = (obj["version"] as? JsonPrimitive)?.content ?: ""
                        policies.add(AuthStage.Terms.Policy(name, url, version))
                    }
                    return AuthStage.Terms(policies)
                }
                "m.login.registration_token" -> {
                    val reason = (stage["params"] as? JsonObject)
                        ?.get("reason")?.toString()?.trim('"')
                    return AuthStage.RegistrationToken(reason)
                }
                "m.login.recaptcha" -> {
                    val siteKey = (stage["params"] as? JsonObject)
                        ?.get("sitekey")?.toString()?.trim('"')
                    if (siteKey != null) return AuthStage.Recaptcha(siteKey)
                }
                else -> return AuthStage.Unsupported(type)
            }
        }
    }

    // Nothing left that we recognise. Either the server is satisfied and simply
    // did not send credentials (so the caller's fallback message is correct), or
    // it wants something we cannot express.
    return if (remaining.isEmpty()) {
        AuthStage.Complete
    } else {
        AuthStage.Malformed("The homeserver's remaining sign-in steps could not be read.")
    }
}
