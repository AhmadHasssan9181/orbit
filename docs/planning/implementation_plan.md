# Fix All Matrix Backend Integration — Implementation Plan

The app compiles and authenticates via SSO, but **all post-login functionality is broken** because the repository uses non-existent Matrix endpoints and the UI still references `MockData` in several places.

## Root Cause Summary

| Problem | Cause |
|---------|-------|
| No rooms visible after login | `getSpaces()` calls `/spaces` (not a real endpoint), fallback `/joined_rooms` works but returns IDs with no names |
| No channels in any server | `getChannelsForSpace()` calls `/spaces/{id}` (not real) and returns empty |
| Can't message | No channel is ever selected (channel list is empty) |
| Member list is mock data | `MemberListPanel()` in ChatScreen.kt hardcodes `MockData.MockUsers` |
| Can't create/join rooms | UI buttons exist but wired to `{}` no-ops |
| Voice channels non-functional | `VoiceChannelPlaceholder` is a static placeholder with no backend |
| User profile shows "Me" | `getCurrentUser()` was returning null, now calls profile API but OrbitApp still uses fallback |
| DM list still uses MockData | `DmListContent()` in ChannelDrawer.kt iterates `MockData.MockUsers` |
| Sync loop doesn't update UI | `startSync()` parses events but the UI never re-fetches rooms/channels |

---

## Proposed Changes

### Component 1: Room/Server Loading (Critical)

> **This is the #1 blocker.** Matrix doesn't have a `/spaces` endpoint. Rooms are loaded via `/joined_rooms` + per-room state calls to get names/topics.

#### [MODIFY] [MatrixOrbitRepository.kt](file:///D:/Onichat/app/src/commonMain/kotlin/repository/MatrixOrbitRepository.kt)

**`getSpaces()` — Rewrite completely:**
1. Call `GET /_matrix/client/v3/joined_rooms` → returns `{ "joined_rooms": ["!abc:matrix.org", ...] }`
2. For each room ID, call `GET /_matrix/client/v3/rooms/{roomId}/state` → returns array of state events
3. Extract room name from `m.room.name` event, topic from `m.room.topic`, type from `m.room.create` (check if `content.type == "m.space"`)
4. If `type == "m.space"` → treat as a "Server" (OrbitServer)
5. If DM (check `m.direct` account data or small membership count) → group into a DM pseudo-server
6. Otherwise → group into a "Rooms" fallback server
7. **Optimization**: Use `/sync` initial response instead of N+1 calls — the initial sync already contains all room state

**`getChannelsForSpace(spaceId)` — Fix for spaces vs flat rooms:**
- If spaceId is a real Matrix space: call `GET /_matrix/client/v3/hierarchy/{spaceId}` (the actual endpoint, NOT `/spaces/{id}`)
- If spaceId is the synthetic DM server or "Rooms" server: return the list we built in `getSpaces()` that were grouped under it

**`getSpaceHierarchy()` — Fix endpoint:**
- Change `/spaces/{id}` → `/_matrix/client/v1/hierarchy/{spaceId}`

**`createSpace()` — Fix room creation body:**
- The `type` field goes inside `creation_content`, not top-level:
```json
{
  "name": "My Space",
  "creation_content": { "type": "m.space" },
  "preset": "private_chat"
}
```

**`getMessagesForChannel()` — Verify endpoint:**
- Currently calls `/rooms/{id}/messages?dir=b&limit=N` — this is correct but must include the auth header and URL-encode the room ID

---

### Component 2: Cache Joined Rooms Locally

> Fetching state for every room on every navigation is too slow. Cache the room list from the initial sync.

#### [MODIFY] [MatrixOrbitRepository.kt](file:///D:/Onichat/app/src/commonMain/kotlin/repository/MatrixOrbitRepository.kt)

Add internal state:
```kotlin
// Cache of room metadata extracted from sync
private val _roomCache = mutableMapOf<String, RoomMetadata>()

data class RoomMetadata(
    val name: String,
    val topic: String,
    val isDM: Boolean,
    val isSpace: Boolean,
    val spaceParent: String?, // parent space room ID
    val memberCount: Int
)
```

- Populate from initial `/sync` response (which contains all joined room state)
- Update incrementally from subsequent sync deltas
- `getSpaces()` reads from cache instead of making N+1 API calls

---

### Component 3: OrbitApp Data Flow

> The OrbitApp correctly calls `repo.getSpaces()` / `repo.getChannelsForSpace()` now, but these return wrong data. Once Component 1 is fixed, this will work. However, there are still improvements needed.

