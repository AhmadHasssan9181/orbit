package webrtc

/**
 * Result of asking whether a room's media should be end-to-end encrypted.
 *
 * ## Why this is not a Boolean
 *
 * The probe used to be `suspend (String) -> Boolean`, and "the server did not
 * tell us" was represented by `false` -- the same value as "the server told us
 * this room is not encrypted". That conflation is a security bug, because
 * `false` is what arms the call to publish **cleartext** media to the LiveKit
 * SFU.
 *
 * Every way the old probe could produce `false` for an encrypted room:
 *
 *  - HTTP `429` (rate limited), `500`/`502`/`503` (Synapse restarting, worker
 *    down), `403`, `404`
 *  - any thrown exception: timeout, TLS blip, socket reset
 *  - the checker not wired up at all (`?.invoke(roomId) ?: false`)
 *
 * Each of those is ordinary operational noise, and each one silently downgraded
 * microphone, camera and screen-share frames to cleartext inside a room the user
 * believed was encrypted. The only signal was a `println`, which a jpackage
 * GUI-subsystem launcher discards. No TLS break was required by an attacker --
 * inducing one HTTP error on one endpoint was enough.
 *
 * Making UNKNOWN a distinct value means the caller has to decide what to do
 * about it, and [CallManager] decides the only safe thing: refuse to join.
 *
 * @see CallManager.probeRoomEncryption
 */
enum class RoomEncryptionVerdict {
    /** The server affirmatively reported an `m.room.encryption` state event. */
    ENCRYPTED,

    /**
     * The server affirmatively reported that the room has no encryption state
     * event (a clean `404` on `/state/m.room.encryption`). Publishing cleartext
     * here is correct Matrix semantics and matches Element.
     */
    NOT_ENCRYPTED,

    /**
     * The probe could not establish an answer: non-404 error, thrown exception,
     * or the checker was never wired.
     *
     * Must never be treated as [NOT_ENCRYPTED]. Doing so is the vulnerability this
     * type exists to prevent.
     */
    UNKNOWN;

    /** Only an affirmative [ENCRYPTED] may arm native E2EE media. */
    val shouldArmMediaE2EE: Boolean get() = this == ENCRYPTED

    /**
     * Whether a call may proceed without native media E2EE.
     *
     * True only for an affirmative [NOT_ENCRYPTED]. [UNKNOWN] returns false so the
     * join is blocked rather than downgraded.
     */
    val mayProceedWithoutMediaE2EE: Boolean get() = this == NOT_ENCRYPTED
}