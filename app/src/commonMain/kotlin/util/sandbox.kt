package util

import de.connect2x.trixnity.client.CryptoDriverModule
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.media.okio.okio
import de.connect2x.trixnity.client.MediaStoreModule
import de.connect2x.trixnity.client.store.repository.room.room
import de.connect2x.trixnity.client.RepositoriesModule
import de.connect2x.trixnity.client.create
import de.connect2x.trixnity.client.cryptodriver.vodozemac.vodozemac
import de.connect2x.trixnity.clientserverapi.client.MatrixClientAuthProviderData
import de.connect2x.trixnity.clientserverapi.client.classicLoginWithPassword
import de.connect2x.trixnity.clientserverapi.model.authentication.IdentifierType
import io.ktor.http.Url
import okio.Path.Companion.toPath

suspend fun sandboxLogin() {
    val authProviderData = MatrixClientAuthProviderData.classicLoginWithPassword(
        baseUrl = Url("https://matrix.org"),
        identifier = IdentifierType.User("user"),
        password = "pwd"
    ).getOrThrow()

    val client = MatrixClient.create(
        repositoriesModule = RepositoriesModule.room(getRoomDatabaseBuilder()),
        mediaStoreModule = MediaStoreModule.okio("media".toPath()),
        cryptoDriverModule = CryptoDriverModule.vodozemac(),
        authProviderData = authProviderData
    ).getOrThrow()
}
