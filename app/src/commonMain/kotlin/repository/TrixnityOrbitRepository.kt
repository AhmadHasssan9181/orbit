package repository

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.client.loginWithPassword
import net.folivo.trixnity.client.store.repository.RealmStore
import ui.model.OrbitChannel
import ui.model.OrbitMessage
import ui.model.OrbitServer
import ui.model.OrbitUser
import io.ktor.http.Url

class TrixnityOrbitRepository : OrbitRepository {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var matrixClient: MatrixClient? = null

    override suspend fun login(identifier: String, password: String, deviceName: String): AuthResult {
        // We assume identifier is something like "@user:server.com"
        val userId = net.folivo.trixnity.core.model.UserId(identifier)
        val hsUrl = Url("https://${userId.domain}")

        // TODO: Initialize Trixnity client
        
        return AuthResult("dummy-token", "dummy-device", identifier, hsUrl.toString())
    }

    override fun observeSpaces(): Flow<List<OrbitServer>> {
        return flowOf(emptyList())
    }

    override fun observeChannelList(spaceId: String): Flow<List<OrbitChannel>> {
        return flowOf(emptyList())
    }

    override suspend fun getMessagesForChannel(channelId: String, limit: Int, fromToken: String?): List<OrbitMessage> {
        return emptyList()
    }

    override suspend fun sendMessage(channelId: String, content: String): Result<String> {
        return Result.failure(NotImplementedError("Not implemented yet"))
    }

    override suspend fun editMessage(eventId: String, newBody: String): Result<Unit> {
        return Result.failure(NotImplementedError("Not implemented yet"))
    }

    override suspend fun getRoomMembers(roomId: String): List<OrbitUser> {
        return emptyList()
    }

    override suspend fun getCurrentUser(): OrbitUser? {
        return null
    }

    override fun observeMessages(channelId: String): Flow<List<OrbitMessage>> {
        return flowOf(emptyList())
    }
}
