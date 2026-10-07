package livekit

import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.keys.DeviceKeys
import de.connect2x.trixnity.core.model.keys.Key
import de.connect2x.trixnity.core.model.keys.Keys
import de.connect2x.trixnity.core.model.keys.Signed
import de.connect2x.trixnity.crypto.sign.SignService
import de.connect2x.trixnity.crypto.sign.SignWith
import de.connect2x.trixnity.crypto.sign.VerifyResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ResilientSignService must never upgrade a failed verification to success.
 *
 * ## Why this test exists
 *
 * The service previously contained exactly this bug:
 *
 * ```kotlin
 * if (result is VerifyResult.Invalid && signed.signed is Key.SignedCurve25519Key) {
 *     return VerifyResult.Valid   // fail OPEN
 * }
 * ```
 *
 * `SignedCurve25519Key` is the type of a Matrix **one-time key**, i.e. the key an
 * olm session is established from. Accepting an unverifiable OTK lets a malicious
 * homeserver substitute its own key for a victim, Orbit builds an olm session with
 * it, and the operator then reads everything olm-encrypted to that device --
 * including the matrixRTC 16-byte call media key, i.e. every frame of every
 * encrypted-room call.
 *
 * It was wired into every MatrixClient, so this was not a rare code path.
 */
class ResilientSignServiceTest {

    /** A delegate that reports every verification as failed. */
    private class AlwaysInvalidSignService : SignService {
        override suspend fun getSelfSignedDeviceKeys(): Signed<DeviceKeys, UserId> = error("unused")
        override suspend fun signatures(jsonObject: JsonObject, signWith: SignWith): Map<UserId, Keys> = error("unused")
        override suspend fun <T> signatures(value: T, serializer: KSerializer<T>, signWith: SignWith): Map<UserId, Keys> = error("unused")
        override suspend fun <T> sign(value: T, serializer: KSerializer<T>, signWith: SignWith): Signed<T, UserId> = error("unused")
        override suspend fun signCurve25519Key(keyId: String, keyValue: String, fallback: Boolean?): Key.SignedCurve25519Key = error("unused")
        override suspend fun <T> verify(
            signed: Signed<T, UserId>,
            serializer: KSerializer<T>,
            signingKeys: Map<UserId, Set<Key.Ed25519Key>>
        ): VerifyResult = VerifyResult.Invalid("bad signature")
    }

    private fun <T> dummySerializer(name: String): KSerializer<T> = object : KSerializer<T> {
        override val descriptor: SerialDescriptor =
            kotlinx.serialization.descriptors.buildClassSerialDescriptor(name)
        override fun deserialize(decoder: Decoder): T = error("unused")
        override fun serialize(encoder: Encoder, value: T) = error("unused")
    }

    @Test
    fun koinOverrideTakesEffect() {
        val app = koinApplication {
            modules(
                module { single { "original" } },
                module { single { "override" } }
            )
        }
        assertEquals("override", app.koin.get<String>())
    }

    /**
     * The regression itself: a one-time key with an invalid signature must come
     * back Invalid, not Valid.
     */
    @Test
    fun invalidOneTimeKeySignatureIsNotUpgradedToValid() = runBlocking {
        val resilient = ResilientSignService(AlwaysInvalidSignService())

        val otkSigned: Signed<Key.SignedCurve25519Key, UserId> = Signed(
            signed = Key.SignedCurve25519Key(
                id = "AAAA",
                value = "fake_otk",
                fallback = false,
                signatures = emptyMap()
            ),
            signatures = emptyMap()
        )

        val result = resilient.verify(
            otkSigned,
            dummySerializer<Key.SignedCurve25519Key>("otk"),
            emptyMap()
        )

        assertTrue(
            result is VerifyResult.Invalid,
            "an unverifiable one-time key MUST NOT be accepted; got $result"
        )
    }

    /** Device keys were already verified correctly; pin that they still are. */
    @Test
    fun invalidDeviceKeySignatureStaysInvalid() = runBlocking {
        val resilient = ResilientSignService(AlwaysInvalidSignService())

        val devSigned: Signed<DeviceKeys, UserId> = Signed(
            signed = DeviceKeys(
                userId = UserId("@peer:matrix.org"),
                deviceId = "DEV1",
                algorithms = emptySet(),
                keys = Keys(emptySet())
            ),
            signatures = emptyMap()
        )

        val result = resilient.verify(devSigned, dummySerializer<DeviceKeys>("dev"), emptyMap())
        assertTrue(result is VerifyResult.Invalid, "got $result")
    }

