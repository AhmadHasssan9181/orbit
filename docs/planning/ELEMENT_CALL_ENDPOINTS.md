# ELEMENT CALL ENDPOINTS — Wire Protocol Reference

Status: VERIFIED 2026-08-22 against real Element sources (not guessed).
Purpose: permanent record of exactly how Element initiates/joins calls, so Orbit's
implementations can be diffed against it without re-doing the archaeology.
Companion to `docs/planning/CALLS_MASTER_PLAN.md` (parity gaps are tracked there as plan items).

---

## 0. Provenance

| Source | Version / branch | Where checked |
|---|---|---|
| Element Desktop | v1.12.13 (`package.json`) | local clone `g:\orbit\element-desktop` (⚠ no node_modules — js-sdk/EC code NOT in clone) |
| matrix-js-sdk | `develop` branch | github.com/matrix-org/matrix-js-sdk raw files |
| Element Call web | `main` branch | github.com/element-hq/element-call raw files |
| OrbitX (Element X Android fork) | local clone `g:\orbit\orbitx` | features/call + gradle libs |

Rule of thumb: if a file below is quoted verbatim it was extracted from the listed
source on 2026-08-22. Anything marked UNVERIFIED is inference — re-check before relying.

---

## 1. Architecture: who owns calling

```
Element Desktop (Electron)
├── Electron shell ......... ZERO call logic EXCEPT screen-share picker
│                            (src/ipc.ts:138-147 desktopCapturer.getSources IPC,
│                             displayMediaCallback.ts, electron-main.ts:580-593,
│                             preload.cts:31)
├── matrix-js-sdk .......... all signaling: MatrixRTCSessionManager,
│                            MembershipManager, legacy m.call.* stack
└── Element Call web bundle . group-call UI + LiveKit SFU client (openIDSFU.ts)

OrbitX (= Element X Android fork, "ORBIT Chat")
├── ZERO native call logic. Delegates 100% to embedded Element Call web:
│    libs.versions.toml → element-call-embedded:0.20.3 Maven artifact
│    ElementCallActivity.kt WebView ← EMBEDDED_CALL_WIDGET_BASE_URL
│      = "https://appassets.androidplatform.net/element-call/index.html"
├── RustWidgetDriver / rust-sdk writes MSC3401 membership events
└── Ringing via MSC4075 (see §6). No native LiveKit, no MediaProjection screen share.

Orbit (this repo)
├── Group = Trixnity MSC3401 signaling → custom C++ JNI bridge → LiveKit C++ SDK 0.4.1
└── 1:1   = legacy m.call.* P2P over forked webrtc-java
```

Consequence: **the only meaningful comparison target is Element's js-sdk+EC stack**;
OrbitX is Element by delegation.

---

## 2. Event type literals (pinned from js-sdk `src/@types/event.ts`, develop)

| Enum key | Literal string | Used for |
|---|---|---|
| `GroupCallPrefix` | `org.matrix.msc3401.call` | LEGACY call-definition state event (current stack never writes; see §3) |
| `GroupCallMemberPrefix` | `org.matrix.msc3401.call.member` | group-call MEMBERSHIP state events (suffix appended at runtime). No plain `GroupCallMember` entry exists |
| `RTCSlot` | `org.matrix.msc4143.rtc.slot` | new-style slot/definition state event |
| `RTCMembership` | `org.matrix.msc4143.rtc.member` | sticky membership timeline event |
| `CallNotify` | `org.matrix.msc4075.call.notify` | call notification |
| `RTCNotification` | `org.matrix.msc4075.rtc.notification` | ringing (OrbitX uses this) |
| `RTCDecline` | `org.matrix.msc4310.rtc.decline` | decline notification |
| `CallInvite` … `CallNegotiate` | `m.call.invite`, `m.call.candidates`, `m.call.answer`, `m.call.hangup`, `m.call.reject`, `m.call.select_answer`, `m.call.negotiate` | legacy 1:1 P2P signaling |
| `CallSDPStreamMetadataChanged(Prefix)` | `m.call.sdp_stream_metadata_changed` / `org.matrix.call.sdp_stream_metadata_changed` | 1:1 stream announcements (screen share etc.) |
| `CallEncryptionKeysPrefix` | `io.element.call.encryption_keys` | EC encrypted-media key transport |

---

## 3. GROUP CALLS (MSC3401 / MatrixRTC)

### 3.1 What Element does NOT do: write call definitions

