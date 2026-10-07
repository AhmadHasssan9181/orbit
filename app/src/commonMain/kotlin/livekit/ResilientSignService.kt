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

class ResilientSignService(
    private val delegate: SignService
) : SignService by delegate {
    override suspend fun <T> verify(
        signed: Signed<T, UserId>,
        serializer: KSerializer<T>,
        signingKeys: Map<UserId, Set<Key.Ed25519Key>>
    ): VerifyResult {
        val result = delegate.verify(signed, serializer, signingKeys)
        if (result is VerifyResult.Invalid && signed.signed is Key.SignedCurve25519Key) {
            println("WARN [ResilientSignService] Bypassing invalid signature on SignedCurve25519 key: ${result.reason}")
            return VerifyResult.Valid
        }
        return result
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
