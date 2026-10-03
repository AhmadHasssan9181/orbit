package repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import livekit.MatrixRtcCalls

/**
 * One `m.call*` state event, reduced to the three fields that decide whether a
 * room currently has a call.
 */
internal data class CallStateEntry(
    val type: String,
    val stateKey: String,
    val content: JsonObject,
    val originServerTs: Long?
)

/**
 * Decides "does this room have a call happening right now" from its MatrixRTC
 * state, with no network access and no side effects.
 *
 * This exists because the answer was being computed inline inside
 * `resolveGroupCallJoinTarget`, where two separate mistakes had accumulated and
 * neither was visible:
 *
 *  1. A **terminated** call definition was skipped over rather than acted on, so
 *     the scan carried on into the membership branch. `m.terminated` is how a
 *     client says "this call is over"; ignoring it and then believing a leftover
 *     `m.call.member` record is exactly backwards.
 *
 *  2. The membership branch accepted **any** non-leave record, with no expiry
 *     check at all. Membership windows are hours long (see [MatrixRtcMembership]),
 *     and nothing ever deletes the events, so a room that hosted a call once
 *     reported a live call for the rest of time.
 *
 * The user-visible consequences were not subtle: a "join the call" banner in
 * every room that had ever had one, a 3-second roster poller running in each of
 * them forever, and a member list under every channel showing everyone who had
 * ever been in that room's calls as if they were in it now.
 *
 * Pulled out here because a rule this consequential, this easy to get backwards,
 * and this invisible in testing, belongs in a place where it can be pinned down.
 */
internal object CallStateResolver {

    private val DEFINITION_TYPES = setOf("m.call", "org.matrix.msc3401.call")
    private val MEMBER_TYPES = setOf("m.call.member", "org.matrix.msc3401.call.member")

    private fun jsonString(content: JsonObject, key: String): String? =
        runCatching { (content[key] as? JsonPrimitive)?.content }.getOrNull()

    private fun jsonLong(content: JsonObject, key: String): Long? =
        runCatching {
            (content[key] as? JsonPrimitive)?.content?.toLongOrNull()
        }.getOrNull()

    /**
     * Has a call definition aged out?
     *
     * `m.terminated` is not the only way a call ends, and treating it as the only
     * one is what left rooms showing a live call indefinitely. A definition
     * carries a `lifetime` in milliseconds, and it stops being a call
     * `origin_server_ts + lifetime` milliseconds after it was sent -- whether or
     * not anyone got round to terminating it. A client that crashed, was killed,
     * or simply lost connectivity at hangup never publishes the termination, and
     * its definition then sits there un-terminated forever.
     *
     * This is exactly the shape of the rooms in the reported bug: an `m.call`
     * definition that is not terminated, declares no LiveKit focus Orbit can use,
     * and is far older than its own lifetime.
     *
     * Returns false when either input is missing. "Cannot prove it expired" must
     * not read as "expired" -- that would make a genuinely live, freshly created
     * call unjoinable because a field was spelled unusually.
     */
    internal fun definitionAgedOut(
        content: JsonObject,
        originServerTs: Long?,
        now: Long
    ): Boolean {
        if (originServerTs == null || originServerTs <= 0) return false
        val lifetime = jsonLong(content, "lifetime")
            // No lifetime to age out against, so fall back to the generous floor
            // rather than to "never". See the constant's note for why.
            ?: return originServerTs + DEFINITION_WITHOUT_LIFETIME_MAX_AGE_MS <= now
        if (lifetime <= 0) return false
        return originServerTs + lifetime <= now
    }

    /**
     * A definition with NO `lifetime` is treated as aged out once it is older than
     * [DEFINITION_WITHOUT_LIFETIME_MAX_AGE_MS].
     *
     * This reverses a deliberate earlier decision, and the reversal is the whole
     * point of the change. The old comment said: "Definitions Orbit published
     * before `DEFINITION_LIFETIME_MS` existed carry no `lifetime`, cannot be proven
     * expired, and are deliberately left alone -- 'cannot prove' must not be read
     * as 'expired'." Correct in isolation, and it cost a 3-second `/state`
     * download for the rest of the session.
     *
     * A live log from a real user showed the cost:
     *
     * ```
     * DEBUG [MatrixCallsRepository] roster tick !GfDlxb…: current call began 10871050237ms ago
     * ```
     *
     * repeating every three seconds, for the whole session, on a definition
     * 10,871,050 seconds -- **125 days** -- old, with no `lifetime` to age out
     * against. It accounted for **44.7% of that user's entire log file**. The
     * self-terminating guard reads `definitionAgedOut`, so with no lifetime to
     * compare against it returned false forever and the guard could never fire.
     *
     * The asymmetry that made "cannot prove" untenable: this is not a case where
     * failing to prove expiry makes a LIVE call unjoinable. It is the opposite
     * direction -- treating an ancient definition as live keeps a poller running
     * and a room rendered as having a call in it. Both failure modes are bad, but
     * one costs a stuck UI and the other costs unbounded background work against
     * the homeserver, forever, for a call nobody is in.
     *
     * The floor is deliberately generous (12 hours) and far longer than any real
     * call: it only has to be longer than "a call someone is actually in", so the
     * practical cost of being wrong is nil, while the benefit is that a stale
     * definition stops costing anything within a day.
     */
    private const val DEFINITION_WITHOUT_LIFETIME_MAX_AGE_MS = 12L * 60L * 60L * 1000L

    /** Where the answer came from, for the debug log. */
    const val SOURCE_DEFINITION = "definition"
    const val SOURCE_ALL_STATE_DEFINITION = "all-state-definition"
    const val SOURCE_DEFINITION_NO_FOCUS = "all-state-definition-no-focus"
    const val SOURCE_MEMBER_EVENT = "member-event"