Verified in `MatrixRTCSessionManager.ts` (develop): **no `sendStateEvent` anywhere;
nothing publishes `org.matrix.msc3401.call` or any definition event.** Discovery reads:

```ts
// room-state listener:
if (event.getType() !== EventType.GroupCallMemberPrefix && event.getType() !== EventType.RTCSlot) return;
// sticky timeline listener:
if (!event.unstableStickyExpiresAt) return;
if (event.getType() !== EventType.RTCMembership) return;
// startup sweep:
MatrixRTCSession.sessionForSlot(this.client, room, this.slotDescription)
```

Default slot description: `{ application: "m.call", id: "ROOM" }`.

⇒ SFU selection lives inside each **membership** event (`focus_active` / `foci_preferred`),
not in a room-state definition. Orbit additionally writes dual-namespace definitions
(`m.call` + `org.matrix.msc3401.call` — `MatrixCallsRepository.kt:889-905,1130`,
`MatrixChannelsRepository.kt:228-230`): tolerated clutter, nothing modern reads it.

### 3.2 Membership state event — THE interop-critical shape

Source: js-sdk `src/matrixrtc/MembershipManager.ts` (develop), verified.

**State key template:** `_${userId}_${deviceId}_${application}${slotId}`
with `slotId = ""` when `needsEmptyStringRoomFix` (`application === "m.call" && id === "ROOM"`):

```
_org.matrix.msc3401.call.member  state key for @alice:x / DEVICE123:
_alice:x_DEVICE123_m.call          ← note EMPTY string between application and end
```

**Content (FLAT, top-level — not nested):**

```json
{
  "application": "m.call",
  "call_id": "",
  "scope": "m.room",
  "device_id": "DEVICE123",
  "membershipID": "@alice:x:DEVICE123",        // "${userId}:${deviceId}"
  "expires": <ms>,                              // see 3.3
  "m.call.intent": <intent>,
  "focus_active": { "type": "livekit", "focus_selection": "oldest_membership" },
  "foci_preferred": [ { "type": "livekit", "livekit_service_url": "...",
                        "livekit_alias": "..." } ],
  "created_ts": <ms>
}
```

Sent as type `org.matrix.msc3401.call.member` (+ optional suffixed variants per publish mode).

### 3.3 Expiry semantics

`DEFAULT_EXPIRE_DURATION = 4 h`; scheduled refresh writes `iteration × DEFAULT_EXPIRE_DURATION`
in `expires`, refreshed ~5 s before deadline (headroom 5 s). Crash ⇒ TTL expiry cleans up.
(Orbit currently uses 12 h TTL — fine, but refresh cadence differs.)

### 3.4 Focus selection rule

**Oldest membership wins**: sort active memberships by `created_ts`, take oldest,
use ITS `focus_active`. All joiners must connect to that same SFU+alias or they land in
a different LiveKit room ("connected but nobody hears me" class of bug).

### 3.5 SFU token exchange

Source: element-call `src/livekit/openIDSFU.ts` (main), verified.

Modern endpoint — `POST {serviceUrl}/get_token`:

```json
{
  "room_id": "<matrix roomId>",
  "slot_id": "m.call#ROOM",                    // LITERAL "${application}#${id}"
  "openid_token": "<OpenID token from homeserver>",
  "member": {
    "id": "<userId>",                           // NB field name is id, not user_id
    "claimed_user_id": "<userId>",
    "claimed_device_id": "<deviceId>"
  }
}
```

Legacy endpoint — `POST {serviceUrl}/sfu/get`:

```json
{ "room": "<matrix roomId>", "openid_token": "...", "device_id": "<deviceId>" }
```

Response (both): `{ "url": "<livekit ws url>", "jwt": "<access token>" }`

JWT payload mapping (client-side): `video.room → livekitAlias`, `sub → livekitIdentity`.
**The LiveKit room name is chosen server-side by the JWT service** from room_id+slot_id —
clients never invent an alias. Matching `serviceUrl` (from foci) is what matters.

Default public service URL seen in the wild: `https://livekit-jwt.call.matrix.org`.

### 3.6 Track conventions over LiveKit

Mic track published with source=MICROPHONE; screen share as separate track tagged
screen_share source — EC tile UI keys off source type (a share must NEVER look like a
second face). Audio encoding knobs EC relies on exist in LiveKit SDKs
(`TrackPublishOptions.audio_encoding.max_bitrate`, `dtx`, `red`).

---

## 4. 1:1 CALLS (legacy m.call.*)