#### [MODIFY] [OrbitApp.kt](file:///D:/Onichat/app/src/commonMain/kotlin/ui/screen/OrbitApp.kt)

- Fetch `currentUser` from `repo.getCurrentUser()` on login instead of hardcoding "Me"
- Add a reactive listener for room list updates (when sync adds/removes rooms)
- Add "Join Room" dialog when the "+" button is clicked in ServerRail
- Add "Create Room" functionality

---

### Component 4: Remove All MockData References from Production UI

> Several UI files still import and use `MockData`. These must be replaced with real data or removed.

#### [MODIFY] [ChatScreen.kt](file:///D:/Onichat/app/src/commonMain/kotlin/ui/screen/ChatScreen.kt)

- **Line 32**: `import ui.data.MockData` — remove
- **Line 255**: `message.userId == MockData.CurrentUser.id` — replace with checking against actual logged-in user ID (pass as parameter or use CompositionLocal)
- **Lines 615-619**: `MemberListPanel()` uses `MockData.MockUsers` + `MockData.CurrentUser` — needs to receive actual room members from repository
- Add `members: List<OrbitUser>` parameter to `ChatScreen` and pass through from `ChatScreenWithRepo`

#### [MODIFY] [ChannelDrawer.kt](file:///D:/Onichat/app/src/commonMain/kotlin/ui/screen/ChannelDrawer.kt)

- **Line 31**: `import ui.data.MockData` — remove  
- **Line 214**: `DmListContent` uses `MockData.MockUsers` to build DM list — should use actual channels passed in, not mock users
- The DM channel names from Matrix already contain the other user's display name

---

### Component 5: Room Members (Real Data)

> The member list panel currently shows hardcoded mock users. It needs to fetch real room members.

#### [MODIFY] [MatrixOrbitRepository.kt](file:///D:/Onichat/app/src/commonMain/kotlin/repository/MatrixOrbitRepository.kt)

Add new method:
```kotlin
suspend fun getRoomMembers(roomId: String): List<OrbitUser>
```
- Call `GET /_matrix/client/v3/rooms/{roomId}/members?membership=join`
- Returns `{ "chunk": [{ "type": "m.room.member", "state_key": "@user:server", "content": { "displayname": "...", "membership": "join" } }] }`
- Map to `OrbitUser` objects

#### [MODIFY] [OrbitRepository.kt](file:///D:/Onichat/app/src/commonMain/kotlin/repository/OrbitRepository.kt)

Add to interface:
```kotlin
suspend fun getRoomMembers(roomId: String): List<OrbitUser>
```

#### [MODIFY] [OrbitApp.kt](file:///D:/Onichat/app/src/commonMain/kotlin/ui/screen/OrbitApp.kt)

- In `ChatScreenWithRepo`, fetch members via `repo.getRoomMembers(channel.id)` and pass to `ChatScreen`

#### [MODIFY] [ChatScreen.kt](file:///D:/Onichat/app/src/commonMain/kotlin/ui/screen/ChatScreen.kt)

- `ChatScreen` accepts `members: List<OrbitUser>` param
- `MemberListPanel` uses the passed-in members instead of `MockData`

---

### Component 6: Join/Create Room UI

> The "+" button in ServerRail and the discover button currently do nothing.

#### [MODIFY] [OrbitApp.kt](file:///D:/Onichat/app/src/commonMain/kotlin/ui/screen/OrbitApp.kt)

Add dialog states and handlers:
- **Join Room Dialog**: Text field for room alias/ID → calls `repo.joinRoom(roomIdOrAlias)`
- **Create Room Dialog**: Fields for name, topic, type → calls `repo.createChannel()`

#### [MODIFY] [MatrixOrbitRepository.kt](file:///D:/Onichat/app/src/commonMain/kotlin/repository/MatrixOrbitRepository.kt)

Add:
```kotlin
suspend fun joinRoom(roomIdOrAlias: String): Result<String>
```
- `POST /_matrix/client/v3/join/{roomIdOrAlias}` with empty body
- Returns the joined room ID

#### [MODIFY] [OrbitRepository.kt](file:///D:/Onichat/app/src/commonMain/kotlin/repository/OrbitRepository.kt)

Add `joinRoom()` to interface.

---

### Component 7: Message Sending

> `sendMessage()` implementation looks correct but depends on having a valid channel ID selected. Once rooms load properly, this should work. Verify:

#### [MODIFY] [MatrixOrbitRepository.kt](file:///D:/Onichat/app/src/commonMain/kotlin/repository/MatrixOrbitRepository.kt)

