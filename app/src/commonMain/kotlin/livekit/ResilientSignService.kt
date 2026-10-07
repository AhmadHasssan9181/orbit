package livekit

import de.connect2x.trixnity.client.MatrixClientConfiguration
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.keys.Key
import de.connect2x.trixnity.core.model.keys.Signed
import de.connect2x.trixnity.crypto.sign.SignService
import de.connect2x.trixnity.crypto.sign.SignServiceImpl
import de.connect2x.trixnity.crypto.sign.VerifyResult
import kotlinx.serialization.KSerializer
import org.koin.dsl.module

/**
 * Signature verification that logs loudly instead of silently.
 *
 * ## Why the `Invalid -> Valid` downgrade was removed
 *
 * This class used to contain:
 *
 * ```kotlin
 * val result = delegate.verify(signed, serializer, signingKeys)
 * if (result is VerifyResult.Invalid && signed.signed is Key.SignedCurve25519Key) {
 *     println("WARN [ResilientSignService] Bypassing invalid signature ...")
 *     return VerifyResult.Valid          // <-- fail OPEN
 * }
 * ```
 *
 * That is the most severe defect this codebase has had, and it was wired into
 * **every** MatrixClient via `configureResilientCrypto()` (password login, SSO
 * login, and both session-restore paths).
 *
 * `Key.SignedCurve25519Key` is exactly the type of a Matrix **one-time key**. OTKs
 * are what an olm session is established from: `/keys/claim` returns them, and the
 * returned keys are consumed to build the encrypted channel. Blanket-accepting an
 * invalid OTK signature therefore means:
 *
 *  - a malicious or compromised homeserver substitutes its own Curve25519 OTK for a
 *    victim's device;
 *  - Orbit accepts it and builds an olm session with it;
 *  - the attacker holds the other side of that session.
 *
 * Everything olm-encrypted to that device is then readable by the homeserver
 * operator, including the matrixRTC 16-byte **call media key** (so every frame of
 * every encrypted-room call) and Megolm session keys for all encrypted rooms.
 *
 * It also silently defeated the guarantee the device-key path documents: routing
 * keys through `OutdatedKeysHandler` verifies the *identity* key, while the
 * *one-time* key used for the handshake no longer was.
 *
 * ## Why it was added
 *
 * OTK signatures from some homeservers fail to verify in Trixnity, and the symptom
 * is a call where nobody can address you and no key exchange ever happens. The
 * honest fix for that is to find why verification fails -- the `scratch/` scripts
 * (`drain_otks.py`, `test_otk_sig.py`, `verify_sig.py`) are that investigation, and
 * a signature over a differently-canonicalised JSON body is the usual cause.
 *
 * Until that is understood, the failure must be **visible**, not papered over.
 * Orbit will simply fail closed on an unverifiable key, which is the correct
 * behaviour: better no call than a silently forged one.
 *
 * The class is retained because the call sites are a useful place to report
 * verification problems, and because removing it outright would delete the
 * diagnostic surface the next person needs.
 */
