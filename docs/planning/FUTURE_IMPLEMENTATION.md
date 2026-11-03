# 🚀 Orbit Future Implementation Roadmap

> **Document Status:** Planning & Architecture Roadmap  
> **Preceding Milestone:** Shipped Discord-Style Separate Text & Voice Channels ([TEXT_AND_VOICE_CHANNELS.md](file:///g:/projects/orbit/docs/planning/TEXT_AND_VOICE_CHANNELS.md))  
> **Target:** Step-by-step implementation of Public/Private Servers, Admin & Mod Roles, Encrypted/Unencrypted Channel Architecture, and Secure E2EE Key Recovery.

---

## 1. Overview: The Next Major Capabilities

Following the successful separation of text and dedicated voice channels with independent chat timelines, the next development cycles will implement four major functional systems:

1. **Server Discovery & Join Architecture** (Public Spaces vs. Private Invite-Only Spaces).
2. **Dual Encryption Architecture** (Encrypted vs. Unencrypted Channels & Access Control).
3. **Server Administration & Moderation Enforcement** (Server Mute, Kick/Disconnect from Voice, Timeouts, Server Nicknames, Role Badges).
4. **End-to-End Encryption Key Recovery & Cross-Signing** (Eliminating the *"This message couldn't be decrypted"* trap upon reinstalling Windows).

---

## Phase 1: Server Discovery & Public / Private Join Mechanics

### A. Public Servers (Discord-Style Discovery)
* **Under the Hood:**
  * Public Matrix Space (`m.space`) with `m.room.join_rules: public`.
  * Public room alias assigned: `#community-name:homeserver.org`.
  * Space visibility published to directory via `PUT /_matrix/client/v3/directory/list/room/{roomId}` (`"visibility": "public"`).
* **UI Features to Build:**
  * **Explore Public Servers Screen:** Queries `GET /_matrix/client/v3/publicRooms` filtered by `room_type: m.space` to show searchable server cards with member counts, descriptions, and server avatars.
  * **Direct Join Links:** Supporting `matrix.to/#/#alias:server` and deep link `orbit://join/#alias:server` for 1-click joins.

### B. Private Servers (Invite-Only)
* **Under the Hood:**
  * `m.room.join_rules: invite` (or `restricted` to Space members).
  * Directory visibility set to `private` (100% unlisted).
  * Uninvited users querying or trying to enter receive `403 M_FORBIDDEN`.
* **Invite Links:**
  * Temporary/reusable invite tokens linking to `m.room.member` invite state events.

---

## Phase 2: Dual Encryption Architecture (E2EE vs. Open Channels)

### A. The Core Principle
* **Access Control** (`m.room.join_rules` & Power Levels) controls **which users** can enter a channel (e.g., `#staff-only`).
* **Encryption** (Megolm / Olm) controls **whether the homeserver database admin / host** can read the messages.

### B. Channel Classification
1. **Public / Community Channels (`#announcements`, `#general`)**:
   * **Unencrypted**: Instant historical scrollback for new joiners, high performance, compatible with bot integrations (music bots, webhooks, bridges).
2. **Confidential Channels (`#finances`, `#leadership`, DMs)**:
   * **Encrypted (Megolm E2EE)**: Messages scrambled client-side before leaving the device. The server only sees ciphertext.
3. **Voice Channels (`🔊 General`)**:
   * Carried via MatrixRTC (MSC3401 / MSC3815).
   * Voice frames encrypted via per-device keys distributed through `m.call.member` state events ([CallMediaKeyExchange.kt](file:///g:/projects/orbit/app/src/commonMain/kotlin/livekit/CallMediaKeyExchange.kt)).

---

## Phase 3: Server Administration & Moderation Enforcement

Orbit already defines the permission model in [OrbitRole.kt](file:///g:/projects/orbit/app/src/commonMain/kotlin/ui/model/OrbitRole.kt) and renders the management UI in [ServerRolesDialog.kt](file:///g:/projects/orbit/app/src/commonMain/kotlin/ui/screen/server/ServerRolesDialog.kt). Phase 3 connects these controls to active enforcement.

### A. Voice Moderation (MatrixRTC + LiveKit SFU)
1. **Server Mute (`OrbitPermission.MUTE_MEMBERS`)**:
   * **Action:** Moderator (Power Level $\ge 50$) clicks "Server Mute" on a participant in the voice roster.
   * **Wire Execution:** The client instructs the LiveKit SFU / MatrixRTC focus (via RoomService API or control message) to mute the participant's publication track (`mutePublishedTrack`). The SFU drops audio packets from that user for all subscribers.
2. **Server Disconnect / Kick (`OrbitPermission.DISCONNECT_MEMBERS`)**:
   * **Action:** Moderator kicks disruptive user from the voice call.
   * **Wire Execution:** Issues `removeParticipant` on the LiveKit SFU and writes a state event transitioning their `m.call.member` state to empty/left.
3. **Move Members (`OrbitPermission.MOVE_MEMBERS`)**:
   * Disconnects member from current voice room and pushes an invite/switch event to the target voice room.

### B. Text Moderation & Power Levels (`m.room.power_levels`)
1. **Timeout / Text Mute (`OrbitPermission.TIMEOUT_MEMBERS`)**:
   * Temporary override setting user's power level in `m.room.power_levels` below `events_default` (or using MSC3881 room timeouts). The user can read messages but cannot send.
2. **Kick & Ban (`OrbitPermission.KICK_MEMBERS`, `OrbitPermission.BAN_MEMBERS`)**:
   * Sends `m.room.member` transition to `leave` (kick) or `ban` (permanent lockout).
3. **Delete Messages (`OrbitPermission.MANAGE_MESSAGES`)**:
   * Sends `m.room.redaction` event referencing target message `event_id`.
4. **Per-Server Nicknames**:
   * Uses Matrix room-specific `displayname` inside the user's `m.room.member` state event in the Space room, allowing custom nicknames per server without changing global `@user:server.org` handles.

---

## Phase 4: Secure E2EE Key Backup & Cross-Signing Recovery

### A. The Bug / Problem
* When Windows is wiped, reinstalled, or `~/.orbit/trixnity.db` is deleted, the local Olm/Megolm key storage is destroyed.
* Without a key backup, historical messages display: `🔒 This message couldn't be decrypted`.

### B. The Solution: Matrix Secure Backup (MSC1219 / MSC3061)
1. **Recovery Passphrase / Security Key**:
   * During first-time setup or in Settings, the user sets a **Recovery Passphrase** (or generates a 12-word security recovery key).
   * Local Megolm inbound session keys are encrypted with an AES-GCM key derived from this passphrase and stored in the homeserver's secure backup store (`PUT /_matrix/client/v3/room_keys/keys`).
2. **Restoration on Fresh Install**:
   * User reinstalls Windows/Orbit -> logs in.
   * Orbit detects missing keys and prompts: *"Enter Recovery Passphrase to restore messages"* or *"Verify with another device"*.
   * Entering the passphrase downloads and decrypts all past room keys directly into `~/.orbit/trixnity.db`.
   * All historical encrypted messages immediately become readable.
3. **Device Cross-Signing (Phone Verification)**:
   * If logged into Orbit or Element on a phone, clicking "Verify with device" initiates an interactive emoji/SAS verification (`m.key.verification.request`).
   * The phone sends Megolm session keys over a secure peer-to-peer encrypted channel (`m.secret.send`), restoring keys without typing the passphrase.
4. **Daily Launch Behavior**:
   * The passphrase is only entered **once** per fresh install. Normal launches read `~/.orbit/trixnity.db` directly and never prompt the user.

---

## 5. Step-by-Step Implementation Checklist

- [ ] **Step 1: Explore Public Servers & Join Alias**
  - Implement public room directory query in `MatrixSpacesRepository.kt`.
  - Create `ExploreServersDialog.kt` / browse tab in ServerRail.
- [ ] **Step 2: Server Mute & Disconnect in Voice**
  - Wire right-click / context menu on voice roster members in `VoiceMemberStack.kt`.
  - Hook `CallManager` to emit mute/disconnect commands to the LiveKit SFU session.
- [ ] **Step 3: Text Moderation Actions (Timeout, Kick, Ban, Redact)**
  - Add Member context menu in `MemberListPanel.kt` / chat messages.
  - Implement power level updates and redactions in `MatrixMessagesRepository.kt`.
- [ ] **Step 4: Per-Server Nicknames**
  - Add "Change Nickname" option in server header dropdown.
  - Write updated `displayname` to user's `m.room.member` state in the Space.
- [ ] **Step 5: E2EE Key Backup & Passphrase Verification Flow**
  - Integrate Trixnity's `KeyBackupService` / `CrossSigningService`.
  - Build UI dialog: "Set Up Secure Backup" and "Restore Encryption Keys".