Verify `sendMessage()`:
- Uses `PUT /_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txnId}` — this is correct
- Ensure the room ID is **not** URL-encoded in the path (Ktor handles this)
- Ensure `txnId` is unique per message (current impl uses UUID — good)

---

### Component 8: Sync Loop & Live Updates

> `startSync()` runs but doesn't trigger UI recomposition because there's no reactive state bridge.

#### [MODIFY] [MatrixOrbitRepository.kt](file:///D:/Onichat/app/src/commonMain/kotlin/repository/MatrixOrbitRepository.kt)

- Expose a `StateFlow<List<OrbitServer>>` for the room list that gets updated on each sync
- When sync returns new rooms in `rooms.join`, update the room cache and emit
- When sync returns new messages in `rooms.join.{roomId}.timeline.events`, emit to the per-channel message flow

#### [MODIFY] [OrbitApp.kt](file:///D:/Onichat/app/src/commonMain/kotlin/ui/screen/OrbitApp.kt)

- Collect the rooms StateFlow to automatically refresh the server list
- Collect channel changes to refresh the channel list

---

### Component 9: Voice Channels

> Matrix doesn't have native voice channels. Voice/video calls use **Element Call** (Jitsi/LiveKit integration) via widgets.

#### [MODIFY] [ChatScreen.kt](file:///D:/Onichat/app/src/commonMain/kotlin/ui/screen/ChatScreen.kt)

In `VoiceChannelPlaceholder`:
- Show "Join Voice" button that opens the Element Call widget URL in the system browser
- URL format: `https://call.element.io/room/#/{roomId}`
- Or display a "Voice not yet supported — open in Element" message with a browser link

> [!IMPORTANT]
> Full native voice/video requires WebRTC integration which is a massive undertaking. For v1, opening Element Call in the browser is the pragmatic approach.

---

### Component 10: User Profile

#### [MODIFY] [OrbitApp.kt](file:///D:/Onichat/app/src/commonMain/kotlin/ui/screen/OrbitApp.kt)

- After login, fetch `repo.getCurrentUser()` and update the `currentUser` state
- Pass the real user to `ChannelDrawer` (for the profile bar at bottom)

---

## Priority Order

| Priority | Component | Effort | Impact |
|----------|-----------|--------|--------|
| **P0** | 1. Room/Server Loading | High | Everything depends on this |
| **P0** | 2. Cache Joined Rooms | Medium | Performance, prevents N+1 |
| **P1** | 3. OrbitApp Data Flow | Low | Wiring |
| **P1** | 4. Remove MockData | Medium | Correctness |
| **P1** | 7. Message Sending | Low | Core feature verify |
| **P1** | 8. Sync & Live Updates | Medium | Real-time |
| **P2** | 5. Room Members | Medium | Social feature |
| **P2** | 6. Join/Create Room | Medium | Growth feature |
| **P2** | 10. User Profile | Low | Polish |
| **P3** | 9. Voice Channels | Low | Browser fallback only |

---

## Verification Plan

### Automated Tests
```bash
.\gradlew compileKotlinJvm   # Must pass with zero errors
.\gradlew run                 # Manual verification
```

### Manual Verification
1. **Login via SSO** → browser opens, auth completes, app transitions to main screen
2. **Rooms visible** → all Element-joined rooms appear in the server rail with correct names
3. **Channels load** → clicking a server shows its rooms/channels in the drawer
4. **Messages display** → clicking a text channel shows real message history
5. **Send message** → type and send → message appears in chat and in Element on another device
6. **Join room** → click "+" → enter room alias → room appears in list
7. **Members panel** → shows real room members with display names
8. **Live updates** → send a message from Element → it appears in Orbit within seconds

## Open Questions

> [!IMPORTANT]
> **Space hierarchy vs flat rooms**: Most Matrix users don't use Spaces. Should the app:
> - (A) Show all joined rooms as a flat list under a single "Rooms" server (simpler, works for everyone)
> - (B) Group rooms by Space (more Discord-like, but many rooms won't be in any Space)
> - (C) Both — Spaces as servers + uncategorized "Rooms" catch-all
> 
> **Recommendation**: Option C — this matches how Element handles it.

> [!IMPORTANT]  
> **DM detection**: Matrix marks DMs via `m.direct` account data. Should we:
> - (A) Always show a "Direct Messages" pseudo-server at the top (Discord-style)
> - (B) Mix DMs with regular rooms
>
> **Recommendation**: Option A.