    /**
     * A key type the class never knew about must also pass through untouched --
     * the guarantee is "never upgrades Invalid", not "rejects two known types".
     */
    @Test
    fun invalidEd25519DeviceIdentitySignatureIsNotUpgraded() = runBlocking {
        val resilient = ResilientSignService(AlwaysInvalidSignService())

        val signed: Signed<Key.Ed25519Key, UserId> = Signed(
            signed = Key.Ed25519Key(id = "ed25519:DEV1", value = "AAAA"),
            signatures = emptyMap()
        )

        val result = resilient.verify(signed, dummySerializer<Key.Ed25519Key>("ed"), emptyMap())
        assertTrue(result is VerifyResult.Invalid, "got $result")
    }

    // ---- the stub-key workaround -------------------------------------
    //
    // Trixnity 5.4.0 sometimes passes a "public key" whose value is its own id. That
    // string can never satisfy a verification equation, so the delegate returns
    // Invalid -- indistinguishable from a forgery, and cached as one. Filtering
    // those out turns it into MissingSignature, which is the "key not available yet"
    // answer that drives a refetch.

    @Test
    fun aKeyWhoseValueIsItsOwnIdIsNotVerifiable() {
        val resilient = ResilientSignService(AlwaysInvalidSignService())
        val stub = Key.Ed25519Key(
            id = "gFrKEjiLS9tgzOamqJ/c40/54qakD7BnnJxZg1v0thM",
            value = "gFrKEjiLS9tgzOamqJ/c40/54qakD7BnnJxZg1v0thM"
        )
        assertTrue(!resilient.isVerifiableEd25519(stub), "the stub shape must be rejected")
    }

    @Test
    fun aRealEd25519KeyIsVerifiable() {
        val resilient = ResilientSignService(AlwaysInvalidSignService())
        // A genuine key: id carries the `ed25519:` prefix, value is the raw base64,
        // and the two differ.
        val real = Key.Ed25519Key(
            id = "ed25519:DEV1",
            value = "JTr/PUdq3z1t1DkBHbnnlCLM/HVHw9o8ARCNvKjzq3myj0mOUkUBQQSlxV/j4J47sl9PUAwf8OT0Iqi8lbNKDw"
        )
        assertTrue(resilient.isVerifiableEd25519(real), "a real key must not be filtered out")
    }

    @Test
    fun aBlankValueIsNotVerifiable() {
        val resilient = ResilientSignService(AlwaysInvalidSignService())
        assertTrue(
            !resilient.isVerifiableEd25519(Key.Ed25519Key(id = "ed25519:DEV1", value = "")),
            "an empty value carries no key material"
        )
    }

    /**
     * Guards the decision to drop the length check.
     *
     * The value seen in the live failure was 86 characters -- 64 bytes decoded --
     * not the 43 characters of a bare 32-byte ed25519 key. A length assertion would
     * have thrown that away and, with it, the peers Orbit needs to reach.
     */
    @Test
    fun aLongRealKeyIsStillVerifiable() {
        val resilient = ResilientSignService(AlwaysInvalidSignService())
        val long = Key.Ed25519Key(
            id = "ed25519:DEV1",
            value = "JTr/PUdq3z1t1DkBHbnnlCLM/HVHw9o8ARCNvKjzq3myj0mOUkUBQQSlxV/j4J47sl9PUAwf8OT0Iqi8lbNKDw"
        )
        assertTrue(resilient.isVerifiableEd25519(long))
    }

