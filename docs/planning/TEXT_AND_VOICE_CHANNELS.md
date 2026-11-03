# 🎙️ Text and Voice Channels Architecture (Discord-Style on Matrix)

> **Document Status:** Active / Verified in Code  
> **Applies to:** Orbit Desktop & Mobile Matrix Architecture  
> **Key Specs:** MSC1772 (Spaces), MSC3401 (Native Group VoIP), MSC3815 (LiveKit MatrixRTC)

---

## 1. Overview: The Problem & The Solution

### The Historical Problem (Element Hybrid Trap)
In vanilla Matrix and Element:
- Every room is primarily a text timeline with an optional call button.
- When clients attempt to emulate Discord without separate rooms, they either:
  1. Dump all channels into a flat list with an inline "Voice" toggle row under every single text channel.
  2. Put a single room under "Voice Channels", making its text chat unreachable.

### The Orbit Solution (Discord 1:1 Parity via Orbit Tags)
Orbit decouples text and voice into **distinct first-class channels** organized under clear categories (`TEXT CHANNELS` and `VOICE CHANNELS`), using explicit Matrix state tags (`space.orbit.server` and `space.orbit.channel`):
- **Orbit-Tagged Servers (`space.orbit.server: { orbit: true }`)**:
  - `TEXT` channels: Strictly pure text chat. No call icons, no inline voice sub-rows.
  - `VOICE` channels: Clicking directly joins the voice call lounge. The room's `m.room.power_levels` sets `org.matrix.msc3401.call.member: 0` so non-admin members can broadcast voice state without `403 Forbidden` errors.
- **External / Element Servers (Untagged)**:
  - Preserves standard Matrix hybrid compatibility (chat + optional live call banner).

```
[ SERVER (Matrix Space with space.orbit.server) ]
 ├── [ CATEGORY: TEXT CHANNELS ]
 │    └── # general (space.orbit.channel: text) ──> Pure text chat timeline (calls disabled)
 │
 └── [ CATEGORY: VOICE CHANNELS ]
      └── 🔊 General (space.orbit.channel: voice) ──> MatrixRTC LiveKit Call
           ├── 👤 Connected Member 1       AND
           ├── 👤 Connected Member 2   Dedicated In-Call Text Chat Timeline
```

---

## 2. Server Creation Flow (`JoinServerScreen.kt`)

When a user creates a new server in Orbit:

1. **Space Creation & Orbit Tag**:
   - `createSpace(name)` creates a top-level Matrix Space room with `CreateEventContent.RoomType.Space`.
   - Orbit tags the Space with the `space.orbit.server` state event: `{ "orbit": true, "version": 1 }`.
2. **Default Channel Provisioning**:
   - Orbit provisions both default channels:
     ```kotlin
     val textResult = repository.createChannel(spaceId, "general", ChannelType.TEXT)
     val voiceResult = repository.createChannel(spaceId, "General", ChannelType.VOICE)
     ```
3. **Room Linking & Power Levels**:
   - Both rooms are linked to the parent Space via `m.space.child` state events.
   - Each channel is tagged with `space.orbit.channel` (`{ "type": "text" }` or `{ "type": "voice" }`).
   - The voice room is explicitly configured with `m.room.power_levels` granting `events["org.matrix.msc3401.call.member"] = 0` and `events["m.call.member"] = 0`, ensuring regular accounts (Power Level 0) can publish call membership without `403 Forbidden` errors.

---

## 3. Dedicated Channels & Independent Timelines

### Why Text Does Not Bleed Between Channels
Under the hood:
* `# general` is **Room ID 1** (`!roomId1:matrix.org`).
* `🔊 General` is **Room ID 2** (`!roomId2:matrix.org`).

Because they are distinct Matrix rooms:
* Messages sent in `# general` stay strictly in `# general`.
* Messages sent in `🔊 General`'s voice chat stay strictly in `🔊 General`.
* When a user is in `🔊 General` voice call, Orbit renders the voice call surface (video/screen share grid) in the main column, and renders `🔊 General`'s timeline in the right-hand side panel.

---

## 4. Sidebar Grouping & Interaction (`ChannelGrouping.kt` & `ChannelList.kt`)

### Grouping Rules (`ChannelGrouping.kt`)
1. **Uncategorized Spaces**:
   - Channels are automatically grouped into:
     - **`TEXT CHANNELS`**: Contains all `ChannelType.TEXT` rooms.
     - **`VOICE CHANNELS`**: Contains all `ChannelType.VOICE` rooms.
