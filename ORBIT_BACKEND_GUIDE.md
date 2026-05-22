# Orbit Backend Integration Guide — Matrix Protocol

> **Status:** Planning Phase  
> **Backend Target:** [Matrix](https://matrix.org/) (open, decentralized communication protocol)  
> **Frontend:** Jetpack Compose Multiplatform (this project)

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Data Model Mapping](#data-model-mapping)
4. [Backend Interface](#backend-interface)
5. [Implementation Roadmap](#implementation-roadmap)
6. [Security & Privacy](#security--privacy)
7. [Testing Strategy](#testing-strategy)

---

## Overview

Orbit is designed to be backend-agnostic at the UI layer. All data flows through well-defined interfaces that abstract away the underlying transport protocol. This document describes how to connect Orbit's existing Compose UI to a **Matrix** homeserver.

### Why Matrix?

| Feature | Benefit for Orbit |
|---------|-------------------|
| Decentralized | Users can run their own homeservers (self-hosting) |
| E2EE by default | Per-channel encryption via `m.room.encryption` state events |
| Rich state model | Native support for rooms, channels, members, presence |
| Bridges | Connect to Discord, Slack, Telegram via matrix-appservice-* |
| Open protocol | No vendor lock-in; fully queryable REST/WS APIs |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     ORBIT UI LAYER                          │
│  ┌──────────┬──────────────┬────────────┬────────────────┐  │
│  │ServerRail│ChannelDrawer │ ChatScreen │ SettingsScreen │  │
│  │(72dp)    │ (240dp)      │ (flexible) │ (full-screen)  │  │
│  └────┬─────┴──────┬───────┴─────┬──────┴───────┬────────┘  │
│       │             │             │              │           │
│       └─────────────┴─────────────┴──────────────┘           │
│                            │                                 │
│                    OrbitApp (state holder)                   │
│                            │                                 │
│                  ┌─────────▼─────────┐                       │
│                  │  OrbitRepository   │  ← ABstraction Layer │
│                  └─────────┬─────────┘                       │
└────────────────────────────┼─────────────────────────────────┘
                             │
              ┌──────────────▼──────────────┐
              │      Matrix Client SDK       │
              │   (kotlinx.coroutines +     │
              │    io.element:android-sdk)  │
              └──────────────┬──────────────┘
                             │
              ┌──────────────▼──────────────┐
              │      Homeserver API          │
              │   /_matrix/client/v3/        │
              │   /_matrix/media/r0/         │
              │   WebSocket (CSAPI)          │
              └─────────────────────────────┘
```

### Key Principle: Repository Pattern

All UI composables receive **data classes** (`OrbitUser`, `OrbitChannel`, `OrbitMessage`, etc.) and know nothing about Matrix. The `OrbitRepository` translates between the two domains.

---

## Data Model Mapping

### Orbit → Matrix Event Mapping

| Orbit Concept | Matrix Equivalent | Notes |
|--------------|-------------------|-------|
| `OrbitServer` | `m.room.create` + room state | Each server = a Matrix room or a group of related rooms under a common space (`m.space.parent`) |
| `OrbitChannel` | Individual `m.room.*` rooms | Text channels → standard rooms; Voice channels → rooms with voice bridge (e.g., Conduit + Jitsi) |
| `OrbitUser` | `@user:server.com` display name | Mapped via `Profile` API (`/profile/{userId}`) |
| `OrbitMessage` | `m.room.message` event | Body → `format: org.matrix.custom.html` or plain text |
| Channel category | `m.space.parent` relation | Channels in a category are child rooms of a Matrix Space |

### Detailed Mapping

```kotlin
// ─── OrbitServer ↔ Matrix Room/Space ──────────────────────
data class OrbitServer(
    val id: String,              // room_id or space_id
    val name: String,            // room / space name
    val iconColor: Long,         // derived from m.room.topic or custom state
    val description: String,     // m.room.topic.body
    val isDM: Boolean            // 1:1 room check (no visible members)
)

// ─── OrbitChannel ↔ Matrix Room ───────────────────────────
data class OrbitChannel(
    val id: String,              // room_id
    val name: String,            // !roomname:server → "roomname"
    val type: ChannelType,       // TEXT = normal room, VOICE = bridged room
    val categoryId: String?,     // parent space_id (m.space.parent relation)
    val topic: String            // m.room.topic.body
)

// ─── OrbitMessage ↔ m.room.message ────────────────────────
data class OrbitMessage(
    val id: String,              // event_id
    val userId: String,          // @user:server (sender)
    val username: String,        // display name / power level
    val body: String,            // msgtype + body
    val timestamp: LocalDateTime,// origin_server_ts
    val serverId: String?,       // parent space or null
    val channelId: String?       // room_id (where the message was sent)
) {
    fun toMatrixEvent(): Map<String, Any> = mapOf(
        "type" to "m.room.message",
        "room_id" to channelId,
        "content" to mapOf(
            "msgtype" to "m.text",
            "body" to body,
            "format" to "org.matrix.custom.html"
        )
    )
}

// ─── OrbitUser ↔ Matrix Profile ───────────────────────────
data class OrbitUser(
    val id: String,              // @user:server (Matrix ID)
    val username: String,        // localpart
    val displayName: String,     // displayname from /profile
    val avatarColor: Long,       // derived or random hash of MXID
    val status: UserStatus,      // mapped from m.presence
    val isBot: Boolean           // check for bot tag in user profile
)

enum class UserStatus {
    ONLINE,         // "online" (m.presence)
    IDLE,           // "idle"
    DO_NOT_DISTURB, // "dnd"
    OFFLINE         // "offline" / "unavailable"
}
```

---

## Backend Interface

### OrbitRepository — The Abstraction Layer

This is the single interface that the UI layer depends on. Implementations can be Mock or Matrix-backed.

```kotlin
/**
 * Repository interface for all data operations in Orbit.
 *
 * The UI layer calls these methods; implementations translate to/from
 * whatever backend (Matrix, custom API, local DB) is configured.
 */
interface OrbitRepository {

    // ─── Authentication ──────────────────────────────────────
    suspend fun login(
        identifier: String,       // username / email / mxid
        password: String,
        deviceName: String = "Orbit"
    ): AuthResult

    suspend fun register(
        username: String,
        password: String,
    ): RegisterResult

    suspend fun getCurrentUser(): OrbitUser?

    // ─── Servers / Spaces ────────────────────────────────────
    suspend fun getSpaces(): List<OrbitServer>

    suspend fun createSpace(name: String): Result<String>

    suspend fun getSpaceHierarchy(spaceId: String): List<OrbitServer>

    // ─── Channels / Rooms ────────────────────────────────────
    suspend fun getChannelsForSpace(spaceId: String): List<OrbitChannel>

    suspend fun createChannel(
        spaceId: String,
        name: String,
        type: ChannelType,
        topic: String = ""
    ): Result<String> // returns channelId (room_id)

    suspend fun getChannelById(channelId: String): OrbitChannel?

    // ─── Messages ────────────────────────────────────────────
    suspend fun getMessagesForChannel(
        channelId: String,
        limit: Int = 50,
        fromToken: String? = null
    ): List<OrbitMessage>

    suspend fun sendMessage(
        channelId: String,
        body: String
    ): Result<String> // returns eventId

    suspend fun editMessage(
        eventId: String,
        newBody: String
    ): Result<Unit>

    suspend fun deleteMessage(
        eventId: String
    ): Result<Unit>

    // ─── User Profile ────────────────────────────────────────
    suspend fun updateDisplayName(name: String): Result<Unit>
    suspend fun uploadAvatar(imageBytes: ByteArray): Result<String> // mxc:// URI
    suspend fun getUserProfile(userId: String): OrbitUser?

    // ─── Presence / Status ───────────────────────────────────
    suspend fun setPresence(status: UserStatus)
    suspend fun observePresence(userId: String): Flow<UserStatus>

    // ─── Real-time Events ────────────────────────────────────
    fun observeMessages(channelId: String): Flow<List<OrbitMessage>>
    fun observeChannelList(spaceId: String): Flow<List<OrbitChannel>>
}

data class AuthResult(
    val accessToken: String,
    val deviceId: String,
    val homeserverUrl: String,
    val userId: String
)

data class RegisterResult(
    val authFlow: AuthFlow,
    val nextEndpoint: String
)

sealed class AuthFlow {
    data class Password(val user: String, val password: String) : AuthFlow()
}
```

### Matrix Implementation Sketch

```kotlin
/**
 * Matrix-backed implementation of OrbitRepository.
 *
 * Uses the official Kotlin/Java Matrix SDK or direct HTTP calls via
 * kotlinx.io / OkHttp depending on target platform compatibility.
 */
class MatrixOrbitRepository(
    private val homeserverUrl: String,
    private val accessToken: String? = null
) : OrbitRepository {

    // Internal Matrix client wrapper (pseudo-code — actual SDK varies by platform)
    private val matrixClient: MatrixClient? = buildMatrixClient(homeserverUrl, accessToken)

    override suspend fun getSpaces(): List<OrbitServer> {
        val spaces = matrixClient?.getSpacesList() ?: return emptyList()
        return spaces.map { space ->
            OrbitServer(
                id = space.roomId,
                name = space.name ?: "Unnamed Space",
                iconColor = hashToColor(space.roomId),
                description = space.topic ?: "",
                isDM = false
            )
        }
    }

    override suspend fun getChannelsForSpace(spaceId: String): List<OrbitChannel> {
        // Get all child rooms in the space hierarchy
        val children = matrixClient?.getSpaceHierarchy(spaceId)?.flatMap { it.rooms }
            ?: emptyList()

        return children.mapNotNull { room ->
            OrbitChannel(
                id = room.roomId,
                name = extractChannelName(room.roomId), // e.g., "!general:server" → "general"
                type = if (room.hasVoiceBridge) ChannelType.VOICE else ChannelType.TEXT,
                categoryId = spaceId,
                topic = room.topic ?: ""
            )
        }
    }

    override suspend fun getMessagesForChannel(
        channelId: String,
        limit: Int,
        fromToken: String?
    ): List<OrbitMessage> {
        val timeline = matrixClient?.getRoomTimeline(channelId, limit, fromToken)
            ?: return emptyList()

        return timeline.mapNotNull { event ->
            if (event.type != "m.room.message") return@mapNotNull null

            val senderProfile = matrixClient?.getProfile(event.sender)
                ?: return@mapNotNull null

            OrbitMessage(
                id = event.eventId,
                userId = event.sender,
                username = senderProfile.displayName ?: event.sender,
                body = extractMessageBody(event.content),
                timestamp = Instant.ofEpochMilli(event.originServerTs)
                    .atZone(ZoneId.systemDefault()).toLocalDateTime(),
                channelId = channelId
            )
        }
    }

    override suspend fun sendMessage(channelId: String, body: String): Result<String> {
        return try {
            val eventId = matrixClient?.sendTextMessage(channelId, body)
                ?: throw IllegalStateException("Not connected")
            Result.success(eventId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ... remaining interface methods
}
```

---

## Implementation Roadmap

### Phase 1 — Foundation (Weeks 1–2)

- [ ] Set up Matrix homeserver (Conduit or Element Server) for development
- [ ] Add Matrix SDK dependency to `build.gradle.kts`
- [ ] Implement `MatrixOrbitRepository.login()` and `register()`
- [ ] Verify authentication flow end-to-end
- [ ] Store credentials securely (`rememberSaveable` + encrypted storage)

### Phase 2 — Server/Space Loading (Weeks 3–4)

- [ ] Implement `getSpaces()` using `/spaces` or `/rooms/join`
- [ ] Map Matrix Spaces to `OrbitServer` data class
- [ ] Display server list in `ServerRail` with real data
- [ ] Handle loading states and errors gracefully

### Phase 3 — Channel/Room Loading (Weeks 5–6)

- [ ] Implement `getChannelsForSpace()` using space hierarchy API
- [ ] Map child rooms to `OrbitChannel` data class
- [ ] Display channels in `ChannelDrawer` with categories
- [ ] Handle room creation via UI

### Phase 4 — Messaging (Weeks 7–9)

- [ ] Implement `getMessagesForChannel()` using `/rooms/{roomId}/messages`
- [ ] Paginated timeline loading (back-pagination)
- [ ] Implement `sendMessage()` with `m.room.message` event
- [ ] Real-time message delivery via WebSocket (CSAPI)
- [ ] Handle message editing and deletion

### Phase 5 — Presence & Status (Weeks 10–11)

- [ ] Map Matrix presence states to `UserStatus` enum
- [ ] Display online/idle/DND indicators on avatars
- [ ] Allow user to set their own status
- [ ] Observe other users' presence changes via `/sync`

### Phase 6 — Voice/Video (Weeks 12–14)

- [ ] Integrate Jitsi Meet or LiveKit for voice channels
- [ ] Map `ChannelType.VOICE` to bridged rooms with active calls
- [ ] Add screen sharing support (for streaming settings)

### Phase 7 — Polish & Production (Weeks 15+)

- [ ] E2EE handling (`m.room.encrypted` events)
- [ ] Push notifications (FCM/APNs via Matrix push gateway)
- [ ] File uploads (`/upload` API → `m.image` / `m.file`)
- [ ] Avatar management
- [ ] Search across messages and rooms

---

## Security & Privacy

### Encryption Strategy

```
┌─────────────────────────────────────────────────────┐
│              ENCRYPTION LAYERS                       │
│                                                      │
│  Layer 1: TLS 1.3 (all client-server traffic)       │
│  Layer 2: m.room.encryption (E2EE per room)         │
│  Layer 3: Device key signing (Ed25519 + Curve25519) │
│                                                      │
│  Default: All rooms encrypted by default             │
│  Key exchange: Megolm for message encryption         │
│  Session sharing: Olm/Matrix Crypto-Next (MLCN)      │
└─────────────────────────────────────────────────────┘
```

### Key Storage

| Platform | Storage Method |
|----------|----------------|
| Android | `EncryptedSharedPreferences` + `AndroidKeyStore` |
| iOS | Keychain Services |
| Desktop (JVM) | JCE Keystore with OS credential provider |
| Web | IndexedDB + Web Crypto API |

### Token Management

```kotlin
// Store access token securely
object AuthStorage {
    private const val PREFS_NAME = "orbit_auth"

    fun saveAccessToken(context: Context, token: String) {
        val encrypted = AES256.encrypt(token) // key from KeyStore
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString("access_token", encrypted)
            .apply()
    }

    fun getAccessToken(context: Context): String? {
        val encrypted = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString("access_token") ?: return null
        return AES256.decrypt(encrypted)
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply()
    }
}
```

---

## Testing Strategy

### Unit Tests (Repository Layer)

```kotlin
class MatrixOrbitRepositoryTest {

    @Test
    fun `getSpaces returns mapped OrbitServer list`() = runTest {
        val mockClient = MockMatrixClient()
        val repo = MatrixOrbitRepository("https://matrix.org", "fake-token")

        val spaces = repo.getSpaces()

        assertThat(spaces).isNotEmpty
        assertThat(spaces[0].id).startsWith("!") // room_id format
    }

    @Test
    fun `sendMessage returns eventId on success`() = runTest {
        val mockClient = MockMatrixClient()
        val repo = MatrixOrbitRepository("https://matrix.org", "fake-token")

        val result = repo.sendMessage("!test:server", "Hello!")

        assertTrue(result.isSuccess)
    }
}
```

### UI Tests (Compose Testing)

```kotlin
@OptIn(ExperimentalTestApi::class)
@Composable
fun ChatScreenTest() {
    val testRepo = TestOrbitRepository() // implements OrbitRepository with mock data
    val state = rememberChatState(testRepo)

    composeTestRule.setContent {
        ChatScreen(channel = state.channel, messages = state.messages)
    }

    composeTestRule.awaitIdle()

    // Verify messages are displayed
    composeTestRule.onNodeWithText("Hello!").assertExists()

    // Type and send a message
    val textField = composeTestRule.onNode(hasTestTag("message_input"))
    textField.performTextInput("New message")
    composeTestRule.onNode(hasTestTag("send_button")).performClick()

    // Verify message appears in list
    composeTestRule.onNodeWithText("New message").assertExists()
}
```

### Integration Tests (Mock Server)

Use [Conduit](https://github.com/ConduitMC/conduit) or a custom mock server that returns deterministic Matrix API responses for all endpoints.

---

## Quick Start — Connecting to Your First Matrix Homeserver

1. **Add dependencies** to your `build.gradle.kts`:
   ```kotlin
   // For Android/Desktop (Kotlin Multiplatform compatible)
   implementation("com.github.ajalt.moshi:moshi-kotlin:1.15.0")
   implementation("io.ktor:ktor-client-core:2.3.0")
   implementation("io.ktor:ktor-client-okhttp:2.3.0") // Android
   implementation("io.ktor:ktor-client-cio:2.3.0")     // Desktop

   // Matrix SDK (when available for MPP)
   implementation("io.element.android:sdk-api:1.x.x")
   ```

2. **Create the repository:**
   ```kotlin
   val repo = MatrixOrbitRepository(
       homeserverUrl = "https://matrix.org",
       accessToken = "your_access_token"
   )
   ```

3. **Inject into OrbitApp:**
   ```kotlin
   @Composable
   fun MyApp(repository: OrbitRepository = MatrixOrbitRepository(...)) {
       val state = rememberAppState(repository)
       OrbitApp(state = state)
   }
   ```

---

## Appendix A — Matrix API Endpoints Used

| Endpoint | Purpose | Repository Method |
|----------|---------|-------------------|
| `POST /_matrix/client/v3/login` | Authenticate | `login()` |
| `POST /_matrix/client/v3/register` | Register user | `register()` |
| `GET /_matrix/client/v3/spaces` | List spaces | `getSpaces()` |
| `POST /_matrix/client/v3/createRoom` | Create room/channel | `createChannel()` |
| `GET /_matrix/client/v3/rooms/{roomId}/state` | Get room state | `getChannelById()` |
| `GET /_matrix/client/v3/rooms/{roomId}/messages` | Paginated timeline | `getMessagesForChannel()` |
| `POST /_matrix/client/v3/rooms/{roomId}/send/m.room.message` | Send message | `sendMessage()` |
| `GET /_matrix/client/v3/profile/{userId}` | Get user profile | `getUserProfile()` |
| `PUT /_matrix/client/v3/profile/{userId}/displayname` | Set display name | `updateDisplayName()` |
| `WS /_matrix/client/v3/events` | Live event stream | `observeMessages()` |

## Appendix B — Event Types Referenced

| Event Type | Purpose |
|-----------|---------|
| `m.room.message` | User messages (text, image, file) |
| `m.room.encrypted` | E2EE encrypted messages (megolm) |
| `m.room.create` | Room creation metadata |
| `m.room.topic` | Channel/topic description |
| `m.space.parent` | Space → room hierarchy relation |
| `m.room.name` | Room name override |
| `m.room.avatar` | Room/avatar image |
| `m.presence` | User presence state (online/idle/dnd/offline) |

## Appendix C — Future Considerations

- **Custom Protocol Support:** The `OrbitRepository` interface allows swapping Matrix for a custom backend. Implement `CustomApiRepository` with your own HTTP client.
- **Multi-Account:** Each account gets its own `MatrixOrbitRepository` instance; switch between them in `OrbitApp`.
- **Bridges:** Matrix appservice-Discord/Slack bridges let Orbit users connect to other platforms transparently — channels appear as regular rooms.