    /**
     * The critical invariant: the filter must change WHICH keys the delegate sees,
     * never WHAT it concludes. A stub key is removed, but if the delegate still
     * returns Invalid (a genuine forgery) that must be passed through untouched.
     */
    @Test
    fun filteringStubsDoesNotConvertAnInvalidResultToValid() = runBlocking {
        var seenKeys: Map<UserId, Set<Key.Ed25519Key>>? = null
        val recording = object : SignService {
            override suspend fun getSelfSignedDeviceKeys(): Signed<DeviceKeys, UserId> = error("unused")
            override suspend fun signatures(jsonObject: JsonObject, signWith: SignWith): Map<UserId, Keys> = error("unused")
            override suspend fun <T> signatures(value: T, serializer: KSerializer<T>, signWith: SignWith): Map<UserId, Keys> = error("unused")
            override suspend fun <T> sign(value: T, serializer: KSerializer<T>, signWith: SignWith): Signed<T, UserId> = error("unused")
            override suspend fun signCurve25519Key(keyId: String, keyValue: String, fallback: Boolean?): Key.SignedCurve25519Key = error("unused")
            override suspend fun <T> verify(
                signed: Signed<T, UserId>,
                serializer: KSerializer<T>,
                signingKeys: Map<UserId, Set<Key.Ed25519Key>>
            ): VerifyResult {
                seenKeys = signingKeys
                return VerifyResult.Invalid("genuine forgery")
            }
        }

        val resilient = ResilientSignService(recording)
        val user = UserId("@peer:matrix.org")
        val stub = Key.Ed25519Key(
            id = "gFrKEjiLS9tgzOamqJ/c40/54qakD7BnnJxZg1v0thM",
            value = "gFrKEjiLS9tgzOamqJ/c40/54qakD7BnnJxZg1v0thM"
        )
        val real = Key.Ed25519Key(
            id = "ed25519:DEV1",
            value = "JTr/PUdq3z1t1DkBHbnnlCLM/HVHw9o8ARCNvKjzq3myj0mOUkUBQQSlxV/j4J47sl9PUAwf8OT0Iqi8lbNKDw"
        )

        val result = resilient.verify(
            Signed(signed = Key.Ed25519Key(id = "ed25519:DEV1", value = "AAAA"), signatures = emptyMap()),
            dummySerializer<Key.Ed25519Key>("ed"),
            mapOf(user to setOf(stub, real))
        )

        // The stub was dropped; the real one survived.
        val passed = seenKeys!![user]!!
        assertEquals(1, passed.size, "expected only the real key to reach the delegate")
        assertTrue(passed.contains(real))
        assertTrue(!passed.contains(stub))

        // And the verdict is untouched: a forgery is still a forgery.
        assertTrue(result is VerifyResult.Invalid, "got $result")
    }

    /** The user entry must survive even when every key was a stub. */
    @Test
    fun aUserWhoseKeysAreAllStubsKeepsAnEmptySetNotAMissingEntry() = runBlocking {
        var seen: Map<UserId, Set<Key.Ed25519Key>>? = null
        val recording = object : SignService {
            override suspend fun getSelfSignedDeviceKeys(): Signed<DeviceKeys, UserId> = error("unused")
            override suspend fun signatures(jsonObject: JsonObject, signWith: SignWith): Map<UserId, Keys> = error("unused")
            override suspend fun <T> signatures(value: T, serializer: KSerializer<T>, signWith: SignWith): Map<UserId, Keys> = error("unused")
            override suspend fun <T> sign(value: T, serializer: KSerializer<T>, signWith: SignWith): Signed<T, UserId> = error("unused")
            override suspend fun signCurve25519Key(keyId: String, keyValue: String, fallback: Boolean?): Key.SignedCurve25519Key = error("unused")
            override suspend fun <T> verify(
                signed: Signed<T, UserId>,
                serializer: KSerializer<T>,
                signingKeys: Map<UserId, Set<Key.Ed25519Key>>
            ): VerifyResult {
                seen = signingKeys
                return VerifyResult.MissingSignature("no key")
            }
        }

        val resilient = ResilientSignService(recording)
        val user = UserId("@peer:matrix.org")
        val stub = Key.Ed25519Key(id = "AAAA", value = "AAAA")

        resilient.verify(
            Signed(signed = Key.Ed25519Key(id = "ed25519:DEV1", value = "AAAA"), signatures = emptyMap()),
            dummySerializer<Key.Ed25519Key>("ed"),
            mapOf(user to setOf(stub))
        )

        assertTrue(seen!!.containsKey(user), "the user entry must be kept so the delegate can answer MissingSignature")
        assertTrue(seen!![user]!!.isEmpty())
    }
}