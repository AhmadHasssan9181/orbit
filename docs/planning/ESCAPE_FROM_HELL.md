# 🌋 ESCAPE FROM HELL

### The Orbit Bug Codex — every demon we met in the Matrix wilderness, and how we killed it.

---

> ## ⚠️ ABANDON ALL HOPE, YE WHO DEBUG HERE ⚠️
>
> **To all those wishing to baptize these demons:** know that thy
> predecessor came to this wilderness with a full head of hair, a clean
> commit history, and the arrogance of a man who once fixed a CSS bug in
> under an hour.
>
> The wilderness took payment.
>
> Here lies the record of his suffering: **nine rounds of E2EE key exchange**
> before a single "hello" survived its own encryption. **A watchdog** that
> executed healthy joins mid-flight, for treason. **A jitter buffer** that
> honestly reported *39 minutes* of delay, because nobody ever told it that
> lies must be small. **Peers who lied about existing. Members who were
> secretly tombstones. Tombstones that still voted in the roster.** And a
> JVM, thrice slain, that kept attending meetings as a ghost drinking 4 GB
> of RAM.
>
> Hours wasted here: unnumbered. Nights: darker than vodozemac's source
> tree. Tears: collected in a bucket and reserved for hydrating the CI
> runners we never built.
>
> So if thou art reading this at 2am, weeping softly into thine keyboard —
> good. That is step one of every fix in this book. Now laugh a little,
> dry thine eyes, open the triage table, and remember:
>
> **Every demon in here was baptized by someone who also wanted to quit.
> They are all dead. Thou art still compiling. Go forth.** 🕯️
>
> — maintained since the first one-way-audio incident, 2026-08

---

## How to use this document

Each entry is written **symptom-first**, because that's all you have at 2am:

```
## [ID] Short name
**You see:**    the user-visible symptoms
**It is:**      the actual root cause (usually not where it looks like it is)
**Fix:**        what changed, and where
**Prove it:**   how to verify the fix / reproduce the check
**Files:**      where to look
```