Event vocabulary: `m.call.invite → m.call.candidates ↔ m.call.answer / m.call.reject /
m.call.select_answer → m.call.hangup`, renegotiation via `m.call.negotiate`.

Element/js-sdk conventions (relevant to Orbit parity):
- `party_id` = the caller's **deviceId** (NOT full userId).
- `version` follows spec era consistently per message (no string/int mixing).
- Invites have enforced lifetimes; glare handled via `select_answer` +
  party_id filtering (multi-device loops prevented).

Orbit diverges on all three (party_id=userId; mixed `"1"`/`0`; no lifetime/glare) —
tracked in master plan.

---

## 5. SCREEN SHARE plumbing (Desktop)

Electron shell contributes ONLY the source picker:

```
EC web getDisplayMedia() → callback → preload.cts:31 → ipc.ts:138-147
  desktopCapturer.getSources({types:[screen,window], thumbnailSize...})
  → main picks → displayMediaCallback.ts hands stream id back to renderer.
```

All capture/publish logic lives in the web bundle. Stream announcements on the 1:1 path
go through `m.call.sdp_stream_metadata_changed` (§2); group/LiveKit path tags tracks by
source type instead.

System audio in shares: works via Electron loopback where available; on Orbit group path
currently disabled (P2P-only WASAPI loopback hack exists) — master plan Part F3.

---

## 6. RINGING

- OrbitX / Element-X: `org.matrix.msc4075.rtc.notification` (MSC4075),
  `RINGING_CALL_DURATION_SECONDS = 90`; declines via `org.matrix.msc4310.rtc.decline`.
- Legacy 1:1 ringing = the `m.call.invite` itself (timed out client-side).
- **Orbit ignores MSC4075 entirely** ⇒ X-stack users ringing a room will not ring Orbit
  users (they still see/join the ongoing call). Decision pending — master plan item.

---

## 7. Orbit parity cheat-sheet (2026-08-22)

| Flow | Orbit vs Element | Notes |
|---|---|---|
| Group membership shape | ✅ character-identical | state key `_user_device_m.call`, flat content incl. focus_active/foci_preferred (`mirrorVisibleElementShape`) |
| Group token exchange | ✅ identical | `/sfu/get` legacy body, slot literal `m.call#ROOM`, `{url,jwt}` response |
| Focus selection | ✅ oldest-membership-wins | shared resolveGroupCallJoinTarget (C4 fix) |
| Definition events | ⚠️ extra | Orbit dual-writes what Element never writes — cleanup candidate (Part J) |
| Mid-call video toggle (group) | ❌ missing | fixed at join via JoinVideoPolicy — Part E |
| Screen share announce/system audio (group) | ❌ partial | Part F |
| 1:1 party_id | ✅ FIXED 2026-08-22 | was full userId; now `CredentialManager.deviceId` (js-sdk `ourPartyId = client.deviceId`), fallback userId |
| 1:1 version field | ✅ FIXED 2026-08-22 | candidates batch sent int `0` (dragged Element into legacy v0 handling); now string `"1"` everywhere like js-sdk `VOIP_PROTO_VERSION`; Trixnity VersionSerializer maps "0"→int 0 on write but accepts both on read |
| 1:1 invite lifetime | ✅ FIXED 2026-08-22 | Orbit rang forever; now expires invite after its `lifetime` (default 60 s = js-sdk CALL_TIMEOUT_MS), local reset only — caller's own timer hangs up remotely |
| select_answer receive / multi-device glare | ❌ not implemented | Orbit ignores incoming m.call.select_answer (single-device impact none); candidate new plan item (H4) |
| MSC4075 ringing | ❌ ignored | payload parsed inside compiled matrix-rust-sdk (`RtcNotificationType` NOTIFICATION/RING) — wire schema unverified locally; group ringing is a NEW feature for Orbit, needs plan item first |

---

## 8. Re-check pointers (if Element moves)

- js-sdk membership/token/discovery: `src/matrixrtc/{MembershipManager,MatrixRTCSession,
  MatrixRTCSessionManager}.ts`, element-call `src/livekit/openIDSFU.ts`,
  `src/room/useLoadGroupCall.ts` (github raw, develop/main).
- Event literals: js-sdk `src/@types/event.ts`.
- Desktop picker IPC: element-desktop `src/ipc.ts`, `src/displayMediaCallback.ts`,
  `electron-main.ts`, `preload.cts`.
- OrbitX ringing/delegation: `features/call/impl/ElementCallActivity.kt`,
  `gradle/libs.versions.toml`.
