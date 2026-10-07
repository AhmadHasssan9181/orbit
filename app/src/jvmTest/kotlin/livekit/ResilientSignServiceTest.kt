package livekit

import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.keys.DeviceKeys
import de.connect2x.trixnity.core.model.keys.Key
import de.connect2x.trixnity.core.model.keys.Signed
import de.connect2x.trixnity.crypto.sign.SignService
import de.connect2x.trixnity.crypto.sign.VerifyResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResilientSignServiceTest {
    @Test
    fun testKoinOverride() {
        val m1 = module {
            single { "original" }
        }
        val m2 = module {
            single { "override" }
        }
        val app = koinApplication {
            modules(m1, m2)
        }
        val value: String = app.koin.get()
        assertTrue(value == "override")
    }

    @Test
    fun testBypassesInvalidSignedCurve25519Only() = runBlocking {
        val mockDelegate = object : SignService {
            override suspend fun getSelfSignedDeviceKeys(): Signed<DeviceKeys, UserId> = error("unused")
            override suspend fun signatures(jsonObject: kotlinx.serialization.json.JsonObject, signWith: de.connect2x.trixnity.crypto.sign.SignWith): Map<UserId, de.connect2x.trixnity.core.model.keys.Keys> = error("unused")
            override suspend fun <T> signatures(value: T, serializer: KSerializer<T>, signWith: de.connect2x.trixnity.crypto.sign.SignWith): Map<UserId, de.connect2x.trixnity.core.model.keys.Keys> = error("unused")
            override suspend fun <T> sign(value: T, serializer: KSerializer<T>, signWith: de.connect2x.trixnity.crypto.sign.SignWith): Signed<T, UserId> = error("unused")
            override suspend fun signCurve25519Key(keyId: String, keyValue: String, fallback: Boolean?): Key.SignedCurve25519Key = error("unused")
            override suspend fun <T> verify(
                signed: Signed<T, UserId>,
                serializer: KSerializer<T>,
                signingKeys: Map<UserId, Set<Key.Ed25519Key>>
            ): VerifyResult = VerifyResult.Invalid("bad signature")
        }

        val resilient = ResilientSignService(mockDelegate)

        // 1. One-time key: should be converted to Valid
        val otkKey = Key.SignedCurve25519Key(
            id = "AAAA",
            value = "fake_otk",
            fallback = false,
            signatures = emptyMap()
        )
        val otkSigned: Signed<Key.SignedCurve25519Key, UserId> = Signed(
            signed = otkKey,
            signatures = emptyMap()
        )
        val dummySerializer = object : KSerializer<Key.SignedCurve25519Key> {
            override val descriptor = kotlinx.serialization.descriptors.buildClassSerialDescriptor("dummy")
            override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Key.SignedCurve25519Key = error("unused")
            override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Key.SignedCurve25519Key) = error("unused")
        }
        val otkResult = resilient.verify(otkSigned, dummySerializer, emptyMap())
        assertEquals(VerifyResult.Valid, otkResult)

        // 2. DeviceKeys: must NOT be converted to Valid
        val devSigned: Signed<DeviceKeys, UserId> = Signed(
            signed = DeviceKeys(
                userId = UserId("@peer:matrix.org"),
                deviceId = "DEV1",
                algorithms = emptySet(),
                keys = de.connect2x.trixnity.core.model.keys.Keys(emptySet())
            ),
            signatures = emptyMap()
        )
        val dummyDevSerializer = object : KSerializer<DeviceKeys> {
            override val descriptor = kotlinx.serialization.descriptors.buildClassSerialDescriptor("dummyDev")
            override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): DeviceKeys = error("unused")
            override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: DeviceKeys) = error("unused")
        }
        val devResult = resilient.verify(devSigned, dummyDevSerializer, emptyMap())
        assertTrue(devResult is VerifyResult.Invalid)
    }
}