Start from the [triage table](#0-triage-table), jump to the entry, read the
root cause even if you think you know it. Half of these bugs were *two* bugs
wearing a trench coat.

*Pick a circle. Any circle. They're all on fire.*

**Related scrolls in this dungeon:** `docs/planning/CALLS_MASTER_PLAN.md` (the campaign
itself), `docs/planning/ELEMENT_CALL_ENDPOINTS.md` (wire protocol archaeology, verified
against Element sources), `docs/planning/headache_medicine.md` (UI architecture map),
`docs/planning/LIVEKIT_CPP_AUDIO_BRIDGE.md` (native bridge notes).

---

## 0. Triage table

| Symptom | Go to |
|---|---|
| Peer can't hear us (TX dead) | [H8-TX](#h8-tx--tx-dead-peers-cant-hear-us) — **SOLVED, was a key-ROTATION bug, not a key-DISTRIBUTION bug** |
| We can't hear a peer who joined later | [H8-RX](#h8-v3--rx-dead-for-latecomers-specifically) / [H7](#h7--settle-nudge-silent-element-peers) |
| Audio works, then garbles/chops | [BUG-D](#bug-d--robotic-garbled-audio-in-one-specific-room) |
| One person too loud/quiet, or mute ONE person's playback | [H15](#h15--per-participant-volume--the-local-mute) |
| **Can hear SOME peers and not others, same build** | **[H20](#h20---the-two-key-same-map-why-volume-and-mute-were-dead)** |
| No visual indicator of who is speaking | [H16](#h16--the-speaking-glow-native-rms--the-tile-ring) |
| Key delivery to one device keeps failing long-session | [H8-V4](#h8-v4--one-bad-device-poisoned-the-whole-batch) |
| Everything is ~2s late for minutes | [H6](#h6--2s-end-to-end-latency-that-never-recovers) |
| Join hangs forever, or fails then stays connected | [H12](#h12--join-stall--the-watchdog-zombie) |
| Can't navigate while in a call | [H10](#h10--the-overlay-trap--random-call-closes) |
| Screen share stops but system audio keeps flowing | [H9](#h9--screen-share-stop-leaves-system-audio-publishing) |
| RAM eats 20 GB in calls | [H11](#h11--the-20gb-ram-hog) |
| Closing the app leaves a 3–4 GB java.exe | [H13](#h13---the-window-close-zombie-jvm) |
| Packaging/build fails or MSI acts weird | [Circle V](#circle-v--packaging-and-release) |
| Audio works but a peer's video never renders | [Rounds 6–7](#rounds-67--heard-but-not-seen-the-downgrade-mystery) |
| Log line looks terrifying | [The Bestiary](#the-bestiary--benign-log-noise) first |
| Hang up, rejoin, stuck on "Connecting" forever | [H21](#h21--the-rejoin-hang-one-rule-broken-five-times) |
| A peer's video is frozen / blocky / smeared | [H22](#h22--the-degenerate-frame-stretched-across-the-tile) |
| A peer shows up once but the log announces them 8 times | [H23](#h23--one-person-announced-eight-times) |
| App exits with `0xC0000409` / "VM attach current thread failed" | [H24](#h24--the-0xc0000409-termination-abort-native-threads-outlived-the-jvm) |
| What's still broken or unbuilt? | [Unfinished Business](#unfinished-business) |

---

# CIRCLE 0 — THE WIRE

> 🕯️ *The format below is what IS deployed. The spec is what SHOULD be.
> When paper and wire disagree, believe the corpse holding the packet captures.*

*There are no docs for the deployed matrixRTC format. This section IS the
docs — reverse-engineered from matrix-js-sdk@84fb28a (develop),
element-hq/element-call@b51a33c (main) and livekit/client-sdk-js@f3732d1.
Implement THIS, not MSC4143's future branch text.*

## The join network chain

Joining an encrypted group call touches FOUR services in order. Any stall
here used to kill the whole join — see [H12](#h12--join-stall--the-watchdog-zombie):

1. **ICE servers** — fetched from room metadata / well-known.
2. **OpenID token** — `POST /_matrix/client/v3/user/{userId}/openid/request_token` from YOUR homeserver.
3. **SFU JWT** — token exchanged at the broker (`livekit-jwt.call.matrix.org`). The legacy `/sfu/get` endpoint sets the JWT `sub` to `` `${userId}:${deviceId}` `` raw concat (e.g. `@alice:example.org:JLAFKJWSCS`). **That string is the LiveKit participant identity forever after** — every key slot, every renderer lookup.
4. **LiveKit WS connect + publish**, then the membership PUT back to Matrix state.

## Matrix endpoints you will suffer against

| Endpoint | Used for | The trap |
|---|---|---|
| `POST /_matrix/client/v3/keys/upload` | Own device keys + OTKs | OTK pool starvation is silent; peers' claims fail while everything "looks connected" |
| `POST /_matrix/client/v3/keys/query` | Fetch peer device identity keys | Warm these BEFORE `encryptOlm` — cold queries delay seed distribution (Round 8) |
| `POST /_matrix/client/v3/keys/claim` | Claim OTKs to open olm sessions | **CONSUMES the claimed OTK.** A diagnostic self-claim drained our pool to 1 spare. Gate self-probes behind observed-zero-inbound |
| `PUT /_matrix/client/v3/sendToDevice/m.room.encrypted/{txnId}` | Olm-wrapped key distribution | One payload per DEVICE; per-device granularity or one bad device poisons its user's batch |
| `GET/PUT /_matrix/client/v3/rooms/{roomId}/state/m.call.member/{stateKey}` | Sticky call membership | This is what the nudge clears + republishes. Sticky TTL ≈ **12h legacy**: crashed clients stay "members" for hours |
| `GET /_matrix/client/v3/sync` | Everything arrives here | Empty-content m.call.member events = TOMBSTONES/removals, not members |
| Room state `m.room.encryption` | Gate for E2EE media | Presence ⇒ enable E2EE at connect (PER_PARTICIPANT mode); SHARED_KEY only for URL-password rooms |

## The key event (deployed schema)

Event type **`io.element.call.encryption_keys`** (PLURAL), wrapped in Olm
per-device sessions, transported ONLY as to-device. Room-event transport was
disabled 2025-10 (#4929) and REMOVED 2026-02 (#5119) — don't look for it.

```json
{
  "keys":    {"index": 0, "key": "<base64 PADDED of 16 raw bytes>"},
  "room_id": "!r:s",
  "member":  {"id": "<memberId>", "claimed_device_id": "DEVICE"},
  "session": {"call_id": "", "application": "m.call", "scope": "m.room"},
  "sent_ts": 1730000000000
}
```

- **Per-DEVICE sender keys, NOT one group key.** Each device: 16 CSPRNG bytes, index starts 0, `(prev+1)%256`.
- `member.id` fallback convention: `"${sender}:${device_id}"`.
- Emit PADDED base64; accept padded AND unpadded on read.
- Rotation rules (RTCEncryptionManager.ts): leaver ⇒ rotate IMMEDIATELY + redistribute to ALL remaining; joiner ⇒ resend current key if age < 10_000ms (`keyRotationGracePeriodMs`) else rotate; after sending a CHANGED key sleep ~1000ms (`useKeyDelay`) before using locally; FIRST key distributes + activates immediately.
- Receiver dedup: per (participant, index) keep NEWEST sent_ts — that's Orbit's `OutdatedKeyFilter`.
- Old Element peers (pre-2025-07 manager) used 3000/5000ms delays but the SAME event family/schema — compatible.
- The future stable format `m.rtc.encryption_key` (32-byte keys) exists only as MSC4143 branch text. NOT SHIPPED. Ignore for interop.

## LiveKit mapping & the crypto chain (where bugs go to hide)

- **PER-PARTICIPANT key slots**: `keyProvider.setKey(participantIdentity, keyBytes, index)` — NEVER the shared-key slot. Identity = JWT `sub` = `mxid:device`. Log `localParticipant->identity()` at connect and VERIFY it equals mxid:device, or every downstream mapping silently misroutes.
- Hashed identity mode (`unpaddedBase64(SHA256(JSON.stringify([userId,deviceId,memberId])))`) exists only in sticky/"Matrix 2.0" deployments (`m.rtc.member` events) — not today's default.
- The 16 distributed bytes are **RAW key material**. LiveKit derives the frame key itself: HKDF-SHA256(salt=UTF8(`"LKFrameEncryptionKey"`), info=128 ZERO bytes) → AES-GCM-128. NO extra Matrix-side derivation — adding one is how you get perfectly-formed undecryptable traffic.
- IV: 12 random bytes per frame. Unencrypted leading bytes: audio=1 (Opus TOC), VP8 key=10, delta=3. Ratchet window 10, key ring 256, tolerate 10 consecutive failures.

> ⚠️ **NATIVE TRAP**: `livekit-sdk-windows-x64-0.4.1` `KeyProviderOptions`
> DEFAULTS TO PBKDF2. Element's JS worker ALWAYS uses HKDF. You MUST set
> `key_derivation_function = KeyDerivationFunction::HKDF` explicitly (plus
> `ratchet_window_size=10`, `key_ring_size≈256` to mirror EC) — or frames
> never decrypt and every human involved questions their sanity.
> `E2EEOptions{encryption_type=GCM}` goes in `RoomOptions::encryption` BEFORE
> connect; `E2EEManager::setEnabled(true)` post-connect.

## Membership event validation

Modern js-sdk `checkSessionsMembershipData` REQUIRES content fields
`device_id`, `call_id`, `application` (`"m.call"`), `focus_active`;
`devices[]` is optional. Recipients parse out per-membership deviceId.
Anything failing validation is either ancient garbage or a tombstone —
exclude it from rosters, targets, and counts.

---

# CIRCLE I — E2EE MEDIA KEYS

> 🕯️ *Here speak the key-exchange demons. Their native tongue is olm,
> their love language is membership diffs, and their favorite meal is a
> developer's weekend.*

*The deepest circle. Here be olm events, ratchets, and peers that lie.*

## The matrixRTC wilderness survival rules

Burn these into your brain before touching any key-exchange code:

1. **Element peers only act on membership DIFFS.** A plain join produces no
   response from peers whose sync is throttled/frozen. Only a leaver→joiner
   transition makes them rotate + redistribute keys.
2. **Empty-content membership state events are REMOVALS** (tombstones,
   redactions), not members. Counting them inflates peer counts by ~30x.
3. **The sticky roster LIES about presence.** A crashed client's membership
   event stays unexpired for HOURS (legacy 12h TTL). The **LiveKit participant
   view is the authoritative liveness source** — roster for identity, LiveKit
   for truth.
4. **A received olm event proves that session direction works NOW.** Use it.
5. **libwebrtc jitter-buffer delay fields are CUMULATIVE LIFETIME SUMS**, not
   instantaneous values. See [H6-RX](#the-jitter-buffer-lie).
6. In cancellable coroutine code, **always rethrow `CancellationException`
   before any generic catch / `runCatching` swallow** (see Round 8).

## Element's SDK, read from source (the key-maintenance verdict)

> 🕯️ *"We should request keys every now and then, right?" We went and read
> the SDK instead of guessing. The answer was NO — and ours already does more.*

**Question:** how does Element's matrix-js-sdk keep keys alive for peers on
bad internet? Should we poll/request keys periodically?

**Answer, from `MatrixRTCSession.ts` + `RTCEncryptionManager.ts`
(matrix-js-sdk develop):**
- **ZERO timers.** No setInterval/setTimeout anywhere in key maintenance.
  Everything is event-driven off membership updates
  (`onMembershipsUpdate` fires on EVERY recalculation, even no-op ones).
- `useKeyDelay` default **5000 ms** before a changed key goes live;
  join-config override fallback 1000 ms. `keyRotationGracePeriodMs` = **10 s**.
- Rotation law: leave → ALWAYS rotate; join → resend same key if younger than
  the grace period, else rotate; no change → do nothing.
- **Send failure is swallowed** (caught + logged) and the member is simply not
  added to `sharedWith` — the failed peer reappears as "not shared" on the
  NEXT membership update, which re-triggers a send. Indirect retry only.
- Inbound: keys that arrive before their membership event are buffered
  (`keysWithoutMatchingRTCMembership`) and retried.
- **There is NO key-request mechanism.** The protocol is push-only. Nobody
  "asks" for keys; senders are re-poked by diffs.

**Verdict:** Orbit's convergence sweeper (10 s UNCONDITIONAL reconcile vs
Element's diff-only events), success-marked delivery ledger, reply-on-receive,
settle/latecomer nudges ALREADY EXCEED Element's SDK. Do not add key polling —
it would be churn, not robustness. Bad-internet accommodation lives in the
sweeper + nudge paths, not a heartbeat.

## Rounds 6–7 — heard but not seen (the downgrade mystery)

**You see:** Peers hear AND see Orbit, including screen share. Orbit hears
peers but renders NO peer video. Log shows exactly ONE
`e2ee participant key installed` line — your OWN identity — and zero
`peer key sender=` lines all session. Native state for the peer sits at
4(MissingKey) forever.

**It is:** Peer keys never reached the native KeyProvider. But hold on to the
OPEN CONTRADICTION that makes this entry worth reading: the user HEARD peers
perfectly with ZERO peer keys installed. Two surviving explanations:
(a) Element silently downgrades a participant to plaintext when it can't find
their key, or (b) Orbit's custom audio path bypasses the frame cryptor while
video cannot. **Lesson: do not assume E2EE health is symmetric across media
types** — verify audio and video paths independently.

**Diagnostics deployed during this round** (kept permanently):
raw sync probe counting inbound `m.room.encrypted` BEFORE trixnity decrypt
(`raw olm to-device #N` — splits server-never-delivered from
delivered-but-unreadable); every silent-drop path in handleDecrypted now logs
(non-UnknownEventContent class, event-type mismatch, other-room,
outdated-filter reject); `WARN configureE2EE(true)` empty-message lines are
the benign pre-session race (see Bestiary).

**Files:** `CallMediaKeyExchange.kt`, native E2EE config in the LiveKit bridge.

## H7 — Settle nudge (silent Element peers)

**You see:** You join a call; an Element user who was there for hours never
sends keys. They can hear you; you hear nothing. Forever.

**It is:** Their client only redistributes on membership diffs. Your join was
not a diff worth reacting to (throttled tab, sleeping laptop, whatever).

**Fix:** One-shot early "settle" check in `CallMediaKeyExchange.start()`:
after `NUDGE_SETTLE_DELAY_MS` (5s), if `receivedPeerIdentities.isEmpty()` and
the roster is non-empty → fire `nudgeMembership` (leave+rejoin membership
republish, see below). Stamps `lastNudgeMs` so the reactive watcher can't
double-fire inside its cooldown. Keeps its own budget slot; healthy rooms log
`settle check: N peer key(s) already received` and skip.

The whole nudge budget lives in five numbers (`CallMediaKeyExchange.kt`
companion):

```kotlin
const val NUDGE_WATCH_INTERVAL_MS = 10_000L  // reactive watcher cadence
const val NUDGE_SETTLE_DELAY_MS   = 5_000L   // one-shot settle check after join
const val NUDGE_GRACE_MS          = 45_000L  // reactive wait for FIRST peer key
const val NUDGE_COOLDOWN_MS       = 90_000L  // minimum spacing between nudges
const val NUDGE_MAX_PER_CALL      = 2        // total membership churn budget/call
```

**Prove it:** Silent peer → nudge fires ~5s after join → their key arrives
within ~1s → e2ee state goes 4(MissingKey)→1(Ok).

**Files:** `app/src/commonMain/kotlin/livekit/CallMediaKeyExchange.kt`,
`repository/MatrixCallsRepository.kt` (`nudgeOwnMembership`: clears our sticky
m.call.member event → wait ~2.5s → republish = forces EVERY peer through
leaver+joiner key paths without touching the LiveKit connection).

## H8-TX — TX dead (peers can't hear us)

> 🕯️ **SOLVED (v0.2.2). The entry below was a beautiful wrong theory, and it is
> kept in full because the wrong theory is the part that cost the time.**
>
> Everything written under "It is" and "Fix" is **HISTORICAL**. It describes a
> plausible and *partly real* set of distribution gaps — and fixing all of them
> did not make the symptom go away, because the symptom was never about
> distribution. Read the closing section before touching key delivery again.

**You see:** You join a room with many participants. You hear everyone. Nobody
hears you. Mic publishes fine, your own E2EE says Ok.

**It is:** Diff-only key distribution is structurally incomplete in two ways:
(a) a peer who RESET their key ring mid-call keeps the same device id ⇒ no
roster diff ⇒ we never resend them our new index; (b) late joiners weren't in
`knownTargets` when we distributed ⇒ they get nothing ever. Anyone without our
current (index, key) pair decrypts nothing while their TX to us works.

**Fix:** Reply-on-receive in `CallMediaKeyExchange.maybeReplyWithOwnKey` — on
every FRESHLY accepted peer key event, resend our current key to that device
over the just-proven olm direction. Payload+index snapshotted atomically under
the roster mutex (`OwnKeyRing` itself is unlocked — a torn read across a
rotation would corrupt installs). `OutdatedKeyFilter` runs BEFORE the hook, so
resends carry no new index and cannot ping-pong between two Orbit peers.
`/keys/query` warms unknown sender devices pre-encryptOlm. 3s per-device rate
guard.

The hook's contract (signature + invariants — read the body before touching):

```kotlin
private fun maybeReplyWithOwnKey(sender: String, claimedDevice: String)
```

- Fires ONLY for freshly ACCEPTED peer key events — `OutdatedKeyFilter` runs
  upstream, so resends can never carry a new index and cannot ping-pong.
- Payload+index snapshotted atomically under the roster mutex; `OwnKeyRing`
  itself stays unlocked (a torn read across a rotation would corrupt installs).
- One device per send; 3s per-device rate guard.

**Prove it:** Room where a peer resets their ring mid-call → they now receive
our current index within seconds of sending us anything.

**Files:** `CallMediaKeyExchange.kt`.

### The actual cause (v0.2.2) — it was ROTATION, not distribution

> 🕯️ *Every fix above is real code that works. None of them fixed the reported
> symptom. Here is what did, and the one observation that cracked it.*

**It is:** We rotated the shared media key for reasons that are not a departure.
Rotation moves `currentIndex` 0 → 1, and any peer still holding index 0 cannot
decrypt anything we send. If a rotation happens for a bogus reason, everyone
silently goes deaf to us **while their own TX to us keeps working** — which is
precisely the reported asymmetry, and precisely why every distribution-side
theory above was consistent with the symptom and none of them was the cause.

**The observation that cracked it:** Element↔Element never has to *infer* a
departure. Element sees a membership diff and reacts to it. Orbit was inferring
departures from six different signals, and three of those signals fire during a
perfectly normal call. Element therefore never enters the divergent path, which
is why "it works on Element, it doesn't work on us" was the clue rather than a
coincidence.

**It was never a regression.** Bisecting to `8ac6ca1` (packageVersion 0.1.5)
reproduced the symptom identically, so the defect is original. The git history
cannot date it: an author-rewrite flattened all 60 commits to one author and two
dates. If you go looking for "the commit that broke audio", you will find
nothing, because there isn't one.

**Three wrong rotation triggers, each found from log evidence, not by reasoning:**

| Trigger | What it really was | Commit |
|---|---|---|
| A peer "appears" without an announce | A **latecomer sighting** — a peer already present got re-observed, not a new arrival | `226248f` |
| A roster entry vanishes from the LiveKit room | A **ghost membership** — `@nfak_ism`, a stale MatrixRTC record that never had keys | `da335e7` / `c922681` |
| A joiner appears during the grace window | A **rejoin** — `Element`'s `RESEND_GRACE_PERIOD_MS` branch was unreachable in any real call, so *every* rejoin rotated | `5c664ee` |

**Fix:** Rotation is now reachable from exactly one place — a **confirmed
departure** (`membership: leave`). `ConfirmedLeavers` + `ConfirmedLeaveLedger`
stop a ghost from counting as a leaver, and leave events are **recorded** in
`MatrixRtcMembership.kt` rather than discarded, so there is something to confirm
against. Joiners always receive the *current* index; they never cause a rotation.

**On the nudges:** `nudgeOwnMembership` republishes with the **same
membershipID and state_key** — a byte-identical event. Element sees no diff and
ignores it. **The nudge has never done anything on the Element side.** It is kept
because it provably works against Orbit peers (who react to the republication),
but do not read its existence as evidence that Element is being prodded.

**Prove it:** Element↔Orbit and Element↔Element must behave identically across
a rotation. Verified by the user in v0.2.2.

**Files:** `CallMediaKeyExchange.kt` (rotation policy, `ConfirmedLeaveLedger`),
`CallMediaKeysCore.kt` (`decideMembershipReaction`, `ConfirmedLeavers`),
`MatrixRtcMembership.kt` (leave events recorded at ~L43–66).

## H8-V2 — Ghost memberships burn the nudge budget

**You see:** Reactive nudges fire at "30 peers" of which ~29 are tombstones;
both nudge attempts die on crashed clients' zombie devices.

**It is:** Roster counting includes empty-content removals and long-expired
sticky entries (survival rule #2 and #3).

**Fix:** Three pieces:
1. `isActiveMatrixRtcMember` requires content evidence (membership field /
   v1 memberships[] / device_id / foci markers). Tombstones excluded from
   roster, distribution targets, and nudge counts.
2. `DeliveredKeyIndex` ledger — per-device highest CONFIRMED sent index,
   marked only on successful sendToDevice with that payload's own index.
3. Convergence sweeper every 10s — resends the current key to every live
   device lagging behind `ring.currentIndex`. "Live" = seen in roster within
   10 min OR currently connected per `LiveKitRemoteVideoFrameStore.remoteParticipants`.
   The LiveKit view rescues roster-invisible-but-talking peers.

**Files:** `CallMediaKeysCore.kt`, `CallMediaKeyExchange.kt`.

## H8-V3 — RX dead for latecomers specifically

**You see:** A NEW participant joins; everyone hears them, they hear nobody.
Not a settle case — keys were flowing fine when they arrived.

**You diagnose:** Every peer dumps keys within ~1s ONLY in response to Orbit's
leave/rejoin nudge (log epochs prove it). The reactive grace window is up to
45s; a joiner arriving mid-flow just waits.

**It is:** Same diff-rule as always: existing peers don't re-send to a new
member unprompted, and the newcomer's own arrival isn't a diff for THEM.

**Fix:** `LatecomerTracker` (in `CallMediaKeysCore.kt`, unit-tested): every 3s,
stamp each LiveKit participant's FIRST appearance (prune leavers so rejoins
restamp). If an identity has been present ≥8s past first appearance and has no
entry in `receivedPeerIdentities` → fire the nudge early. Deaf window drops
from ≤45s to ≤11s. Shares the reactive budget (max 2/call, ≥90s cooldown);
settle stamps the cooldown so start-of-call can't double-fire.

**Kotlin gotcha hit here:** `break` inside `run {}` is an experimental inline-
lambda error — hoist to `val cb = x; if (cb == null) { …; break }`.

**Files:** `CallMediaKeysCore.kt`, tests in `commonTest`.

## Round 8 — the self-inflicted regression (read this before refactoring)

**You see:** TOTAL silence both directions after a refactor. Logs show
"rotating key" printed AFTER "stopped", plus 13 fake
`olm encryption failed: StandaloneCoroutine was cancelled` warnings.

**It is:** Two compounding mistakes: (1) an inline 10s device-key wait delayed
the initial seed distribution until it collided with the user leaving; (2)
`runCatching` swallowed `CancellationException`, so the cancelled collector
kept running as a zombie while logging nonsense. Zero keys were ever sent.

**Fix / Law:** Distribution NEVER blocks on key material — send immediately to
stored-key devices, defer missing ones to the retry watcher. And **always
rethrow `CancellationException` before generic catch/runCatching-swallow** in
exchange code. Per-device granularity: partition ready/missing BEFORE
`encryptOlm` — one bad device must not poison its whole user's batch (that
silently dropped entire users, including the quiet ones you least suspect).

The law, in code shape:

```kotlin
try {
    distribute(snapshot)
} catch (e: CancellationException) {
    throw e   // NEVER swallow — a swallowed cancel leaves a zombie collector
              // that keeps logging "rotating key" after "stopped"
} catch (e: Exception) {
    log("distribution failed", e)
}
```

## H8-V4 — One bad device poisoned the whole batch

> 🕯️ *The sweeper swept. The ledger lied not. But one cursed device made the
> scribe burn the whole page — and its innocent siblings burned with it.*

**You see (day-1 field reports):** "one can hear some but can't hear another,"
worse in long calls / flaky networks. Existing recovery machinery (settle /
reactive / latecomer nudges, convergence sweeper, reply-on-receive) all worked
— but a single device whose olm encryption throws aborted distribution for its
ENTIRE user.

**It was:** `distribute()` built each user's encrypted payload with
`put(deviceId, olm.encryptOlm(...).getOrThrow())` inside ONE `buildMap` — first
throwing device discarded every sibling already encrypted in that pass, and
none of them got sent until the next 10s sweep. Multi-device users + one broken
device = systematic under-delivery. Also: an encryption failure carried no
recovery beyond a generic WARN — OTK exhaustion and mid-call device-key
rotation both looked like "unknown error", forever.

**Fix set (all in `CallMediaKeyExchange.distribute`):**
1. PER-DEVICE isolation — encrypt each device under its own `runCatching`;
   failures log `ERROR ... (OTK exhaustion or rotated device keys are the usual
   suspects)` and never touch siblings.
2. Failed devices stay UNMARKED in the delivered-index ledger → the convergence
   sweeper re-attempts at its 10s cadence. Deliberately NOT queued into the
   1.5s retry watcher — those devices have stored keys, so their failure is
   session-level; re-claiming every 1.5s would hammer `/keys/claim`.
3. `maybeRefreshDeviceKeys(userId)` after any failure — post-failure
   `/keys/query` refresh rate-limited to once/30s per user (heals rotated
   identity keys without hammering during outages).
4. Ledger truthfulness: `markDelivered` only for devices actually present in
   the sent batch (`deviceId !in encryptedByDevice.keys → continue`).

**Still true:** HTTP-200 from sendToDevice proves the HOME SERVER accepted the
event, nothing about the receiving client. Silent stale-device loss remains
invisible by protocol design — the ERROR lines above are the best available
telescope.

**Files:** `livekit/CallMediaKeyExchange.kt`.

---

## Trixnity shape traps (they cost real hours)

- `ClaimKeys.Response.oneTimeKeys` is `Map<UserId, Map<String /*DEVICE id*/,
  Keys>>` where `Keys implements Set<Key>`; identify usable OTKs by the FULL
  key id prefix (`signed_curve25519:`…), not the device-id string.
  A probe testing device ids reports `claimable=false` forever while OTKs are
  fine.
- `saveDeviceKeys` takes a `UserId` object, not String — value-class mangling
  hides it from javap.
- `getKeys(...)` is suspend with default args → mangled JVM name; call through
  Kotlin or count the `-pFTbHVM`-style suffixes.
- `continue` inside inline lambdas (`getOrElse`) is an experimental error —
  restructure with `.onFailure{}.getOrNull() ?: continue`.
- OlmDecrypter notifies ALL subscribers of every decrypted event; filter by
  type yourself. `OutdatedKeyFilter.accept` correctly passes first receipts.
- `POST /keys/claim` **CONSUMES** the claimed OTK. A diagnostic self-claim
  drains your own pool (observed live: `otkCount=1` spare left). Gate any
  self-probe behind "zero inbound olm observed"; replenishment cadence is
  trixnity's business, not yours to assume.
- Sync liveness needs proving too: the raw sync probe logs
  `sync probe alive batch=N events=M olmSoFar=K` heartbeats — check them
  before declaring "server never delivered".

---

# CIRCLE II — JOINS & CALL LIFECYCLE

> 🕯️ *Every join is four services holding hands across the internet.
> When one lets go, production calls it "network issues" and moves on
> to the next ticket. Do not be production.*

## H12 — Join stall & the watchdog zombie

**You see:** "Joining…" hangs. Sometimes it fails visibly but audio still
works (!). Retrying spawns TWO live sessions (audio stats reset mid-log).

**It is:** `MEMBERSHIP_TIMEOUT_MS` clock started at tracker.begin() covering
THREE sequential network hops (ICE fetch → OpenID token → SFU JWT) BEFORE the
membership PUT it polices. On a slow day the 8s deadline blew mid-connect:
watchdog GiveUp ran (`disconnectFromLiveKit(false)` + markFailed +
failGroupJoin) while `connectNativeGroupMedia` kept running and completed ANYWAY
⇒ "Skipping finalizeGroupJoin readiness write: call already failed" = a fully
connected zombie under a visible failure UI. User retry = second native
session.

**Evidence pattern in logs:** stall ERROR line BEFORE `Published … status=200`,
then mic publish success lines AFTER the failure.

**Fix:**
1. `GroupCallConnectionTracker.noteJoiningProgress()` — resets
   `stageEnteredAtMs` WITHOUT changing stage; no-op unless stage==Joining &&
   !terminal. Called right after token fetch so the deadline measures the
   membership PUT alone. Timeout raised 8s → 15s.
2. Cooperative abort guards in `CallManager`: after the `!membershipVisible`
   check (pre-native, nothing to tear down), and post-retry-loop on
   `stage == Failed` → `disconnectFromLiveKit(false)` + return false EVEN IF
   connect succeeded — the watchdog may give up mid-connect and the session
   may still complete; tearing it down is the whole point.

**Prove it:** Unit tests: stamp-rescues-breached-deadline,
stamp-is-no-op-after-Joining. Live: slow-network join completes; failed join
leaves zero resident sessions.

**Files:** `webrtc/GroupCallConnectionTracker.kt`, `webrtc/CallManager.kt`,
`commonTest/.../GroupCallConnectionTrackerTest.kt`.

Stage ladder for orientation: Idle → Joining → Joined → Connected →
AudioPublished → VideoPublished → InCall (+ Reconnecting/Failed). Watchdog
polls `evaluateDeadlines()` every 500ms.

The progress stamp — the whole trick, verbatim from
`GroupCallConnectionTracker.kt`:

```kotlin
fun noteJoiningProgress() {
    if (terminal()) return
    if (_connection.value.stage == GroupCallStage.Joining) stageEnteredAtMs = nowMs()
}
```

The post-connect zombie guard in `CallManager` (condensed to its shape):

```kotlin
var connectOk = false
for (attempt in 1..MAX_CONNECT_ATTEMPTS) {
    connectOk = tryConnect()
    if (connectOk || groupConnectionTracker.connection.value.stage == GroupCallStage.Failed) break
}
if (groupConnectionTracker.connection.value.stage == GroupCallStage.Failed) {
    // Watchdog gave up mid-connect, yet the native session may STILL have
    // completed. Tearing down even on success is the entire point — this is
    // zombie prevention, not failure handling.
    println("WARN [CallManager] Join aborted: call already failed; tearing down native session")
    livekit.disconnectFromLiveKit(userInitiated = false)
    return false
}
finalizeGroupJoin(roomId, callId, isVideo, authResponse, readiness, roomEncrypted)
```

## H10 — The overlay trap & random call closes

**You see:** Joining a room puts the call surface over the WHOLE window with
no minimize control; navigation impossible. Separately: calls sometimes end
"randomly" with nothing in the logs identifying why.

**It is:** OrbitApp mounted a second `LiveKitGroupCallSurface` fillMaxSize over
everything whenever a group call was active, on top of the embedded in-channel
surface; auto-minimize fired only on channel CHANGE. The "random close" was
most plausibly misclicking Leave/Back while trapped.

**Fix:** Suppress the popup when `selectedChannelId == activeCallChannel.id`
(the embedded surface owns rendering); elsewhere ALWAYS render the mini-pill
via `forceMinimized`; pill restore NAVIGATES back instead of re-expanding the
blanket. Attribution for future incidents: `endCall()` now logs INFO with the
top-5 stack-frame chain; WebRtcEffects logs WARN before ending on a matching
m.call.hangup. If another unexplained close happens, grep
`endCall invoked … by <chain>`.

**Files:** `ui/screen/main/*` (OrbitApp), `livekit/LiveKitGroupCallSurface.kt`,
`webrtc/CallManager.kt`, `ui/screen/main/WebRtcEffects.kt`.

---

# CIRCLE III — MEDIA PIPELINE (NATIVE)

> 🕯️ *Native C++ speaking WASAPI through JNI to a LiveKit SDK that ships
> with its own opinions. "Works on my machine" means nothing here —
> your machine IS the weird machine.*

## BUG-D — Robotic garbled audio (in ONE specific room)

**You see:** Speech sounds fast/high-pitched/choppy/robotic — but only in one
room, even SOLO. Other rooms perfect. Network stats pristine.

**It is:** Native mixer drain-cycling in `orbit_livekit_audio.cpp`:
`targetDepthPrimed` unlatched whenever the input queue hit empty; recovery
demanded the FULL 80ms depth again before contributing. On bursty decode paths
each jitter pause became an ≥80ms inserted hole. Holes + limiter interplay read
as "robotic". (ZCR analysis proved pitch was FINE — gaps, not chipmunk.)

**Fix:** `startupPrimed` latches once at full depth (3840 samples) forever;
post-drain recovery gate lowered to `kStarvationReprimeSamples = 960` (20ms);
starved-flag re-gates only after a true hungry tick. MIXSTAT gained `drops=`
(overflow trims) and `starved=` counts.

The fix in the mixer (`orbit_livekit_audio.cpp`), verbatim core:

```cpp
constexpr std::size_t kStarvationReprimeSamples = kSamplesPerChannel * 2; // 20 ms top-up after a true drain

// latch ONCE at full depth — never demand the full 80ms again mid-call
if (!queue->startupPrimed && queue->pcm.size() >= kTargetParticipantQueueSamples) {
    queue->startupPrimed = true;
}
const std::size_t requiredDepth = queue->startupPrimed
    ? kStarvationReprimeSamples      // 20ms is inaudible as a hole
    : kTargetParticipantQueueSamples; // full depth only for cold start
```

**Related landmine — padded SIDs:** the SDK sometimes returns sids with
trailing whitespace padding (`TR_AMsm2zYqaRtG4f   `). Padded-vs-clean
comparisons duplicate decoders summing delayed copies = another garble source.
`normalizedSid()` trims at ALL reader start/stop boundaries + publication
lookups + announced-set operations.

**Prove it:** Repro room → WAV gap analysis should show ~0 mid-speech stutters;
MIXSTAT `starved=` may be >0 (jitter is real) but holes ≤20ms are inaudible.

**Files:** `app/src/jvmMain/cpp/orbit_livekit_audio.cpp`.

**Build-verification law:** after native changes, confirm the DLL timestamp
actually changed — findstr filters can mask whether your build really rebuilt.

## H6 — ~2s end-to-end latency that never recovers

### The jitter buffer lie

`jitter_buffer_target_delay` and friends are **cumulative sums over emitted
frames**, NOT instantaneous. A raw read of 2342400ms means "39 minutes of
lifetime delay accounting", not a 39-minute buffer. Correct instantaneous:
`target_delay * 1000 / emitted_count`. Garbage-cap streaks at 120000ms.
Without this correction, H6 watchdogs false-fire resubscribes.

### The actual latency

**You see:** element→orbit speech arrives ~2s late early in a call; settles
by ~3min; every key rotation re-fires it.

**It is:** Inside the LiveKit SDK receiver jitter buffer — NetEQ peak-mode
holds an inflated target and decays over minutes. Our pipeline holds ≤160ms
(mixer cap), network is clean ⇒ residual is theirs. Every rotation restarts
peak-mode.

**Compounding trigger — the cold-decode storm:** installing the FIRST peer
key activates decrypt+decode across ALL tiles simultaneously. MIXSTAT logged
+394k samples trimmed in ~12s (`droppedOldest` up to 167/window,
`play.played` collapsing to 16) = a process-wide CPU hole right when latency
matters most. The H11 tile-conversion throttle shrinks the blast radius but
the storm still exists at key-activation moment.

**Shipped:** RXSTAT carries jbTargetMs/jbAvgMs/jbMinMs (per-frame averages).
**Next lever (unimplemented):** one-shot AUDIO resubscribe recovery when
jbTargetMs sustains >~400ms — `RemoteTrackPublication.setSubscribed(false→true)`
rebuilds the receiver; guardrails: per-track cooldown, max count, don't fight
announced-SID dedup.

LiveKit C++ SDK headers (stats fields, options) live locally after any build at
`app/build/jni/_deps/livekit-sdk/livekit-sdk-windows-x64-0.4.1/include/livekit/`.
Read them THERE instead of web-fetching. `AudioStream::Options` has only
capacity/noise-cancellation knobs — no NetEQ controls.

## H9 — Screen share stop leaves system audio publishing

**You see:** User stops screen share; viewers still hear their system audio.

**It is:** `WebRtcClientImpl.stopScreenShare()` tore down ONLY the P2P/ADM
path (`setSystemAudioShareEnabled(false)`). The group cppMedia sys-audio path
lives behind `setStreamAudioEnabled(false)` → `unpublishScreenShareAudio()`,
which NOTHING called on stop ⇒ WASAPI loopback kept publishing after the
share ended.

**Fix:** `stopScreenShare` mirrors `setStreamAudioEnabled`'s condition
(`isGroupCall && LiveKitFeatureFlags.cppMedia` → `unpublishScreenShareAudio()`).

**THE LAW:** any new publish path needs its teardown wired into EVERY stop
surface (stop button, window-closed auto-stop, dialog cancel), not just the
enable path's inverse.

**Files:** `webrtc/WebRtcClientImpl.jvm.kt` (+ platform impls).

## H11 — The 20GB RAM hog

**You see:** 3 participants + screen share ⇒ RSS climbs toward 16–20GB and
sits there.

**It is:** Not a leak — GARBAGE ACCUMULATION. Without `-Xmx`, max heap defaults
to ¼ of physical RAM, so G1 happily lets humongous-object garbage (1–8MB frame
buffers × 30fps × N tracks) pile toward 16GB+ before collecting. Churn sources:
remote-video JNI byte[] per frame, fresh DIRECT ByteBuffer per captured share
frame (`packI420`), camera RGBA preview packs, and UI-side
BufferedImage + `toComposeImageBitmap()` Skia copies per displayed tile frame.

**Fix:** `-Xmx2048m` on BOTH launch paths — `compose.desktop.application.jvmArgs`
(covers the packaged launcher) AND `tasks.withType<JavaExec>().configureEach`
(covers `:app:run`). Tile conversion throttled to ~20fps/tile (50ms gate;
next arriving frame retries — nothing stalls). Instrumentation: CallPathStats
STATS lines now carry `vid.remote n=/mb= vid.share n=/mb= camPrev=` throughput
plus `heap used=/max=MB pendingFin=` — rising pendingFin = finalizer backlog.

**If RSS climbs again, triage order:** heap pinned at 2048 with high pendingFin
→ need bitmap reuse/dispose path or lower throttle floor; vid.* MB/s exploding
→ cap capture fps / reuse the packI420 direct buffer.

**Files:** `app/build.gradle.kts`, `livekit/LiveKitLocalScreenShareTrack*`,
UI video tile conversion sites.

---

## H15 — Per-participant volume & the local mute

> 🕯️ *Day one of internal testing and the first prayer arrived: "let me turn
> DOWN the loud one." Discord had this in 2015. We have it now. Barely. It
> almost killed us less than usual.*

**You see:** friends demand per-person volume sliders + mute-one-person
without deafening yourself. (Also asked: where's the other person's
mute/deafen badge? See [Unfinished Business](#unfinished-business) — no audio
mute event exists yet; video-only.)

**The law of this feature:** the native mixer sums every participant at UNITY
gain into one mixed stream that becomes a single output device feed. There is
no per-person knob anywhere downstream of `mixOneFrame()` — so the gain had to
be born inside the mixer queue itself.

**Stack, top to bottom (all shipped):**
1. **cpp** (`orbit_livekit_audio.cpp`): `ParticipantPcmQueue.gain{1.0f}`;
   global `g_participantGains` map + mutex OUTSIDE the mixer so setters that
   arrive before the queue exists survive; `addParticipant` re-applies the
   remembered gain at queue creation (mute survives participant reconnects);
   `applyParticipantGain` mutates live queues under `mutex_`+`queueMutex`;
   accumulate loop multiplies by `queue->gain`. MUTED QUEUES STILL POP — only
   the contribution is zeroed, so starvation/jitter-depth bookkeeping is
   untouched (muting must not re-trigger the cold-buffer gate).
   ⚠️ **`participants_` is keyed by PARTICIPANT IDENTITY. Do not key it by track
   SID.** See [H20](#h20---the-two-key-same-map-why-volume-and-mute-were-dead).
2. **JNI**: `Java_livekit_LiveKitNativeAudioBridge_nativeSetParticipantGain(JNIEnv*, jclass, jstring, jfloat)`
   → `setParticipantGainEverywhere()` (writes map + live queue).
3. **Kotlin bridge**: `LiveKitNativeAudioBridge.nativeSetParticipantGain`
   external; `LiveKitWrapper` common expect / jvm actual.
4. **CallManager**: `_participantGains: MutableStateFlow<Map<String,Float>>`,
   `setParticipantVolume(identity, 0f..2f)`, `toggleParticipantMute()`;
   cleared + reset to unity on endCall. Independent of global deafen by design.
5. **UI**: `RenderNode.participantIdentity` plumbed on remote cam/voice/share
   nodes in `LiveKitGroupCallSurface`; `VideoNodeCard(…, callManager)` renders
   volume IconButton + 0–200% Slider on bottom-right pill.

**Gating rules that prevent stupid bugs:** controls appear ONLY when
`participantIdentity != null && !node.isScreenShare && callManager != null`.
No local/self tiles. Not on share tiles (a share mirrors the sharer's MIC
queue — same identity — so a slider there would silently control their voice;
that confusion stays banned until the mixer learns per-TRACK queues).

**Semantics worth remembering:** purely LOCAL playback shaping — remote side's
mic, everyone else, and server are untouched. Native gains persist for process
lifetime (bounded, tiny); Kotlin resets them to unity at endCall so state can't
diverge across calls.

**Files:** `jvmMain/cpp/orbit_livekit_audio.cpp`, `livekit/LiveKitNativeAudioBridge.kt`,
`livekit/LiveKitWrapper.kt`(+`.jvm.kt`), `webrtc/CallManager.kt`,
`ui/screen/call/LiveKitGroupCallSurface.kt`, `ui/screen/call/CallScreen.kt`
(RenderNode/VideoNodeCard live HERE, not in CallGrid), `ui/screen/call/CallGrid.kt`.

## H16 — The speaking glow (native RMS → the tile ring)

> 🕯️ *"A slight border glow of the user who is speaking." Discord's green
> halo, day minus-none. Ours is measured in hellfire, at the mixer.*

**The law:** Compose never sees audio frames, and per-participant loudness
does not exist downstream of `mixOneFrame()` — so speaking detection had to
be born where H15's gains were born: inside each participant queue.

**Stack, top to bottom (shipped):**
1. **Metering (cpp):** inside `queueMutex`, over the RAW popped samples
   (pre-gain — a locally-muted peer still glows when talking; their gain
   zeroes only their CONTRIBUTION), compute RMS → fast-attack/slow-release
   EMA (`rms >= ema ? ema = rms : ema = 0.85*ema + 0.15*rms`).
2. **Hysteresis + hold:** speak start at RMS ≥ 320, stop below 170, hold
   500 ms after last voice. Kills flicker without feeling laggy.
3. **Event discipline:** collect sorted speaking identities; emit ONLY on set
   change, throttled to one event per 150 ms (`g_speakersEmitMutex` +
   `g_lastSpeakers` compare-and-swap). No event storm while five people chat.
4. **Dispatch:** worker thread grabs JNI via `getJniEnv("active-speakers")`,
   builds `NativeMediaEvent$ActiveSpeakersChanged(sessionId, epoch,
   ArrayList<String>)` through the standard `g_eventSink` path; Kotlin
   filters stale epochs.
5. **Kotlin:** sealed event → `LiveKitNativeMediaSession._activeSpeakers`
   StateFlow (cleared on Disconnected) → expect/actual
   `liveKitActiveSpeakers()` → CallManager collects into `activeSpeakers`
   (cleared on endCall).
6. **UI:** `VideoNodeCard` animates `speakGlow` 0→1 (240 ms tween); border
   color lerps dark→accent, border width 1→2 dp, accent-tinted shadow scales
   to 18 dp. Screen-share tiles keep their red ring regardless.

**Semantics worth remembering:** change-only events mean the UI holds the
LAST set until the next change — no per-frame churn. Locally-muted peers
appear (by design, see #1). The whole stack dies cleanly on call end:
`clearParticipants()` resets native emit state, Disconnected clears the flow,
endCall clears CallManager's copy.

**Files:** `jvmMain/cpp/orbit_livekit_audio.cpp`,
`commonMain/livekit/NativeMediaEvents.kt`,
`jvmMain/livekit/LiveKitNativeMediaSession.kt`, `LiveKitWrapper.kt`(+`.jvm.kt`),
`webrtc/CallManager.kt`, `ui/screen/call/CallScreen.kt` (VideoNodeCard).

**Downstream consumers added same day:** (1) sidebar voice-channel member
rows (`ChannelList.kt`) get an animated green avatar ring + white name while
their user speaks — matched via `identity.substringBeforeLast(':') == userId`
(identities are `${userId}:${deviceId}`); (2) tile name pills go Discord-green
(`#23A55A`) while speaking. One metering pipeline, three visible surfaces.

## H17 — The remote mic badge (LiveKit track truth, not membership lies)

> 🕯️ *"Discord shows a red mic-slash under every muted avatar." The badge
> everyone reads a hundred times a call — we shipped it last.*

**Why NOT the m.call.member route:** `capabilities.audio` in the membership
payload is a static CAPABILITY declaration (`MatrixRtcMembershipContent`
publishes it once at join / video-toggle). Element Call never republishes it
on mute either — parsing membership would mean spamming room state on every
toggle AND lagging by federation round-trips. The real-time truth already
lives one layer down: LiveKit's own track mute state, in the same cpp room
delegate that feeds H16's glow.

**Stack (shipped):**
1. **Initial state:** `startRemoteReaderLocked` dispatches subscribe-time
   `track->muted()` so late joiners see badges for peers who were ALREADY
   muted (no event will fire for them).
2. **Live updates:** `onTrackMuted`/`onTrackUnmuted` gain an audio branch.
   SDK 0.4.1 trap: `TrackMutedEvent` has NO `.track` member — only
   `participant` + `publication`. Local/remote discrimination uses
   `publication->track()->remote()` (our own self-mute fires these handlers
   too and MUST be excluded). A null subscribed track = mute predates our
   subscription = handled by #1 instead.
3. **Dispatch:** `dispatchRemoteAudioMuteChanged(identity, muted)` mirrors
   the participant-connected dispatcher (sink mutex only — safe under the
   session mutex every caller holds). JNI signature `(JJLjava/lang/String;Z)V`,
   cached as `g_remoteAudioMuteChangedMethod`, nulled in BOTH teardowns.
4. **Kotlin:** `NativeRemoteVideoSink.onRemoteAudioMuteChanged` →
   `LiveKitRemoteVideoFrameStore._remoteAudioMuted` (Set<String>; pruned in
   `participantDisconnected`, emptied in `clear()` so teardown is free) →
   expect/actual `liveKitRemoteAudioMuted()` → `CallManager.remoteMicMutedIds`.
5. **UI:** shared `identityIsMicMuted(identity, mutedSet)` matcher accepts
   full `${userId}:${deviceId}` identities AND bare Matrix userIds.
   `RenderNode.isMicMuted` lights the name-pill MicOff on camera + voice-only
   tiles; sidebar member rows show the red MicOff next to the speaking ring,
   replacing the old local-only placeholder ("would ideally come from
   m.call.member" — it shouldn't; see Why NOT above).

**Files:** `jvmMain/cpp/orbit_livekit_audio.cpp`,
`commonMain/livekit/NativeMediaEvents.kt`,
`commonMain/livekit/LiveKitRemoteVideoFrameStore.kt`,
`jvmMain/livekit/LiveKitNativeRemoteVideoRenderer.kt`,
`LiveKitWrapper.kt`(+`.jvm.kt`), `webrtc/CallManager.kt`,
`ui/screen/call/CallScreen.kt` (RenderNode + VideoNodeCard),
`ui/screen/call/LiveKitGroupCallSurface.kt`, `ui/screen/drawer/ChannelList.kt`.

## The nudge, de-uglified (recovery-aware status)

> 🕯️ *The membership nudge works like a defibrillator: necessary, effective,
> terrifying if you don't warn people first.*

**You see:** a self-nudge fires (settle / reactive / latecomer — all stamp
`lastNudgeMs`). Media session untouched, but the UI may simultaneously show
"Reconnecting…"/"Waiting for participants' media…" — implying the call died
exactly while we're repairing it.

**Shipped fix:** `CallMediaKeyExchange.lastSelfNudgeAtMs()` (manager accessor
for the active exchange) → CallManager polls it once a second into
`selfNudgeRecovery: StateFlow<Boolean>` (true within
`NUDGE_RECOVERY_VISIBLE_MS` = 20 s of a nudge — covers the 2.5 s membership
gap plus peer reaction). The group-call banner swaps its copy while lit:
"Reconnecting…" → **"Repairing audio link…"**, "Waiting for participants'
media…" → **"Restoring participants' audio…"**. Same chips, same colors —
truthful, calm, no fake progress bars. Resets on endCall.

## Settings that lie (the honesty audit)

> 🕯️ *A toggle that does nothing is a lie with a checkbox. We audited every
> Voice & Video control against reality.*

**Verified genuinely wired** (settings → WebRtcEffects LaunchedEffects →
CallManager/native): input/output device pickers, input/output volume,
NS/EC/AGC (`setAudioOptions` → live APM), OrbitZero AI NS, camera picker,
mirror video, background blur, bitrate, attenuation, SFU override.

**Found lying, and what happened to them:**
1. **Push to Talk** — selectable in Input Mode, did NOTHING. Now real: hold
   **Ctrl+Shift+Space** anywhere in the app → mic opens (native
   `LiveKitNativeAudioSession.setMuted(false)` via `toggleMute(true)`),
   release → restores the pre-hold mute state. Auto-repeat-proof, IDLE-proof,
   restored-state-proof (`pttPreHoldAudioEnabled`). Combo (not a bare key)
   precisely so typing in chat can't trigger it.
2. **"Toggle Camera Ctrl+Shift+V" keybind card** — advertised, never
   dispatched. Wired through `CallShortcuts.onToggleCamera`.
3. **Voice sensitivity slider (-100..0 dB)** — fed nothing (native AGC already
   auto-determines sensitivity). Deleted from the pane rather than kept as
   decoration.
4. **PTT "keybind" surface showing "Unassigned"** — there was no rebind UI and
   no consumer. Replaced with truthful fixed-combo text; Keybinds pane card
   updated to match ("Ctrl + Shift + Space (Hold)").

**Left on the table deliberately:** Start/Stop Stream shortcut (needs
source-picker flow first), Navigation-pane shortcuts (unaudited this round).
Rebindable PTT keys — deferred that day, SHIPPED the next: see H18 below.

## H18 — Rebindable push-to-talk (the deferred keybind, delivered)

> 🕯️ *"Rebindable PTT keys (focus-aware capture UI is its own project)."*
> It was. It isn't anymore.

**Shipped:** one new file (`ui/screen/settings/PttKeybinds.kt`) owns the
whole domain — a hand-checkable label↔Key table (letters, digits, F1–F12,
Space), `parsePttCombo` (Ctrl/Shift/Alt tokens + one key), and the capture
`Dialog` that grabs the next `onPreviewKeyEvent` combination live.

**The safety rule:** every combo MUST include Ctrl or Alt. Shift alone is
rejected because Shift+letter types uppercase in chat; a bare letter would
swallow normal typing app-wide (the handler sits on the root Window). The
capture dialog says so while you press.

**Wiring:** Voice & Video pane's keybind Surface is now clickable → capture
dialog → canonical string persisted via the pre-existing
`pushToTalkKeybind` setting (legacy `"Unassigned"` values normalize to
`Ctrl+Shift+Space`, so old installs migrate without touching storage).
Main.kt parses once per stored change into `PttBinding(ctrl, shift, alt,
key)` and matches the event dynamically — modifiers compared EXACTLY
(`==` per flag), not subset-matched, so "Ctrl+Shift+V" never leaks into a
"Ctrl+V" bind. The Keybinds pane row reads the same flow.

**Files:** `commonMain/ui/screen/settings/PttKeybinds.kt` (new),
`SettingsExtraPanes.kt`, `SettingsAppPanes.kt`, `jvmMain/Main.kt`.

---

## H19 — The Keybinds pane audit: two shortcuts wired, six lies deleted

**You see:** Settings → Keybinds advertises 13 shortcuts with pretty accent
chips. Grep for their handlers: **8 of them do not exist anywhere in the
codebase.** Quick Switcher Ctrl+K, Navigate Back/Forward Alt+←/→,
Mark Server Read Shift+Esc, Mark Channel Read Esc, Focus Message Box Tab —
pure decoration. Even a dead **"Add a Keybind"** button (`onClick = {}`)
sat under them like a tombstone for features never written.

**It is:** the same honesty debt as the settings panes — UI copied from
Discord's keybind list before any of it was real.

**Fix set (two wired, six deleted):**
1. **Start/Stop Stream Ctrl+Shift+S is now REAL.** New
   `CallShortcuts.onToggleStream`, registered by `LiveKitGroupCallSurface`
   via `DisposableEffect` — placed BEFORE the minimized early-return so
   stopping a share works from the compact pill too; opening the source
   picker stays expanded-only (same as clicking the button). Sharing?
   `stopScreenShare()`. Not sharing? Picker.
2. **Return to Active Call Ctrl+Shift+Alt+V is now REAL.**
   `CallShortcuts.onReturnToCall` registered app-wide in WebRtcEffects →
   clears `callManager.isMinimized` (guarded on not-IDLE). In Main.kt the
   Ctrl+Shift block splits Key.V on `isAltPressed`: plain = camera,
   +Alt = restore. Exact-flag matching from H18 keeps these unambiguous.
3. **Deleted without ceremony:** the five Messaging/Navigation fakes above
   plus the empty Navigation leftovers — no read-state system exists
   (`unread*` grep finds only badge toggles), no focus-requester anywhere,
   no nav history stack. A card advertising a nonexistent FEATURE is worse
   than no card.
4. Dead "Add a Keybind" button removed.

**Files:** `webrtc/CallShortcuts.kt`, `ui/screen/main/WebRtcEffects.kt`,
`ui/screen/call/LiveKitGroupCallSurface.kt`, `jvmMain/Main.kt`,
`ui/screen/settings/SettingsAppPanes.kt`.

**Smoke-test trap found this round:** the H13 WM_CLOSE ritual
(`taskkill /PID <pid>`) now hits **`TrayMessageWindow`**, not the app —
the unconditional `Tray {}` in Main.kt owns a hidden message window, and
taskkill posts WM_CLOSE to whichever top-level window it enumerates first.
The app ignores close-on-tray-window, so the ritual reports a false zombie.
Post to the VISIBLE window instead:

```powershell
# EnumWindows for pid → find visible "Orbit" hwnd, then:
[W.U32b]::PostMessage([IntPtr]<hwnd>, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero)
# poll ~12s → process tree gone == clean exit (verified this round)
```

---

# CIRCLE IV — APP LIFECYCLE

> 🕯️ *Apps ought to die when closed. Ours preferred to haunt — invisible,
> resident, sipping 4 GB from the tray like a polite poltergeist.
> Follow the rituals below or join them.*

## H21 — The rejoin hang: one rule broken five times

> 🕯️ *Eleven runs. The same symptom. Four correct fixes. The bug was not four
> bugs, and it was never where any of the four fixes were looking.*

**You see:** Hang up from a call, rejoin immediately, and the app sits on
**"Connecting"** forever. The mic works, the window is responsive, the UI is
alive. Nothing in the log ever says what went wrong.

**It is:** One rule, stated twice in comments at the top of
`orbit_livekit_audio.cpp` and broken in five places:

> **never `join()` a thread while holding `g_sessionMutex`**

Every one of the five joined a thread whose body opens with `SessionLock` to read
`g_audioTrack` / `g_room`. So teardown waited for a lock the joining thread was
itself holding. Five sites:

| # | Site | Stuck on |
|---|---|---|
| 1 | audio reader self-cleanup | the `join()` in the reader's own exit path |
| 2 | video reader self-cleanup | same, via `g_pendingVideoReaderJoins` |
| 3 | `~Room()` destructor | Room teardown needs the session state |
| 4 | `stopTxStats()` → `resetRoomLocked()` | TXSTAT thread opens each iteration with `SessionLock` |
| 5 | `nativePublishAudio` | **not on the teardown path at all** — mic re-publish |

**Why it took eleven runs:** each fix was correct, necessary, and **moved the
stall to the next waiter** rather than ending it. Six separate rounds blamed the
*callers* of `disconnect()`, and each removed one waiter while others waited too.
The symptom is identical whichever waiter you are on, so the trace never pointed
at the cause.

**The fix shape** is the same in all five: signal under the lock, **join with the
lock released**. The file already had a reaper thread for exactly this class of
join; sites 1, 2, 4 and 5 now hand their joins to it.

**Site 5 is why the reaper needed changing too.** `nativePublishAudio` must stop
the old TXSTAT thread and *immediately* start a new one, and assigning a fresh
`std::thread` over a still-joinable one is undefined behaviour — so the handle is
**moved** to a `g_txStatsRetiredThread` slot. The reaper used to join
`g_txStatsThread`, and after this change that slot can already hold the *new*
thread, so joining it would block the reaper for the entire next call.

**Prove it:** the line that ends the hunt is

```
disconnect step=bridge.disconnect returned atMs=<n>
```

It had **never appeared in any run**. Its absence is what kept pointing at a
waiter rather than at a caller. Verified in v0.2.2: `returned atMs=8`, `done
atMs=8`, four disconnects in a row, all single-digit milliseconds.

**Files:** `orbit_livekit_audio.cpp` — `stopTxStats` / `retireTxStatsThread` /
`joinTxStatsThread` / `drainPendingReaderJoins` / `ensureReaderReaper`,
`nativePublishAudio`, `JNI_OnUnload`.

## H22 — The degenerate frame stretched across the tile

> 🕯️ *The video was not frozen. The video was a postage stamp, enlarged to fill
> your screen.*

**You see:** A peer's video — usually a screen share — goes frozen, blocky, or
smeared while the call is otherwise healthy. Not black, not "connecting": the
last picture, stretched and ugly. Users report it as "the video is stuck" or
"their internet is bad".

**It is:** Element emits an **8x8 frame** when its share stops. Orbit accepted it
and published it, and the renderer — which allocates from `width`/`height` without
re-checking the arithmetic — made an 8x8 image and stretched it over the tile.
**This shipped in 0.2.1 and earlier.** Run 83 caught two of them on a live 1920x1080
share that had already delivered 88 good frames:

```
width=8 height=8 strideY=32 format=0 bytes=256 minEdge=16 accepted=88 rejected=1
```

**The trap, and it is the whole reason this entry exists:** the obvious fix does
not work. `bytes=256` for `strideY=32` is `32 * 8 == 256` — the frame is
**exactly self-consistent**. A buffer-size check (`buffer.size >= strideY *
height`) waves it straight through, and that was the first implementation, which
failed its own test.

**Fix:** a size floor of **16** on both dimensions, checked *first*, plus the
buffer check kept alongside it for a different fault (a caller under-allocating by
one byte). 16 is far below anything a capture device produces, so it cannot
reject a real stream. Rejections are counted per call and printed at teardown,
because a track that rejected every frame looks identical to one that delivered
none.

**Prove it:** `grep 'rejected degenerate frame' run.log`. The boundary is pinned
by tests at exactly 16 (accepted) and 15 (rejected) — a floor set too high does
not look like a bad tile, it looks like video that never works at all.

**Files:** `LiveKitRemoteVideoFrameStore.kt` (`updateFrame`, `MIN_FRAME_EDGE`),
tests in `commonTest`.

## H23 — One person announced eight times

> 🕯️ *The roster said eight participants. The room held one.*

**You see:** Nothing visibly wrong on screen. You find it by reading the log:

```
PARTLEDGER final UNBALANCED identities=@hellishsaa:matrix.org (+8/-0)
```

Eight connects, zero disconnects, for a peer who was in the room the whole time.

**It is:** **Five** independent native sites dispatch a participant-connected:

```
onParticipantConnected      onTrackPublished      onTrackSubscribed
connect-time roster walk    periodic rescan
```

Only the **rescan** consulted `g_announcedParticipantSids`, so a peer with three
tracks was announced once per track per source. A dedup gate at a call site
cannot work when there are six call sites and no shared funnel — the rescan's
check was a fifth opinion, not the rule.

**Fix:** the gate moved into `dispatchRemoteParticipantConnected`, where the
invariant belongs. Check-and-reserve in **one** critical section, not
check-then-insert: the sites run on the LiveKit FFI event thread *and* a tick
thread, and both could otherwise observe "not announced" and both call up. A
reservation is released if the upcall turns out to be undeliverable, so a missing
sink still gets retried rather than becoming a participant who never appears.

**The ledger then had to stop lying.** It counted *callbacks*, so "announced eight
times" and "eight participants" were the same number — which is the entire
question the ledger exists to answer. It now counts **state**: `present` is a set
keyed by **SID**, and `pending` is its size.

**Keyed by SID, not identity, and that is load-bearing in both directions.** A
genuine rejoin keeps the same identity and gets a fresh sid, so a sid key counts it
as the new arrival it is:

```
+connect           pending=1 dupConnects=0 present=1     <- @hellishsaa arrives
-disconnect        pending=0 dupConnects=0 present=0     <- leaves
+connect           pending=1 dupConnects=0 present=1     <- rejoins, NEW sid
+connect DUPLICATE pending=1 dupConnects=1 present=1     <- rescan repeats it
```

An identity key would collapse every rejoin after the first into a "duplicate" and
report a healthy churning call as **balanced**. A diagnostic that lies in the safe
direction is worse than one that is merely wrong.

Two follow-up bugs the live run exposed, both in the reporting:

- `report()` printed **sids** on a line labelled `identities` —
  `UNBALANCED identities=PA_NoTSngNzpkyh`. Nobody can look that up in Matrix. The
  line whose entire job is to name who is stuck named something un-actionable.
- The wording was wrong too. That peer rejoined and was **legitimately in the
  room** when the user hung up, so `UNBALANCED` described a correct state. It now
  says `STILL PRESENT` with the reason inline. A diagnostic that cries wolf on the
  healthy case gets skipped, and then it misses the real one.
- `report()` clears per-call state afterwards; `clear()` is called on paths that
  are not a call ending at all, so a carried-over tally repeats into the next call.

**Prove it:** `grep PARTLEDGER` in run 83 — 8 announcements for 2 real arrivals,
now 2, with `dupConnects=1` naming the one the gate suppressed.

**Files:** `orbit_livekit_audio.cpp` (`dispatchRemoteParticipantConnected`,
`unreserveParticipantAnnouncement`, rescan), `NativeParticipantLedger.kt`,
`LiveKitNativeRemoteVideoRenderer.kt`.

## H24 — The 0xC0000409 termination abort: native threads outlived the JVM

> 🕯️ *The demon was in the Bestiary. Filed as benign. It was not benign.*

**You see:**

```
> Process 'java.exe' finished with non-zero exit value -1073740791
```

immediately after

```
VM attach current thread failed
Failed to attach thread 24756
```

`0xC0000409` is `STATUS_STACK_BUFFER_OVERRUN` — a HotSpot **fail-fast**. It
happens at process exit, after the call is over and the UI is gone.

**It is:** `JNI_OnUnload` latched the shutdown flags but **joined nothing**. That
stops the *next* upcall and does nothing about a thread already inside one, or one
that outlives the library entirely. A LiveKit SDK thread then calls `getJniEnv` on
a VM that is tearing down, HotSpot refuses the attach, and the abort is HotSpot's.

**Why it was misfiled:** the Bestiary already listed `VM attach current thread
failed` as benign noise from "native workers attaching to a dying JVM". Run 82 had
the identical lines and **exited cleanly**. Same message, different outcome, which
is the definition of a diagnostic that has been read as reassurance rather than
evidence.

**Fix:** `JNI_OnUnload` clears the running flags and joins the TXSTAT thread.
Joining is safe **only here** — every join deferred elsewhere in this file was
deferred because the joiner held `g_sessionMutex`, which the joined thread needs
to finish. This function holds no lock at all, so the joins are immediate, and they
have to be: a detached thread reaching the VM during teardown is the thing being
fixed.

The reader reaper is **detached**, so it cannot be joined. It now checks its
running flag at the top of every iteration rather than only on the way out —
previously clearing the flag was silently ignored, because the loop condition never
looked at it. Detaching stays: making it joinable would reintroduce the exact
requirement the thread exists to avoid.

**Prove it:** `:app:run`, join a call, hang up, quit. The exit value should be 0.
**Not yet verified** — the fix is reasoned and unit-tested, but no run has
confirmed it since.

**Files:** `orbit_livekit_audio.cpp` (`JNI_OnUnload`, `ensureReaderReaper`),
and the Bestiary entry above, which was wrong.

## H20 - The two-key same map: why volume and mute were dead

> 🕯️ *Three people, one build, and each of them heard a different subset. The
> mixer was fine. The map was fine. The map was keyed two ways.*

**You see:** on a group call, some peers are audible and others are not, and it
CHANGES between runs. Per-participant volume sliders and mute-one-person do
nothing at all. The person who was fine last call is silent this one. Reported
as "Hamza can hear me, I can't hear Hamza; Talnz can't hear anyone but we can all
hear Talnz" — three mutually inconsistent-sounding reports that are one bug.

**It is:** ONE map, `participants_`, written under TWO different keys.

```
addParticipant()             participants_[trackSid]          <- LiveKit / group calls
applyParticipantGain()       participants_.find(identity)     <- every read
removeParticipant()          participants_.find(identity)
pushRemoteAudio()            participants_[identity]         <- legacy P2P
```

For a group call the queue was created as

```
stored   participants_["TR_AMwWxXt3pkqKK6"]
sought   participants_["@hamzamasudbutt:matrix.org:BzPLGdcHdw"]
```

Different strings. Every identity-keyed lookup missed, and every miss `return`ed
**in silence**.

**Why it presents as "I can hear SOME of them":** the miss is in
`applyParticipantGain`, not in the mix. `mixOneFrame` iterates `participants_` and
sums every queue at whatever gain that queue holds — which was always the default
`1.0f`. So audio genuinely reached the mixer (the log shows `inRms` spiking to
2717), it was simply never shaped, and audibility came down to which queue the
mixer happened to favour on join order. Nothing in that path produces an error,
a warning, or a log line.

**Why H15 looked fine in review:** H15 documents the gain stack as shipped and
working, and it is -- *keyed consistently, by identity*. The LiveKit writer was the
odd one out. Documentation of the intended design was correct; one call site had
drifted away from it. That drift is why this sat in a book full of fixed bugs.

**Fix:** `addParticipant` keys by `participantIdentity`, falling back to the track
SID only when the identity is genuinely unknown (an empty identity would never
match the UI's active-speaker event). Identity is also correct on the merits: a
participant publishing screen-share-with-audio mirrors their mic queue under the
SAME identity, and one key is what makes a single slider control both -- the
behaviour H15's share-tile ban depends on.

Both silent misses now log. `applyParticipantGain MISS` and
`removeParticipant MISS` print the identity plus every key currently in the map,
because "not found" is only actionable if you can see what IS there. A removal
that finds nothing is also exactly what leaves a queue alive for the rest of the
call, so that miss was the leak's natural early warning and it passed without a
sound.

**Prove it:** group call, two peers, drag one peer's volume to 0. That peer must go
silent AND the log must show no `applyParticipantGain MISS`. Any MISS line means a
key is still wrong somewhere. Then check the teardown tally:

```
PARTLEDGER final UNBALANCED identities=...(+3/-0)
```

`PARTLEDGER` (added alongside this) counted 5 connects against 0 disconnects in
the run that exposed this -- the same keying mismatch, seen from the other side:
a participant stored under a track SID can never be found by the identity-keyed
removal path, so it is never cleaned up.

**Files:** `jvmMain/cpp/orbit_livekit_audio.cpp` (`addParticipant`,
`applyParticipantGain`, `removeParticipant`, `pushRemoteAudio`).

**Lesson, added to the Codex:** a map read by identity in four places and written
by track SID in one is not a subtle bug — it is a bug that produces NO symptom in
any layer that reports errors. It surfaced only as *asymmetry between users*, which
is the least reportable symptom there is. **Whenever a lookup can miss silently,
make it log the miss with the keys it holds.** Silence is not a valid failure mode
for a lookup.

## H13 - The window-close zombie JVM

**You see:** Close the app; java.exe lives on in Task Manager eating 3–4GB.

**It is:** `minimizeToTray` settings flow defaulted TRUE → clicking ✕ only hid
the window (`windowRef?.isVisible = false`) and the entire app + call session
stayed resident in the tray. The "zombie" was the app, alive and well, invisible.

**Fix set:**
1. Default flipped OFF; tray mode opt-in (`SettingsManager.minimizeToTray`).
2. `CallShortcuts.onAppQuitRequested` hook registered in WebRtcEffects — fires
   `callManager.endCall()` if not IDLE so quitting mid-call publishes the
   MSC3401 leave instead of halt-killing it into a TTL ghost that confuses
   peers for hours.
3. Main.kt `quitOrbit()` local fun INSIDE the application{} scope:
   armForcedExitWatchdog (5s daemon → Runtime.halt) → hook → exitApplication().
   Wired to ✕/titlebar close and tray Quit.

The quit path, verbatim shape from `Main.kt`:

```kotlin
fun quitOrbit() {          // LOCAL fun inside application{} — see Kotlin trap below
    armForcedExitWatchdog()                             // 5s daemon → Runtime.halt(0)
    webrtc.CallShortcuts.onAppQuitRequested?.invoke()   // publishes MSC3401 leave if in call
    exitApplication()                                   // ApplicationScope extension
}
```

**Kotlin trap:** `exitApplication` is an ApplicationScope EXTENSION — a
top-level fun cannot resolve it. Keep quitOrbit local or pass the scope.

**Prove it — the WM_CLOSE ritual:**
```
taskkill /PID <appPid>        # NO /F flag == WM_CLOSE == clicking ✕
# poll ~12s: process gone? log shows endCall chain + leave published?
```
Note: killing the gradle background shell kills the tree but does NOT test the
app's own close path.

Benign during shutdown: `VM attach current thread failed` /
`Failed to attach thread NNNN` = native workers attaching to a dying JVM.

**Files:** `Main.kt`, `webrtc/CallShortcuts.kt`,
`ui/screen/main/WebRtcEffects.kt`, `repository/SettingsManager.kt`.

## Share pager & the icon forge (UI mines)

Not all wounds bleed audio. Two UI artifacts worth remembering:

- **Share pager** (`CallGrid.kt`): multiple simultaneous screen shares compete
  for ONE spotlight slot; wrap-around chevron arrows + an "N / M" counter chip
  (`ShareSpotlightBox` / `SharePagerArrows`). En route it killed a latent NPE:
  the old code did `nodes.find { !it.isScreenShare }!!` — two shares and zero
  cameras = guaranteed crash. Guards now handle empty lists; the page index is
  `remember { mutableStateOf(0) }` so it survives recomposition (CallGrid stays
  mounted), but ALWAYS re-clamp before indexing — `coerceIn(0, -1)` throws on
  an empty list.
- **OrbitIcons** (`ui/icons/OrbitIcons.kt`): hand-rolled SVG paths through
  `collectionIcon(name, originX, originY, paths)` — the builder viewport is
  **80×80 with an origin translation**, not Material's 24dp box. Author new
  icons natively at origin (0,0); if porting a Material path, scale by
  ×80/24 (the chevrons were made exactly this way).

## Data & storage map (for "why does it remember X")

| What | Where | File |
|---|---|---|
| Matrix session DB (sync, rooms, crypto) | `%USERPROFILE%\.orbit\trixnity.db` | `util/DatabaseBuilder.jvm.kt` |
| Credentials (accessToken/syncToken) | Windows registry Java prefs node `com/noobdev/orbit/credentials`, DPAPI-encrypted (`enc:v1:<b64>`); non-Windows AES-GCM w/ `.orbit/.credkey` | `repository/Settings.jvm.kt` |
| Media cache | `%USERPROFILE%\.orbit\media` | `util/MediaStoreProvider.jvm.kt` |
| Soundboard samples | `%USERPROFILE%\.orbit\sounds` | `livekit/SoundboardMixer.kt` |

Consequence: dev runs, packaged builds, and MSI-installed copies on the SAME
Windows user share one profile (instant login everywhere). Fresh machines get
a clean login screen. Legacy plaintext credential values migrate to encrypted
transparently on first read.

---

# CIRCLE V — PACKAGING AND RELEASE

> 🕯️ *Packaging: where code you tested becomes a binary you didn't.
> WiX materializes out of the internet; DLLs mutate byte-by-byte out of
> pure spite. Trust timestamps, never vibes.*

## The `buildFeatures` ghost

`buildFeatures.release = false` DOES NOT EXIST anywhere in Compose Desktop
1.7.x — not at application level, not inside nativeDistributions. It costs us
two failed script compilations to learn that release packaging runs **no
ProGuard tasks at all** (verify: `gradlew :app:createDistributable --dry-run`
— no proguard task in the graph). Nothing to disable. If shrinking is ever
wanted, the real knob is `buildTypes.release.proguard {}`.

## WiX comes from the plugin

No WiX install needed. `packageMsi` auto-downloads/unzips WiX
(`:downloadWix`/`:unzipWix` tasks appear in the graph). `createDistributable`
needs nothing extra.

## jpackage launcher anatomy

The packaged launcher is TWO processes named `Orbit.exe`: an ~8MB parent that
spawns the real JVM child carrying the window. WM_CLOSE-test the CHILD pid.
Native `webrtc-deps` DLLs ship INSIDE the app jar (`app-jvm-*.jar!
/webrtc-deps/…`), not as loose files.

## The packaged app CANNOT LOG — the launcher has no console

`Orbit.exe` is a **GUI-subsystem binary**: PE header subsystem field = 2
(`WINDOWS_GUI`), because `windows { console = true }` is not set. A GUI-subsystem
process launched from Explorer has no console, so `System.out`/`System.err` go
nowhere and the OS discards them.

Consequence: **every `println` in this codebase — all 300+ legacy sites and every
`OrbitLog.d/w/e` — produced NOTHING for a user running the MSI.** Not "logs are
hard to find". Nothing. A bug report from a tester carried no evidence, which is
why several demons in this document took rounds to pin down.

Verified by reading the subsystem field directly:

```powershell
$b=[IO.File]::ReadAllBytes("$exe"); $pe=[BitConverter]::ToInt32($b,0x3C)
[BitConverter]::ToUInt16($b,$pe+0x5C)   # 2 = GUI, 3 = console
```

**Do not "fix" this with `console = true`.** That attaches a console window to a
messenger people keep open all day. The fix is `util.installOrbitLogFile()`, a
rolling sink at `%LOCALAPPDATA%\Orbit\logs\orbit.log` (`ORBIT_LOG_DIR` overrides),
4 MB × 3 files. Two non-obvious things, both found by RUNNING it:

- **Open eagerly at startup, never lazily on first write.** DEBUG is off by
  default, so a session that logs no WARN/ERROR writes nothing — and a
  lazily-created file means that session leaves NO log. That is precisely the
  session worth a log ("it just closed", "nothing happens when I press Join").
- **`PrintWriter.println` does not flush.** The startup banner sat in the
  `BufferedWriter` and the eagerly-created file stayed 0 bytes. `flush()`
  explicitly, or the log looks broken.

Redirection still works as a manual escape hatch, and needs `start /wait`
because cmd does not wait for a GUI app:

```
start /wait "" "%LOCALAPPDATA%\Orbit\Orbit.exe" > "%USERPROFILE%\Desktop\orbit-log.txt" 2>&1
```

Gotcha: only lines routed through `OrbitLog.emit` reach the FILE. Raw `println`
sites hit the console filter and are deliberately not duplicated — routing them
into the sink double-writes every line. Use `OrbitLog.d/w/e` for anything you
need to read back from a user's machine.

## A duplicate LazyColumn key is a 3-second dialog, not a static error

`Key "@user:server" was already used. If you are using LazyColumn/Row please make
sure you provide a unique key for each item.` — every row in a keyed lazy list
uses `it.id` (a Matrix user ID) as its key, so a roster that repeats one ID throws
out of the lazy layout.

It presents as **a tick every three seconds**, which is not a sound bug at all:
it is the Windows error-dialog beep. Compose throws, a dialog appears, the user
dismisses it, and `MatrixCallsRepository`'s roster poller re-emits the member
list on `delay(3000)` (`ensureRosterPoller`) — so the throw returns three seconds
later, for the length of the call. Nothing in the audio path is involved.

**Both feeds were already supposed to be unique**, which is why this is a trap:

- room roster — `getJoinedMembers().joined.keys`, a Map;
- call roster — `currentMembers.none { it.id == sender }` in
  `observeCallMembers`.

One of them handed over a repeated ID anyway, from data only that call had. So
the guarantee lives in `dedupeByUserId`, at the one point rows are built, rather
than in each source — a fix per-source just moves the bug to the next list. It
logs WARN with the offending ids, because the malformed data is upstream and must
stay visible. The header count reads `sections.sumOf { it.users.size }`, not the
input size, so a duplicate cannot inflate the badge above the rows on screen.

`MemberList` (the `groups` variant in `MemberList.kt`) is **dead code** — nothing
calls it. Its key was `it.id` too. If you touch member lists, do not "fix" that
one; delete it or wire it up deliberately.

## msiexec / upgrade-version mismatch

`app-version` in `app/Orbit.cfg` and `app/.jpackage.xml` comes from
`nativeDistributions.packageVersion`. A stale build dir can carry an OLD
`app-version` (0.1.5 under an `Orbit-0.2.2.msi` filename) — and with a fixed
`upgradeUuid`, **Windows Installer decides upgrades from ProductVersion, not the
filename**, so a mismatched MSI can refuse to upgrade in place or duplicate the
install. If the version looks wrong, `rm -rf app/build/compose/binaries` and
re-run `packageMsi`; the field is regenerated, never edited by hand.

Also: `packageVersion` must be bumped per release or the new MSI collides with the
existing release asset on the tag.

## windows{} DSL notes

- No `menuGroup` ⇒ shortcut lands under Start Menu → **Unknown** folder.
  Always set `menuGroup = "Orbit"`.
- `perUserInstall = true` ⇒ double-click install, NO UAC — mandatory for
  sending to testers.
- Fixed `upgradeUuid` ⇒ future versions upgrade IN PLACE.
- No custom icon yet ⇒ default Java cup. Wire `iconFile` when an .ico exists.

## msiexec via scripts

Don't embed escaped quotes around args in `powershell -Command` — bare
space-free paths work; `\"` mangling yields exit **1622** (log-open failure
masquerading as install failure). Silent ops verified working:

```
msiexec /i <msi> /qn /norestart     # per-user install, exit 0, no elevation
msiexec /x <msi> /qn /norestart     # uninstall, leaves zero trace
```

## The DLL churn ritual

Every compile/test/run rewrites `orbit_livekit_audio.dll` /
`orbit_livekit_video.dll` in resources with identical sizes and
nondeterministic bytes. Before committing:

```
git restore -- app/src/jvmMain/resources/webrtc-deps/orbit_livekit_audio.dll ^
               app/src/jvmMain/resources/webrtc-deps/orbit_livekit_video.dll
```

Unless you actually changed native `.cpp` sources — then the churn IS the change.

## Build internals worth knowing

- **`compileJniBridge`**: finds `vcvars64.bat` across known install paths,
  else walks `D:\` up to depth 6 hunting for one; CMake + MSVC
  `MultiThreadedDLL`; copies built DLLs AND LiveKit SDK runtime DLLs into
  `src/jvmMain/resources/webrtc-deps/`. Falls back to prebuilt DLLs if VS is
  missing; throws only if neither exists. `jvmProcessResources` depends on it.
- **`runLiveKitTonePublisher`**: headless tone publisher exe for exercising
  the audio pipeline without launching the UI. Needs
  `ORBIT_LIVEKIT_URL`/`ORBIT_LIVEKIT_TOKEN` env vars or `-PlivekitUrl=` /
  `-PlivekitToken=` props.
- The JNI bridge task has NO up-to-date checking — it rebuilds (and re-fetched
  the SDK zip) every invocation. That's why builds are slow and why the DLL
  churn ritual exists.

---

# THE BESTIARY — benign log noise

> 🕯️ *Not every monster bites. Some are just ugly. Learn the difference
> before spending a candle on them.*

Do NOT file bugs against these:

| Log line | Actually |
|---|---|
| ~~`VM attach current thread failed` / `Failed to attach thread NNNN`~~ | **NO LONGER BENIGN — see [H24](#h24--the-0xc0000409-termination-abort-native-threads-outlived-the-jvm).** It used to be filed here as harmless shutdown noise, and run 82 had these lines and exited cleanly, which is exactly how a diagnostic becomes reassurance instead of evidence. It now precedes a `0xC0000409` fail-fast. **If you see it, check the exit code before dismissing it.** |
| `WARN configureE2EE(true) failed:` (empty msg) | request raced session creation; nativeConfigureE2EE returns stale-event silently; recovery lines follow |
| `local participant metadata update failed: NotAllowed` | token lacks canUpdateOwnMetadata |
| `Gradle build daemon disappeared unexpectedly` after closing the app | benign artifact of `:app:run` wrapping when the app exits |
| MIXSTAT `starved=` > 0 | jitter existed; only holes >~20ms matter (see BUG-D) |
| raw JB delay numbers in the thousands of ms | cumulative lifetime sums, divide by emitted_count |

---

# THE TOOLKIT — diagnostic rituals that work

> 🕯️ *These rituals were paid for in hours you will never get back.
> Use them freely — the lock is already picked.*

1. **WM_CLOSE test**: `taskkill /PID <pid>` WITHOUT `/F`. Poll 12s. This is
   the ✕ click. Never trust shell-tree kills as close-path tests.
2. **Log epoch mining**: bracket a repro between visible marks; compare
   ordering across hops (publish status lines vs failure lines vs publish
   success). Ordering violations ARE the bug (see H12).
3. **STATS fields**: CallPathStats STATS lines carry mixer depths, RXSTAT
   (incl. jbTarget/Avg/Min), TX counters, `vid.remote/vid.share/camPrev`
   MB/s throughput, `heap used/max/pendingFin`.
4. **Event-log check before diagnosing ghosts**: Windows Application log for
   crash events. A cmd-mangled multi-line PowerShell pipeline once made the
   app seem to self-exit mid-test — zero crash events, retest with simple
   single-purpose commands before chasing phantoms.
5. **cmd.exe reality**: run_shell_command is cmd.exe — wrap PS in
   `powershell -NoProfile -Command`; bare space-free paths beat escaped
   quotes; `findstr` exits 1 on no-match (not an error); `ping -n N` is the
   sleep.
6. **Native truth**: DLL timestamps over findstr output; SDK headers under
   `app/build/jni/_deps/livekit-sdk/.../include/livekit/`.

### Log-grep recipe card

| Question | Grep for |
|---|---|
| Who ended the call? | `endCall invoked` (attribution chain follows) |
| Did the settle check run? | `settle check:` |
| Nudge fired? | `leave+rejoin` / `forcing membership leave+rejoin attempt=` |
| Raw inbound olm arriving at all? | `raw olm to-device #` |
| Sync loop alive? | `sync probe alive batch=` |
| Peer key installs | `e2ee participant key installed` / `peer key sender=` |
| Native E2EE state per participant | `EncryptionState` codes: New=0 Ok=1 EncryptionFailed=2 DecryptionFailed=3 MissingKey=4 KeyRatcheted=5 InternalError=6 |
| Mixer health | `MIXSTAT` (depths, `starved=`, `drops=`) |
| RX jitter buffer | `RXSTAT` (`jbTarget/Avg/Min`) — divide by emitted_count! |
| Watchdog verdicts during join | `GiveUp` / `Join aborted` / `noteJoiningProgress` effects |
| Leave actually published on quit? | `invoke$quitOrbit` → `invoke$closeRequested` → endCall chain |

---

# UNFINISHED BUSINESS

> 🕯️ *The demons not yet baptized. They know where you sleep. Visit them
> before they visit you.*

| Front | Status | Where to start |
|---|---|---|
| H6 resubscribe recovery | SHIPPED in native stats watcher — guarded one-shot unsubscribe/resubscribe per reader | Fires when jbTargetMs sustains >400ms (3 consecutive polls) with 20s warmup, 90s cooldown, 4-lifetime cap, 120s garbage cap; grep `JBWATCH firing resubscribe` / `JB-RESUB` in run logs to see it work |
| H8-V3 latecomer nudge | Shipped + unit-tested, needs LIVE multi-peer verification | Join as latecomer to a busy encrypted room, measure deaf window |
| H8-TX "peers can't hear us" | **SOLVED in v0.2.2** — it was key ROTATION on three bogus departure signals, not key distribution. Entry rewritten; the old theory is kept as a monument | Read the v0.2.2 section under H8-TX before touching key delivery |
| H21 rejoin hang | **SOLVED in v0.2.2** — one rule broken in FIVE places, not five bugs | grep `disconnect step=bridge.disconnect returned` in a run log; the line appearing is the whole proof |
| H23 8x participant announces | **SOLVED in v0.2.2**, verified live in run 83 — 8 announcements for 2 real arrivals, now 2 | grep `PARTLEDGER`; `dupConnects` counts what the gate suppressed |
| H22 degenerate video frames | **SOLVED in v0.2.2**, verified live in run 83 — 2 real 8x8 frames caught on a 1080p share | grep `rejected degenerate frame` |
| H24 0xC0000409 shutdown abort | Fixed in v0.2.2, **not yet live-verified** — launch once and confirm the exit code | `:app:run`, join, hang up, quit; read the exit value |
| H21 fifth join site (mic publish) | Fixed in v0.2.2, **not yet live-verified** — stalls on mic re-publish, not on hangup | Mute/unmute the mic repeatedly mid-call and watch for a stall |
| H8-V3 latecomer nudge still needs LIVE multi-peer | as above | — |
| H10 random-close attribution | Instrumentation live, awaiting next incident | Grep `endCall invoked` chain from the run log |
| Remote MIC-muted badge on tiles | SHIPPED ([H17](#h17--the-remote-mic-badge-livekit-track-truth-not-membership-lies)) — native LiveKit track-mute events + subscribe-time initial state; tiles AND sidebar rows | Verify live: mute in one client, badge appears instantly on the other; late joiner sees already-muted peers correctly |
| Deafen-of-others badge | IMPOSSIBLE remotely by protocol design | Deafen is local output muting; no wire signal exists. Do not promise it |
| "Mic suppression not working" reports | Explained: suppression is the SPEAKER-side client's TX setting (their NS/EC), not yours to toggle | NS/EC/AGC toggles now SHIP in Voice & Video (Voice Processing section); RX-side suppression remains out of scope |
| Long-session partial deafness field reports | Hardened (H8-V4) + fully instrumented; await reproduction logs | Grep `ERROR [CallMediaKeyExchange] olm encryption failed` + nudge lines; if clean, suspect sendToDevice silent loss / OTK pool on SERVER side |
| H11 RAM under load | Capped + instrumented, verify under 3-person+share load | Read STATS fields; if pendingFin grows → bitmap reuse/dispose path |
| Cold-decode storm | Throttled (H11), not eliminated | Fires at first peer-key install; revisit if latency regressions return |
| D1 AEC/AGC speakers test | Untested | Native APM flags (`orbit.apm.*`) exist for this |
| orbit→element residual TX glitch | Open polish item | Element hears slight artifact; direction-specific |
| Deploy / Docker validation | Untouched | MatrixRTC focus/livekit broker deployment checks |
| packI420 direct-buffer reuse + share capture fps cap | Optimization backlog | `LiveKitLocalScreenShareTrack` allocates fresh direct buffers per frame |
| App icon | No .ico exists | Author one, wire `windows { iconFile = ... }`; default Java cup shows today |
| Rebindable PTT key | SHIPPED (H18) — click-to-capture dialog in Voice & Video; combo must include Ctrl or Alt so chat typing stays safe; Main.kt matches the parsed binding dynamically; legacy "Unassigned" values fall back to Ctrl+Shift+Space | Capture supports letters/digits/F1–F12/Space; extend `pttKeyTable` in PttKeybinds.kt for more keys |
| Keybinds-pane honesty audit | SHIPPED (H19) — Ctrl+Shift+S stream toggle + Ctrl+Shift+Alt+V return-to-call wired; Quick Switcher/Navigate/Mark-Read/Focus-Message cards deleted (features don't exist); dead Add-a-Keybind button removed | Build the actual features first, then re-add their cards |
| Smoke-test WM_CLOSE ritual | AMENDED (H19) — taskkill hits hidden TrayMessageWindow now; post WM_CLOSE to the VISIBLE "Orbit" hwnd via EnumWindows+PostMessage | See H19 snippet |
| "Navigation" polish (user ask) | AMBIGUOUS — awaiting user's meaning | In-app navigation vs nav-while-in-call vs something else; ask before building |
| GitHub Release publication | **DONE — v0.2.2 published**, repo private. Tag `v0.2.2` → `b3af619` | `gh release create v0.2.2 <msi> --verify-tag`. Note `--cleanup-tag` deletes the LOCAL tag too; re-tag before recreating |
| `isNativeE2eeArmed()` has no callers | Dead code, never wired | `LiveKitNativeMediaSession.kt:873` — either call it from the UI or delete it |
| Dead UI controls | Known, untouched — deliberately left for the user | Ducking Strength (`CallControlsBar.kt:288-327`, `SettingsExtraPanes.kt:284`), Attenuation (`WebRtcEffects.kt:207`) |
| `disconnect step=` tracing | Left IN on purpose — 6 lines per hangup, and it is the diagnostic that ended the eleven-run hunt | Remove only once the rejoin hang is trusted in the field |
| H24 0xC0000409 shutdown abort | Fixed, **awaiting live verification** | `:app:run` → join → hang up → quit; read the exit value |
| H21 mic-publish join site | Fixed, **awaiting live verification** | Mute/unmute the mic repeatedly mid-call; watch for a stall |
| MSI upgrade path in the wild | Mechanism verified via upgradeUuid | Bump packageVersion, install over 0.1.0, confirm in-place upgrade |

---

# THE PARITY ROADMAP

> 🕯️ *"There is sooo much missing — do research and make a really long
> list of all backend and UI features and implement em all."*
> Command received. Here is the list, researched against how Element Call
> actually works (matrix-js-sdk `MatrixRTCSession` / `RTCEncryptionManager`,
> read from source) and Discord's voice surface. ✅ = already shipped in
> Orbit (see H-entries). Everything else is ordered by impact-per-effort.

## A. CALL / VOICE — BACKEND & MEDIA

- ✅ E2EE media keys: push-based distribution, per-device ledger, 10s
  convergence sweeper, latecomer nudge ≤11s (H8/V2/V3/V4) — EXCEEDS Element
  (which has no key-request mechanism at all; push-only + membership-driven).
  **v0.2.2: distribution was never the bug. Rotation was — see the rewritten
  [H8-TX](#h8-tx--tx-dead-peers-cant-hear-us). Rotation now fires on a CONFIRMED
  departure only.**
- ✅ Partial-deafness root causes: sticky-roster lies → LiveKit-truth sweeps;
  per-device send isolation; retry-unconfirmed devices.
- ✅ JB resubscribe recovery on sustained jitter (H6).
- ✅ Join watchdog vs slow joins; zombie-session teardown (H12).
- ✅ Speaker detection native→UI glow (H16); remote mic-muted badges (H17).
- ✅ Per-participant local volume/mute via native mixer gains (H15).
- ✅ Per-tile connection quality — native stats watcher samples
  `getStats()` every ~5s per RemoteAudioReader (jitter, RTT, interval packet-loss
  delta) → `onRemoteConnectionQuality` JNI channel → `ConnectionQualitySample`
  StateFlow → 3-bar signal badge (green/yellow/red) top-right of every remote
  call tile; hidden when no measurable sample yet.
- **Connection-stats dialog** — the "Voice Connection" debug panel: current
  SFU url, ICE state, bitrate in/out, jitter, packet loss, codec. Data source
  exists (STATS line); needs a settings/call-menu pane rendering it live.
- **Device hot-swap mid-call** — `setAudioInputDevice` etc. exist, but verify
  a LIVE call re-negotiates without drop; add UI affordance from the call
  device menu (Element re-negotiates via m.call.negotiate).
- ✅ Raise hand — custom `io.orbit.raise_hand` room event (call_id + raised
  bool) → `CallEvent.HandRaised` → CallManager.raisedHands; yellow Raise Hand
  button in the shared CallControlsBar utility group toggles own hand, amber
  PanTool badge on every raised participant's tile (local tile included).
  Hands clear on call end.
- ✅ Spotlight / pin participant — right-click a tile → Pin; forces Focus-style
  spotlight even in Grid mode, beats paged shares in slot priority.
- ✅ Screen-share resolution/FPS picker — ScreenShareDialog now has Quality
  (720p/1080p/1440p/Source) and Frame rate (15/30/60) pill rows; selection is
  persisted to the EXISTING screenShareQuality/screenShareFps settings on
  source pick, which both capture paths already read at startScreenShare
  (P2P: VideoDesktopSource.setMaxFrameSize/setFrameRate; group: same capturer
  + armScreenSharePublish fps → native videoEncoding.max_framerate). Limits:
  applied at share START only (changing mid-share needs stop/restart); group-
  path resolution cap comes from the shared capturer, native publish takes
  dims lazily from the first frame.
- **Simulcast/bandwidth adaptation** — LiveKit supports it natively; verify
  dynacast enabled; low priority while group sizes stay small.
- **Custom image virtual background** — only Blur wired today
  (`videoBackground` setting); engine supports images.

## B. CALL / VOICE — UI

- ✅ Mini-pill floating bar with mute/leave/drag/restore (H10).
- ✅ Fixed double-pill bug when navigating during a call (embedded surfaces no
  longer render their own minimized pill; popup owns it).
- ✅ Right-click context menu on tiles: volume slider %, Mute-for-me,
  Reset volume (this round).
- ✅ Context-menu additions: "Copy user ID" copies the tile's participant
  identity to the clipboard (Compose LocalClipboardManager); share tiles now
  say "Mute stream"/"Unmute stream" instead of "Mute for me" for the SAME
  local-gain action. Limits: copied ID is the LiveKit identity space string
  (what tiles/media use), not necessarily the raw Matrix mxid; no paste-
  confirmation toast beyond the menu closing.
- ✅ In-call device switcher quick menu — the chevron next to mute already
  opened AudioDeviceMenu (mic input + speaker output pickers, both call
  paths); this round added the missing CAMERA section: enumerates
  getVideoCaptureDevices() when opened, selection persists via the
  cameraDevice setting which WebRtcEffects already observes and hot-switches
  mid-call (setVideoCaptureDevice), check-marked current device. Limits:
  opens on click (not hover — click is Discord's actual behavior too);
  camera hot-switch relies on the existing native setVideoCaptureDevice path
  (same mechanism as the Settings pane, runtime-unverified with multiple
  cameras plugged in).
- ✅ Ringing / incoming-call UX for DM calls — pulsing overlay with
  Accept/Voice/Decline (CallScreen) now reachable from ANY screen: the app-level
  popup no longer hides while viewing the call's own DM channel (P2P calls have
  no embedded surface). Decline sends `m.call.hangup`; invite expires per
  lifetimeMs; procedural 440+480 Hz ringtone loops while ringing
  (`setCallRinging`, honors the "Incoming call ringtone" setting).
- ✅ Call notifications — system tray balloon on INCOMING calls ("<caller> is
  calling…") and on MISSED calls (invite expired or caller hung up while
  ringing; answering suppresses it). Fires only while the main window lacks
  focus (AWT WindowFocusListener) — the in-app Accept/Voice/Decline overlay
  covers the focused case. Honors the existing Desktop-notifications toggle.
  Implementation: expect/actual `showCallNotification` (LiveKitWrapper pair)
  → jvmMain `util.CallNotifier`, which reuses the TrayIcon Compose Desktop
  already registered (no second tray icon) and no-ops when no tray exists;
  caller display names resolve async from room members at invite time
  (`webrtc.IncomingCallRegistry`, mxid-localpart fallback). Limits: no
  taskbar flash, notification is informational only (clicking the balloon
  doesn't focus the app), P2P/group invites both covered via call-state
  transitions.
- ✅ Soundboard — audit found this fully wired and the pending line stale.
  MusicNote toggle in the controls bar (both P2P CallScreen and
  LiveKitGroupCallSurface) opens a SoundboardPanel overlay: 12 built-in
  effect tiles (procedurally synthesized — applause/laugh/airhorn/drumroll/
  party/wow/nice/ding/bruh/music/meow/quack), custom sounds added via a file
  picker into SoundboardMixer, volume slider. Tiles route through
  CallManager.playSoundEffect → livekit.playSoundEffect → SoundboardMixer.
  Limits: playback is LOCAL ONLY — you hear the effect mixed into your own
  output; nothing is published to the call as a second audio track, so other
  participants don't hear it. Custom sounds are session-only (in-memory
  samples, no persistence).
- ✅ Join/leave + deafen sound settings now REAL (were write-only) — new
  procedural effects in SoundEffects: "join"/"leave" two-tone chimes fired on
  self connect/disconnect AND on remote-participant count changes during a
  connected native call (LiveKitRemoteVideoFrameStore.remoteParticipants diff);
  "deafen"/"undeafen" short sweeps on local deafen-state changes. Each honors
  its existing Voice & Video toggle (joinLeaveSound / deafenSound). Limits:
  peer-arrival blips need the native LiveKit path (P2P ADM calls only get
  self connect/disconnect sounds), and a rapid disconnect right after a peer
  leaves may double-fire the leave blip (participant-reset ordering).
- ✅ PiP window — separate always-on-top Compose window (jvmMain
  `ui/screen/call/CallPipWindow.kt`, hosted from Main.kt next to the Tray)
  that appears only while a call is CONNECTING/CONNECTED AND the main window
  is minimized or tray-hidden; 320×190 undecorated, bottom-right. Content:
  screen-share frame first, else the active speaker's camera track
  (`liveKitActiveSpeakers()` matched against
  LiveKitRemoteVideoFrameStore.activeTracks/framesByTrack), else the latest
  flowing remote frame, else an audio-only card with participant count;
  mute/deafen badges plus a hang-up button overlay it. Clicking anywhere
  restores/focuses the main window (shared restoreMainWindow() also used by
  the tray menu); ✕ dismisses until the next call; auto-hides when the main
  window comes back or the call ends. CallManager reaches Main.kt via the new
  snapshot-backed `webrtc.ActiveCallBridge` registered in WebRtcEffects.
  Limits: fixed position (not draggable), no call timer/label on video,
  active-speaker matching assumes LiveKit identity space (falls back to the
  latest camera frame if it misses), and the window takes OS focus when
  clicked like Discord's does.

## C. MESSAGING

- ✅ Typing indicators — `observeTyping` flow → "X is typing…" overlay under
  the message list, display names mapped from room members.
- ✅ Read receipts (display) — `RoomUserStore.getAllReceipts(roomId)` observed
  per room; own messages get a Discord-style "Seen ✓✓" row once another
  member's read receipt reaches or passes them. (Send side existed: onSeen →
  setReadMarkers.)
- ✅ Unread separator + jump-to-unread v1 — the shell records each room's
  first message that arrived while it was away this session (the same filtered
  arrivals stream as the badges, so mute/mentions modes and own-message /
  edit / reaction exclusions apply); markers survive OPENING the room (that's
  when the separator shows) and are dropped when you leave it again.
  MessageList renders a Discord-style "NEW" divider above that message's
  group plus a floating "Jump to unread" pill (stacked over the scroll-to-
  bottom FAB, hidden while the marker row is visible) that scrolls the list to
  it. Limits: session-local only (no persisted read markers, no offline catch-
  up — a fresh app launch starts clean with no separator), the marker clears
  on LEAVING the channel rather than on scrolling past it, and the
  Mark Server/Channel Read actions this was expected to unblock remain future
  scope.
- ✅ Message actions — the hover toolbar's dead buttons are real: React opens
  an 8-emoji quick picker (duplicate-guarded per user) → `sendReaction`
  annotation; Edit (own text only) opens a dialog → `m.replace` edit; More →
  Copy Text + Pin/Unpin + Delete with confirm → redact. (The dead Reply button
  returned as a real feature in the replies round below.)
- ✅ Replies v1 (`m.in_reply_to`) — hover-toolbar Reply sets a replying-to
  state; an accent-bar preview strip ("Replying to <name>" + snippet, X to
  cancel) sits above the input bar and intercepts the next send →
  sendReplyMessage wraps the body in RelatesTo.Reply/ReplyTo. Incoming replies
  render via ReplyQuoteBlock: parent event resolved through getTimelineEvent
  for quoted sender + body snippet (≤200 chars), graceful empty-quote fallback
  when the parent isn't in local store. Threads remain future scope.
- ✅ Reactions rendering + adding — chips already aggregated per emoji; picker
  adds `m.reaction` annotations.
- ✅ File/image/video/audio upload + rich previews — audit confirmed this
  shipped earlier and the pending line was stale. Sending: input-bar
  pickers → onSendAttachment → uploadMedia then typed m.image/m.file/m.audio
  events (size recorded; images/videos keep width/height hooks). Rendering:
  images via Coil AsyncImage against the authed
  `/_matrix/client/v1/media/download` endpoint (singleton ImageLoader's ktor
  client attaches the Bearer token to /_matrix/ URLs), pending-upload
  placeholder while url is blank/upload://; videos show sender-provided
  thumbnails with an in-app NativeVideoPlayer play overlay; audio gets a
  waveform player card; files get type-aware icon cards (pdf/zip/media/generic)
  with name · size · mime. Download affordances open the media URL in the
  system browser. Limits: no native save-to-disk dialog, no in-app image
  lightbox/zoom, Orbit's own uploads don't generate thumbnail blobs (video
  posters only render when another client supplied one), stickers render as
  fixed 128dp images.
- ✅ Markdown/rich-text rendering — `formatted_body` (org.matrix.custom.html)
  now renders via a lightweight tag parser (RichText.kt): bold/italic/underline/
  strike, code + pre blocks, blockquote, headings, lists, links (clickable via
  LinkAnnotation), HTML entities, unknown tags stripped, `<mx-reply>` dropped.
  Plain bodies get markdown-lite (**bold**, *italic*, `code`, ~~strike~~) +
  autolinked URLs.
- ✅ Mentions (highlight side) — @room and @member pills on both render paths;
  matrix.to anchor mentions pill-styled; pills targeting the local user get the
  bright highlight. Push-notification side still pending.
- ✅ Message search (room-scoped) — header Search button is live: toggles a
  search bar under the header that filters the loaded conversation in place
  (case-insensitive body/username match) with a result count; X clears and
  closes. Server-side /search across history still future scope. HeaderIconBtn
  also gained an active-tint state (used by Search; ready for Pins).
- ✅ Pinned messages — real `m.room.pinned_events` state event: More-menu
  Pin/Unpin (OrbitMessagesRepository.pinMessage read-modify-write via typed
  PinnedEventsEventContent), pinned ids fetched per channel load and marked on
  messages (isPinned), header Pins button toggles a Pinned Messages side panel
  (username/snippet/time rows + Unpin; pins from other clients like Element
  show up too). Interop-compatible with Element.
- ✅ Slash commands — `/me <action>` sends a real `m.emote` event
  (sendEmoteMessage → trixnity emote() builder; incoming emotes/notes now map
  to Emote/Notice content instead of Unsupported, rendered as italic "* action").
  `/shrug` appends ¯\\_(ツ)_/¯. Bare `/me` sends nothing. Unknown `/foo` passes
  through as plain text.

## D. NAVIGATION / APP SHELL

- ✅ Back/forward navigation history stack (Alt+←/→) over selectedChannelId
  transitions — small state machine in OrbitApp.
- ✅ Quick Switcher Ctrl+K — fuzzy channel jump dialog across all servers
  (Keybinds card restored as real).
- ✅ Unread badges v1 — every synced chat message funnels through a new
  globalMessageArrivals SharedFlow (MatrixContext) exposed as
  observeIncomingMessages; OrbitApp keeps a session-local per-room unread map
  (own messages, edits, reactions and already-open channels excluded; counts
  clear when the channel opens), aggregates per server via a channel→server
  index, and surfaces red count pills (99+ cap) on ServerRail home/server
  icons, ChannelList channel rows and non-lite DM rows. Limits: counts are
  session-local only (no persistence, no offline catch-up — the stream starts
  at "now"); lite-mode WhatsApp DM rows have no badge yet.
- ✅ Presence/status v1 — profile popover's status pill is now a real picker
  (Online/Idle/Do not disturb/Offline): selecting calls setPresence (Matrix
  online/unavailable/offline; DND maps to unavailable since spec lacks it) and
  persists the choice via SettingsManager.myStatus, which OrbitApp re-applies on
  startup/change and mirrors into the drawer bar + chat surface. Member-list
  rows observe live presence per visible user (dots/name dimming track the
  homeserver; section headers regroup on next member refresh). Custom status
  TEXT still missing (needs account-data convention for cross-client visibility);
  DM-list dots still placeholder. Settings "Presence Settings" pane chips
  (idle timeout etc.) remain cosmetic.
- ✅ Profile editing — already real (audited, not rebuilt this pass):
  Settings → My Account has avatar click → OS image picker →
  `uploadAvatar` (media upload + setProfileField AvatarUrl) and Edit Profile →
  name field → `updateDisplayName`, both refreshing from getCurrentUser after
  save. Caveats: uploads are labeled PNG regardless of picked format; other UI
  surfaces pick up the change on next reload; no inline error surface on failure.
- ✅ Member list role grouping + moderation basics v1 — roles snapshot per
  member-list load via new getMemberRoles (trixnity UserService getPowerLevel /
  canKickUser / canBanUser / canSetPowerLevelToMax; PowerLevel.Creator maps to
  100). When any member has PL > 0 the panel groups ADMIN (≥100) / MODERATORS
  (50–99) / MEMBERS with presence-ranked rows and Admin/Moderator pills;
  otherwise legacy presence sections (covers DMs/rooms without a PL event).
  Hover ⋯ per row offers Kick / Ban (confirm dialog) / Promote to Moderator
  / Remove Moderator, each gated by server-truth flags and hidden on your own
  row; kick/ban call api.room.kickUser/banUser, power-level edits are
  read-modify-write of m.room.power_levels (raw state GET + typed sendStateEvent)
  guarded client-side by canSetPowerLevelToMax. Limits: no unban UI (banned
  users aren't in the joined list), no arbitrary PL values (0↔50 toggle only),
  failures surface silently except a log line.
- ✅ Notification settings per room v1 — SettingsManager gains
  roomNotificationMode(roomId)/setRoomNotificationMode ("ALL"/"MENTIONS"/"MUTE",
  persisted locally); the previously dead header Notifications button now
  cycles the mode for the open channel (bell swaps to slashed icon and tints
  accent when not ALL) and the shell's unread-badge collector enforces it:
  MUTE drops the message entirely, MENTIONS counts only bodies containing
  @<your-localpart> or @room. Limits: local-only (no Matrix push-rule sync —
  other clients unaffected; no OS-notification suppression yet), mention
  detection is a body-substring heuristic rather than parsed m.mentions.
- ✅ Server creation/join-via-invite flows — the rail's + button (all three
  layouts) opens the Add Server dialog: Create tab builds a space via
  createSpace and auto-creates #general inside it; Join tab joins by room ID
  or alias via joinSpace; both select the resulting server on success.
  Per-server room creation has its own dialog (text/voice toggle,
  lowercase-hyphen name validation). Limits: the Invite/Discover choice cards
  are decorative only — there is no server directory behind them, and invites
  are raw room IDs/aliases rather than shareable invite links.

> Rule: features get added to Settings → Keybinds ONLY when they actually
> work (H19). Build feature → wire shortcut → restore card.

---

# THE TEN COMMANDMENTS (lessons, distilled)

> 🕯️ *Ten laws. Zero appeals. Moses got a burning bush; we got merge
> conflicts.*

1. Peers act on membership DIFFS — recovery must be baked into join, not left
   to watchers.
2. Empty-content membership events are removals. The sticky roster lies;
   LiveKit presence is truth.
3. A received olm event proves the direction works NOW — reply along it.
4. Never block distribution on missing keys; defer per DEVICE, never per USER.
5. Always rethrow `CancellationException` before generic swallowing.
6. Any publish path gets its teardown wired on EVERY stop surface.
7. libwebrtc JB delay fields are lifetime sums. Divide before believing.
8. Measure the deadline over the thing it polices — stamp progress at phase
   boundaries, and tear down zombies even when the work "succeeded".
9. Ship with a heap cap; assume GC will let garbage pile to ¼ of physical RAM.
10. When a log line looks like a demon, check the Bestiary first — half of
    hell is benign. **But a line in the Bestiary is a claim, not a fact: if the
    outcome ever changes, the entry is wrong. See [H24](#h24--the-0xc0000409-termination-abort-native-threads-outlived-the-jvm).**

---

# THE THREE COMMANDMENTS THAT COST THE MOST

> 🕯️ *Added v0.2.2. These are not lessons — they are the shape of the eleven runs
> that produced [H21](#h21--the-rejoin-hang-one-rule-broken-five-times).*

**11. A fix that moves the symptom to the next line of the same trace is not a
fix.** Five correct fixes each unstuck one waiter and produced an identical log.
The trace never moved because every site had the same defect. **When the third fix
for one symptom does not end the symptom, stop fixing symptoms and go count the
sites.** The pattern was in the file the whole time, in a comment, twice.

**12. Never `join()` a thread while holding a lock that thread needs to finish.**
This is a rule about the whole file, not about the function you are editing. It
was broken in five places and the file said so twice. **If you find yourself
deferring a join, grep for every other `join()` and ask what each one's thread
needs.**

**13. Verify the bug is reachable before you fix its neighbours.** A dead
`RESEND_GRACE_PERIOD_MS` branch in Element's flow was the smoking gun for the
key-rotation bug — and it was unreach*able in Orbit, which is *why* every rejoin
rotated. A line of code that cannot run is not a safety net. It is an accident
waiting for a refactor to make it live.

**Corollary — instrumentation beats reasoning, every time.** Six diagnoses this
session came from reading an exact log line and were right. Five came from
assuming a mechanism and were wrong. The `disconnect step=` trace ended a hunt
that ten rounds of code reading had not touched, because it announced each
blocking call *before* it ran, so a call that never returns still names itself.

---

*Every entry above corresponds to a real shipped fix with a commit behind it.
If you break something new, add its entry here. Future-you is the audience.*

---

> 🕯️ **A note on the entries that were WRONG, because they are the most useful
> ones here.**
>
> [H8-TX](#h8-tx--tx-dead-peers-cant-hear-us) shipped a complete, plausible,
> unit-tested theory of "peers can't hear us" that was not the cause. Every fix in
> it is real code that works. None of them fixed the symptom. It stays in the book
> **unedited in its wrongness**, with the correction appended, because a book that
> only contains right answers teaches you nothing about how the wrong ones happen.
>
> Two failure shapes worth naming:
>
> 1. **A correct theory of the wrong layer.** "Keys aren't distributed" and "keys
>    are distributed under the wrong index" feel identical from the symptom —
>    their TX works, ours doesn't. The whole distribution stack was fine. The bug
>    was one state machine upstream of it.
> 2. **A dead branch mistaken for a safety net.** Element's
>    `RESEND_GRACE_PERIOD_MS` looked like it handled rejoins. It was unreachable in
>    Orbit, which is exactly why every rejoin rotated. Unreachable code is not a
>    guard; it is an accident with a good comment above it.
>
> The thing that actually cracked H8-TX was not reasoning. It was a user
> observation — *Element↔Element works, Element↔Orbit doesn't* — which said the
> bug lived in whatever Orbit does that Element does not, and Element never has to
> **infer** a departure. Six diagnoses from exact log lines were right; five from
> assumed mechanisms were wrong. **Read the log line. Do not reason about the
> mechanism.**