2. **Categorized Spaces**:
   - If server categories exist (`/_matrix/client/v1/categories`), channels group by server category. Orphan channels fall back to `TEXT CHANNELS` and `VOICE CHANNELS`.
3. **Exact Cardinality**: Every room appears exactly once in the drawer.

### Channel Row Interactions (`ChannelList.kt`)
1. **Text Channel Rows (`#`)**:
   - Displays `OrbitIcons.TextChannel` (`#`) and channel title.
   - **Clicking**: Switches current view to that room's message timeline.
   - **Strictly Text in Orbit Servers**: Text channel rows in Orbit-managed servers never render an inline "Voice" sub-button. In external Element rooms, an inline voice button is shown only if an active call is running.
2. **Voice Channel Rows (`🔊`)**:
   - Displays `OrbitIcons.VoiceChannel` (`🔊`) and channel title.
   - **Clicking**: Immediately joins the MatrixRTC / LiveKit call and expands the voice call view with its dedicated chat.
   - **Live Roster**: Active participants (`VoiceMemberStack`) stack directly underneath the voice channel row with real-time speaking rings, mute, and deafen indicators.

---

## 5. MatrixRTC & LiveKit Wire Architecture

* **VoIP Protocol**: MatrixRTC (MSC3401 / MSC3815).
* **Focus Resolution**: The voice room advertises its LiveKit SFU focus via `m.call` or `.well-known` discovery.
* **Call Membership**:
  - LiveKit participant state is authoritative for who is connected in the active room.
  - Matrix `m.call.member` state events signal call presence to users browsing other channels.
  - Non-admin members have power level 0 permission for `m.call.member` and `org.matrix.msc3401.call.member`.
* **Audio Routing**: Handled by Orbit's C++ native APM bridge with hardware acoustic echo cancellation (AEC), noise suppression (NS), and 10ms low-latency mixing.

---

## 6. Summary Matrix

| Feature | External / Element Spaces | Orbit Discord-Style Servers |
|---|---|---|
| **Server Identity** | Standard Matrix Space (Untagged) | Tagged with `space.orbit.server` |
| **Channel Identity** | Hybrid Matrix Room | Tagged with `space.orbit.channel` (text / voice) |
| **Text Channels** | Hybrid: chat + live call sub-row if call exists | 100% Strict Chat: calls disabled |
| **Voice Channels** | Call started inside any room | Dedicated voice lounge: click immediately joins call |
| **Voice Permissions** | Default (requires PL 50 unless configured) | Automatically PL 0 for `m.call.member` |
| **Drawer Grouping** | Flat or category-based | Dedicated `TEXT CHANNELS` & `VOICE CHANNELS` headers |

---

## 7. Server Discovery, Aliases & Real-Time Sync Lifecycle

### Real-Time Space Membership (`flattenValues`)
* In Trixnity, `room.getAll()` emits `Map<RoomId, Flow<Room?>>`. Observing only map emissions fails to capture membership updates when users leave or are kicked from rooms (the room keys remain unchanged in the store).
* Using `.flattenValues()` unpacks the inner `Room?` flows and continuously emits whenever a room's `membership` transitions to `LEAVE` or `BAN`.
* When a server is deleted or the user is removed, `roomListFlow` automatically re-emits `getSpaces()`, instantly pruning the server from the left server rail without requiring an app relaunch.
* If a space is no longer in the user's active joined snapshot (`getAllRoomsSnapshot()`), `getChannelsForSpace(spaceId)` immediately returns `emptyList()` rather than loading stale hierarchy or orphaned channels.

### Discover Communities & Public Room Aliases
* **Alias Collision Avoidance**: When creating a public server whose base handle is occupied (e.g. `#jjk:matrix.org`), Orbit assigns a collision-free canonical alias (e.g. `#jjk-5041:matrix.org`).
* **Strict Public Room Validation**: `getPublicServers` candidate resolution verifies `isSpacePublic(resolvedRoomId)` (`join_rule == "public"`). If a candidate alias resolves to an invite-only room (such as a stranger's existing private room), it is discarded from discovery rather than throwing a `403 Forbidden` error on join.
* **Direct Handle Sharing**: The Invite Dialog inspects `server.canonicalAlias` and copies the authoritative Matrix handle so members can join directly via the Discover or Join tabs.