class ResilientSignService(
    private val delegate: SignService
) : SignService by delegate {
    override suspend fun <T> verify(
        signed: Signed<T, UserId>,
        serializer: KSerializer<T>,
        signingKeys: Map<UserId, Set<Key.Ed25519Key>>
    ): VerifyResult {
        val usable = filterUsableSigningKeys(signingKeys)
        val result = delegate.verify(signed, serializer, usable)
        if (result is VerifyResult.Invalid) {
            // Report it, and return `result` unchanged. An unverifiable signature is
            // not a stylistic problem: it is the only signal that a key may not
            // belong to the device claiming it.
            util.OrbitLog.w(
                "SignService",
                "Signature verification FAILED for ${signed.signed?.let { it::class.simpleName }}: " +
                    "${result.reason}. Refusing to accept this key. If this is your " +
                    "homeserver and calls cannot find peers, this line is the reason -- " +
                    "do NOT work around it by accepting the key."
            )
        }
        return result
    }

    /**
     * Drop signing keys that are structurally incapable of verifying anything.
     *
     * ## The defect this works around
     *
     * Trixnity 5.4.0 sometimes hands `verify()` a "public key" whose **value is its
     * own id**, e.g.:
     *
     * ```
     * public key (id=gFrK..., value=gFrK...)   <- the id echoed back, not a key
     * signature (id=gFrK..., value=JTr/PU...)   <- the actual ed25519 key
     * ```
     *
     * A string that is not a real public key can never satisfy a verification
     * equation, so the delegate returns `Invalid("Verification equation was not
     * satisfied")` -- which reads exactly like a forged signature, and gets cached
     * as a negative trust verdict for a device that is perfectly legitimate.
     *
     * This is a **bug in the key lookup, not a homeserver attack**: the id matches
     * the signature's id, so the real key was simply never substituted in.
     *
     * ## Why filtering is the right fix
     *
     * `SignServiceImpl.verify` already distinguishes the two situations we care
     * about, and that is the whole reason this is safe:
     *
     *  - key id not found among the supplied keys -> `MissingSignature`, which is the
     *    "we do not have the key yet" answer, and is what drives a refetch.
     *  - key found but the equation fails -> `Invalid`, a real forgery verdict.
     *
     * By removing the stub keys *before* delegating, a lookup bug degrades to
     * `MissingSignature` and the existing outdated-key refetch path repairs it --
     * while a genuine forgery still produces `Invalid` and is still refused.
     *
     * ## What this is NOT
     *
     * This is not the bypass that used to live here. That one rewrote `Invalid` to
     * `Valid` for every `SignedCurve25519Key`, i.e. it disabled one-time-key
     * signature checking entirely and let a substituted OTK receive olm-encrypted
     * media keys. Nothing here converts a failure into a success: a key that cannot
     * be *found* is not the same claim as a key that was *proven wrong*, and only the
     * second one is ever allowed to become `Valid`.
     *
     * ## False positives
     *
     * The test is deliberately narrow, and it is the *only* test: `value == id`.
     *
     * A length check was tried first and removed. It looked reasonable -- an
     * ed25519 public key is 32 bytes -- but it misclassified real keys: the value
     * observed in a live failure was 86 characters (64 bytes decoded), not the 43
     * characters a bare 32-byte ed25519 key would be. Guessing at the expected key
     * shape from the outside is exactly the kind of assumption that turns a
     * workaround into a new bug, so the check is restricted to the one property the
     * failure actually exhibits.
     *
     * `value == id` has essentially no false-positive risk: a genuine key's value is
     * base64 key material, while its id carries the `ed25519:` prefix, so the two
     * are never the same string.
     */
    private fun filterUsableSigningKeys(
        signingKeys: Map<UserId, Set<Key.Ed25519Key>>
    ): Map<UserId, Set<Key.Ed25519Key>> {
        var dropped = 0
        val result = LinkedHashMap<UserId, Set<Key.Ed25519Key>>(signingKeys.size)
        for ((user, keys) in signingKeys) {
            val usable = LinkedHashSet<Key.Ed25519Key>(keys.size)
            for (key in keys) {
                if (isVerifiableEd25519(key)) usable += key else dropped++
            }
            // Keep the user even with an empty set: an empty set is what makes the
            // delegate answer MissingSignature rather than throwing.
            result[user] = usable
        }
        if (dropped > 0) {
            logStubOnce(dropped)
        }
        return result
    }

    /**
     * True when [key] carries key material rather than an echo of its own id.
     *
     * Exposed for tests; not part of any interface.
     */
    internal fun isVerifiableEd25519(key: Key.Ed25519Key): Boolean {
        val value = key.value.value
        if (value.isBlank()) return false
        // The stub shape: the value is the id, so there is no key material at all
        // and no equation can be satisfied with it.
        if (value == key.id) return false
        return true
    }

    /**
     * Report the stub keys, but only the first time.
     *
     * `verify` is called for every device key on every sync, so an unfiltered log
     * here is thousands of lines an hour and would drown the lines that matter.
     */
    private fun logStubOnce(dropped: Int) {
        if (stubLogged.getAndSet(true)) return
        util.OrbitLog.w(
            "SignService",
            "SECURITY WORKAROUND: $dropped signing key(s) had their own id in the value " +
                "field, which cannot verify a signature. They are being treated as " +
                "'key not available' so the keys get refetched, instead of being " +
                "recorded as forged. This is an upstream key store anomaly where a key id " +
                "is populated in the value field; reported once per process."
        )
    }

    private companion object {
        val stubLogged = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}

fun MatrixClientConfiguration.configureResilientCrypto() {
    modulesFactories = modulesFactories + listOf {
        module {
            single<SignService> {
                val original = SignServiceImpl(get(), get(), get(), get())
                ResilientSignService(original)
            }
        }
    }
}