    data class Verdict(
        val exists: Boolean,
        val callId: String?,
        val serviceUrl: String?,
        val source: String?,
        /** Why nothing was accepted, when [exists] is false. */
        val reason: String? = null
    ) {
        fun toResolved(): ResolvedGroupCall =
            if (exists) ResolvedGroupCall(true, callId, serviceUrl, source) else ResolvedGroupCall.None
    }

    /**
     * [liveKitFocusOf] is injected rather than reached for directly so the caller
     * keeps ownership of `RtcFocusParser`; this object stays pure and testable.
     */
    fun resolve(
        entries: List<CallStateEntry>,
        liveKitFocusOf: (JsonObject) -> String?,
        nowMs: Long = io.ktor.util.date.getTimeMillis()
    ): Verdict {
        var sawTerminatedDefinition = false
        var sawAgedOutDefinition = false
        var sawAnyDefinition = false
        var liveDefinitionWithoutFocus: Verdict? = null
        var memberFallback: Verdict? = null

        for (entry in entries) {
            when {
                entry.type in DEFINITION_TYPES -> {
                    sawAnyDefinition = true
                    val terminated = MatrixRtcCalls.isDefinitionTerminated(entry.content)
                    val agedOut = definitionAgedOut(entry.content, entry.originServerTs, nowMs)
                    if (terminated || agedOut) {
                        sawTerminatedDefinition = true
                        if (agedOut && !terminated) {
                            sawAgedOutDefinition = true
                        }
                    } else {
                        val focus = liveKitFocusOf(entry.content)
                        if (focus != null) {
                            return Verdict(
                                exists = true,
                                callId = entry.stateKey.ifBlank { "m.call" },
                                serviceUrl = focus,
                                source = SOURCE_ALL_STATE_DEFINITION
                            )
                        }
                        if (liveDefinitionWithoutFocus == null) {
                            liveDefinitionWithoutFocus = Verdict(
                                exists = true,
                                callId = entry.stateKey.ifBlank { "m.call" },
                                serviceUrl = null,
                                source = SOURCE_DEFINITION_NO_FOCUS
                            )
                        }
                    }
                }

                entry.type in MEMBER_TYPES && jsonString(entry.content, "membership") != "leave" -> {
                    // An unexpired membership is necessary but NOT sufficient:
                    // the window is hours long, so a record from yesterday passes.
                    if (!MatrixRtcMembership.isCurrentlyValid(entry.content)) continue
                    val focus = liveKitFocusOf(entry.content)
                    if (focus != null && memberFallback == null) {
                        memberFallback = Verdict(
                            exists = true,
                            callId = callIdOf(entry),
                            serviceUrl = focus,
                            source = SOURCE_MEMBER_EVENT
                        )
                    }
                }
            }
        }

        // A live definition outranks a terminated one: a room can hold the old
        // call's terminated definition AND a fresh call's live one, and the fresh
        // one is the truth.
        liveDefinitionWithoutFocus?.let {
            return it.copy(
                reason = "live call definition present (no focus declared)"
            )
        }

        if (memberFallback != null) {
            if (sawTerminatedDefinition) {
                // The decisive rule. The room has a definition that is over --
                // either explicitly terminated, or aged out past its own lifetime
                // because whoever started the call never got round to terminating
                // it. Either way every membership record in the room belongs to a
                // call that has ended, and those leftovers are permanent.
                return Verdict(
                    exists = false,
                    callId = null,
                    serviceUrl = null,
                    source = null,
                    reason = if (sawAgedOutDefinition) {
                        "call definition past its lifetime; membership records are leftovers from a call that ended"
                    } else {
                        "terminated call definition; membership records are leftovers from a call that ended"
                    }
                )
            }
            return memberFallback
        }

        return Verdict(
            exists = false,
            callId = null,
            serviceUrl = null,
            source = null,
            reason = when {
                sawAgedOutDefinition -> "call definition past its lifetime"
                sawTerminatedDefinition -> "call terminated"
                sawAnyDefinition -> "definition present but unusable"
                else -> "no MatrixRTC state"
            }
        )
    }

    private fun callIdOf(entry: CallStateEntry): String =
        jsonString(entry.content, "call_id")?.takeIf { it.isNotBlank() }
            ?: entry.stateKey.substringAfterLast("_").takeIf { it.isNotBlank() && it != entry.stateKey }
            ?: "m.call"

    /**
     * Parses a raw `/state` array into the entries [resolve] needs.
     *
     * Per element, never per document. The whole-array parse used to sit inside a
     * single `runCatching`, so ONE malformed element -- and a state array can
     * legitimately contain elements that are not objects -- threw the parse away
     * and handed back an empty list. An empty list reads as "this room has no
     * MatrixRTC state", which is the exact opposite of the truth, and quietly
     * turned every room into one Orbit believed had never had a call.
     */
    fun parseStateArray(raw: String): List<CallStateEntry> = runCatching {
        val array = Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray
            ?: return emptyList()
        array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val type = jsonString(obj, "type") ?: return@mapNotNull null
            CallStateEntry(
                type = type,
                stateKey = jsonString(obj, "state_key") ?: "",
                // Content-less events become empty objects rather than being
                // dropped, so a membership event with no content still reaches the
                // resolver and is rejected there on its merits.
                content = obj["content"] as? JsonObject ?: JsonObject(emptyMap()),
                originServerTs = runCatching {
                    jsonString(obj, "origin_server_ts")?.toLong()
                }.getOrNull()
            )
        }
    }.getOrElse { emptyList() }
}
