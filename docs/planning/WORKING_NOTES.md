# Orbit — Working Notes

A working document for the codebase. Not a changelog: it covers what the code
*is*, what was removed and why, and the traps that have already cost hours.

Last updated: 2026-09-30 · 182 tests passing · everything staged, nothing committed

---

## 1. What Orbit is

A Kotlin Multiplatform / Compose Desktop Matrix client. Chat, calls over LiveKit,
and self-hosting support. JVM target only right now; the `commonMain` /
`jvmMain` split is real but there is no non-JVM target.

The Matrix SDK is `trixnity`. Calls use `org.matrix.msc3401` (MatrixRTC) with
LiveKit as the SFU, and a fair amount of native C++ for audio
(`app/src/jvmMain/cpp/orbit_livekit_audio.cpp`).

---

## 2. The traps

These are the ones that produced real bugs. Each is now pinned by a test **unless
marked otherwise** — and four of the newest ones cannot be, because they need a
native reader thread, a WASAPI session or an audio device. Those four are marked
⚠ and listed again in section 12.

### `m.call.member` is a MEMBERSHIP RECORD, not a presence signal

An `m.call.member` state event asserts *"this device was in a call from
`created_ts` for `expires` milliseconds."* It is not "this person is in a call
now."

Consequences:

- Nobody publishes a leave when the window closes. The membership simply lapses.
- Windows are **hours** long (12h legacy, 4h modern). Nothing is ever deleted.
- A room accumulates one record **per device per call**, forever.

So a membership can be unexpired and still describe a call that ended this
morning. Any code that treats it as presence will show ghosts — this was the
"everyone is in the call and nobody is" bug.

**Rule:** a membership counts only if it is unexpired *and* newer than the current
call's start. The call start comes from the `origin_server_ts` of the room's
`m.call` definition event. See `repository/MatrixRtcMembership.kt` and
`repository/CallStateResolver.kt`.

### `m.terminated` is not the only way a call ends

A call definition carries a `lifetime` in milliseconds. It stops being a call
`origin_server_ts + lifetime` ms after it was sent — whether or not anyone
terminated it.

A client that crashes, is killed, or loses connectivity at hangup never publishes
`m.terminated`, and its definition then sits there looking alive indefinitely.
On any long-lived server this is the normal state, not the exception.

**Rule:** treat a definition as over if it is terminated **or** aged out. Judge it
only from a source that carries `origin_server_ts`.

### `GET /rooms/{id}/state/{type}/{key}` returns the CONTENT only

No timestamp. So that endpoint physically cannot evaluate `lifetime`, and any
liveness decision made from it will treat stale definitions as live. Use the
all-state scan, which carries `origin_server_ts` per event.

### `MatrixRtcCalls.isDefinitionTerminated` and `m.terminated: false`

`m.terminated` is `true` or an object `{"reason": "..."}`. It was once read as
"the key is present", so a literal `false` — explicitly NOT terminated — read as
terminated and hid live calls. It also called `jsonPrimitive` on `m.intent`,
which throws when that key holds an object (room state is attacker-shaped input).

### MatrixRTC ≠ LiveKit

matrix.org's default call focus is **Jitsi**. Orbit joins **LiveKit** calls. A call
started from another client on such a server is visible to Orbit and cannot be
joined. That is not a bug in Orbit; it is a consequence of the two stacks
differing, and it will read as "the call is broken" if you don't know.

While connected, the LiveKit room's own participant list is the only authority on
who is present. `livekit/CallRosterReconciliation.kt` reconciles the Matrix
roster against `LiveKitRemoteVideoFrameStore.remoteParticipants`.

### LiveKit participant identities vs audio track SIDs

A participant can have **two** audio tracks (screen-share-with-audio). Mixer
queues are therefore keyed by **track SID**, deliberately — an identity-keyed
queue doubles that participant's inflow and lets either track's teardown destroy
the shared buffer.

But the *reported* value (active-speaker events) must be the **participant
identity**. These were once a single field, so `ActiveSpeakersChanged` carried
`TR_…` while the UI compared against `@user:host:DEVICE` — the event fired
correctly, in the wrong namespace, forever. `ParticipantPcmQueue` now carries both.

### Compose: a non-weighted `Row` child starves a `weight(1f)` sibling

`Row` measures non-weighted children against the **full** incoming width first,
then divides the remainder among weighted ones. So a sibling using `fillMaxWidth()`
takes everything and the `weight(1f)` child is measured at zero.

A `Text` at ~zero width wraps **one glyph per line**. That is how "App Theme"
rendered as a vertical `A p p T h e m e`.

Two traps inside that one:

- `horizontalScroll` reports the **full parent width back**, so a scrolling chip
  row claims everything even when its segments are content-sized. Wide controls
  need a **vertical** card (label above, control below), not a fixed-width scroll.
- `.border()` on a parent Box draws *before* its children. If the first child is
  `fillMaxSize()`, the border is painted over and invisible. Draw the ring last as
  an alpha-animated overlay.

Fixed by: `weight(1f, fill = false)` + `widthIn(min = …)` on labels, vertical card
layout for wide controls, and overlay-first ring drawing.

### A reader with no writer is not a feature

`SettingsManager` had 69 settings keys that nothing read or wrote. Seven more are
read but can never be written — they are constants wearing a settings API. A
declared accessor with no caller reads as "implemented" and is not.

### Tooltips inherit their anchor's constraints

`HoverTooltip` renders inside the rail item's `Box`, so it was measured against
that box's ~68dp width — minus 54dp of padding, ~14dp left for the text, sliced
with no ellipsis. Anchored overlays need `Modifier.layout {}` to measure
unbounded.

### Search the codebase before trusting "no log line"

"No speaking events in the log" was indistinguishable between the event not firing
and the event being suppressed by a debug gate. "No `JoinFocus` lines" was
myself, gating my own diagnostics. Neither branch had a log line at all, so the
absence carried no information.

When a branch produces no output, **add a log line** so its absence means
something next time.

### ⚠ Ordering is not mutual exclusion

`LiveKitRemoteVideoFrameStore.updateFrame` read `activeSession`, built a frame,
then wrote five `StateFlow`s. `clear()` disarms the store and empties the maps.
If `clear()` ran in the middle, the in-flight writes landed on top and
repopulated them — **a ghost tile that outlived the call.**

The code carried a comment claiming this was already handled:

```
// Disarm BEFORE the map resets: a native reader thread that is still
// mid-callback for the old session must not repopulate them afterwards.
```

It isn't. Ordering only helps if the other side has **not already passed its
check** — and a reader thread pushing 30–60 frames a second per track usually
has. Only a lock around re-validate-and-commit actually works.

**Rule:** when one thread reads shared state, does work, then writes shared
state, and another thread can reset it in between, the read must be *repeated*
under the same lock as the write. Never rely on write order across threads.

### ⚠ `CopyOnWriteArrayList` makes each operation safe, not the pair

`WindowsAudioDucker` captured COM `ISimpleAudioVolume` pointers into a
`CopyOnWriteArrayList`. Two concurrent ducking passes could capture the *same*
pointer twice, and `unduckVolumes()` released once per entry.

A double COM `Release` drops the refcount below what WASAPI still holds. It does
not fail at the release — it fails minutes later inside audio code that has
nothing to do with ducking.

**Rule:** a thread-safe collection removes data races, not logic races. Any
check-then-insert needs its own lock or a de-duplication set.

### ⚠ A lock on the write side is not a lock on the read side

`CallRingtone.playing` was a plain `var`, written inside `@Synchronized
fun setPlaying` and read unsynchronized on the worker thread. There was no
happens-before edge, so the loop was entitled never to observe the stop and
would ring until the line was closed from somewhere else.

**Rule:** `@Synchronized`/`Mutex` protects code *inside* the critical section.
A flag read outside one needs `@Volatile`, an atomic, or the same lock on both
sides.

### ⚠ An error path that leaves "started" set makes a feature permanently dead

`CallRingtone` again, worse. If `line.open()` threw, the `catch` logged and
returned without clearing `playing`. `playing` stayed `true` and `worker` stayed
non-null, so every later `setPlaying(true)` short-circuited on
`if (start == playing) return`. One unavailable output device and the ringtone
was dead for the rest of the process.

**Rule:** the recovery path of an "am I doing X?" flag must reset the flag. If
the latch and the resource have separate lifetimes, the error path is wrong.

### A rate-limit constant nobody reads is not a rate limit

`REPLY_ON_RECEIVE_MIN_INTERVAL_MS` and its `lastReplyToDeviceMs` map both
existed, and neither was ever read. Every received call-media key produced an
immediate resend **plus** a `/keys/query`. The KDoc argued loop safety purely
from *"the peer's `OutdatedKeyFilter` will drop it"* — a property of another
implementation, not a local guarantee. If a peer ever echoes a key back, that
is a Matrix room event per echo.

**Rule:** grep for the constant's *reads*, not its existence.

### `PlayableMedia.options()` is discarded by `play(String)`

`NativeVideoPlayer` built an auth header with
`media.options().add(":http-header=Authorization: …")` and then called
`libVLC.mediaPlayer.play()`. For a URI, `play()` resolves and plays the
*library's* media item, not the one you configured — so the header was set on
an object that was never opened. Every authenticated video request went out
unauthenticated and came back 403.

Correct form is `libVLC.mediaPlayer.play(media)`. The same feature's
`AudioPlayer` had a harder version of this: `play()` was literally
`error("not implemented on Desktop JVM")` with libVLC already linked in.

### Layer violations are invisible until they become unfixable

`repository/` and `ui/theme/` both ended with

```kotlin
import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
// …and four more
```

Seven star-imports, 17 files, 119 lines — the *data* layer importing the
*screen* layer. It compiled fine the whole time and would have made it
impossible to move a repository method off the main thread, extract a shared
model, or test anything without Compose on the classpath.

**Rule:** the compiler will never catch this. Grep for `import ui.screen`
inside `repository/`, `ui/theme/`, `ui/model/`, `livekit/`, `webrtc/`. Star
imports of *own-layer siblings* are fine; imports of a layer *above* you are
not.

### `Set-Content -NoNewline` destroys arrays in PowerShell

Writing an array of lines with `-NoNewline` joins every element with **no
separator**. Nine repository files collapsed into single 20,000-character lines.

Use `[System.IO.File]::WriteAllText($path, ($lines -join "`r`n"))`. When editing
Kotlin in bulk, prefer the Edit tool — a regex with nested braces ate
`object Colour` (128 lines) from `Stub.kt` in this session too.

---

## 3. Layout of the code

```
app/src/
  commonMain/kotlin/
    livekit/       LiveKit wrappers, native media session, media-key E2EE,
                   call roster reconciliation  (note the two r's)
    repository/    Matrix data layer. MatrixContext owns the client, credentials
                   and every cache; each *Repository is a slice of it.
    ui/
      components/  Shared primitives — buttons, inputs, rows, dialogs, menus,
                   tooltips. The visual language is decided HERE, not per screen.
      model/       Pure data + pure logic. Anything testable belongs here.
      screen/      Composables, grouped by surface.
      theme/       Tokens, palette, shapes, motion.
      icons/       Generated-ish icon set, lazily extracted and cached.
  jvmMain/
    cpp/           Native audio. SessionLock, deferred dispatch, RoomDelegate.
    kotlin/util/   JVM-only: browser, audio player, media fetch, file pickers.
  commonTest/kotlin/   182 tests, all JVM.
deploy/                Self-hosting bundle. Synapse + LiveKit + coturn, compose
                       files, and the Tailscale / public / Tuwunel guides.
```

### Principles that are load-bearing here

- **Pure logic in `model/` or a `*Resolver`/`*Membership` object, never in a
  composable or inline in a repository.** `CallStateResolver` exists because the
  equivalent logic was inline in `resolveGroupCallJoinTarget`, where two
  independent mistakes survived several rounds of testing.
- **Comments explain WHY, and only where it is non-obvious.** Most of this file is
  the justification for code that would otherwise be "tidied" back into a bug.
- **Tokens over literals.** A hardcoded `Color(0xFF14171D)` was the tile
  background while the surrounding surface moved; the tiles became dark holes in a
  lighter stage. Reference `ui.theme.*` constants or a token.
- **Layers may only import downward.** `livekit/` and `webrtc/` sit at the bottom,
  then `repository/`, then `ui/model`, then `ui/theme`, then `ui/components`, then
  `ui/screen` at the top. `repository/` and `ui/theme/` each carried seven
  `import ui.screen.*` star-imports until this pass. The compiler will never catch
  this — it compiles perfectly and it makes the layer untestable without Compose on
  the classpath. Check it with:
  `Select-String -Path repository,ui\theme,ui\model,livekit,webrtc -Pattern '^import ui\.screen'`

---

## 4. What was fixed

Grouped by what the bug looked like from the outside, because that is how you
recognise it next time.

### Ghost rosters — "everyone is in the call and nobody is"

The single worst bug in the codebase, and it was four bugs stacked.

1. `m.call.member` was treated as presence. It is a *membership record* asserting
   a past window; nobody publishes a leave. `CallStateResolver.kt` (new) now
   requires either a terminated definition, an aged-out `lifetime`, or a fresh
   membership — and the "fresh" test carries a 120 s skew allowance so a slow
   joiner isn't dropped.
2. `isDefinitionTerminated` read `m.terminated` as *"the key is present"*. A
   literal `false` — explicitly **not** terminated — read as terminated and hid
   live calls. It also called `jsonPrimitive` on `m.intent`, which throws when
   that key holds an object, and room state is attacker-shaped input.
3. `resolveGroupCallJoinTarget` trusted a direct `GET /state/…` fetch, which
   returns content with **no timestamp**, so it physically cannot evaluate
   `lifetime`. The all-state scan (which carries `origin_server_ts`) is now
   authoritative and the direct fetch is the fallback.
4. `parseStateArray` aborted the whole parse on one malformed element. One junk
   element used to wipe the entire roster. It is now per-element.

### Features that were literal no-ops

| Feature | What it actually did |
|---|---|
| Voice-message playback | `AudioPlayer.play()` was `error("not implemented on Desktop JVM")`. Now drives libVLC's `AudioPlayerComponent` with an authenticated temp-file fetch, and releases the component rather than just stopping it |
| Video-message playback | Auth header set on `media.options()`, then `play()` — which discards it. Now `play(media)` |
| Camera toggle | Re-extracted the multi-megabyte ONNX segmentation model from resources on **every** toggle. Now cached per resource name |
| Screen-share audio ducking | The duck job's handle was dropped, so cancelling left playback at 30 % for the rest of the call |

### Layout defects that made text unreadable

- `Row` measures non-weighted children against the full width first, so a
  sibling `fillMaxWidth()` starved a `weight(1f)` child to zero width — and a
  `Text` at zero width wraps **one letter per line**. That is the entire
  explanation for the vertical "App Theme". Fixed with `weight(1f, fill = false)`
  plus `widthIn(min = …)`, a `fillWidth` parameter on `OrbitSegmentedControl`,
  and switching four cards to vertical layout.
- `HoverTooltip` rendered inside its anchor's `Box` and inherited its ~68 dp
  width, leaving ~14 dp for the text with no ellipsis. Now measured unbounded.
- `DirectProfilePanel` had two `OrbitButton(fullWidth = true)` in a `Row`; the
  Video button was pushed off-panel entirely.
- `ChannelList` showed a voice roster under *every* channel. Roster now appears
  only under VOICE channels.
- Call tiles drew the speaking ring first, then a `fillMaxSize()` avatar over it.
  The ring was invisible. Avatar now scales to the tile (26 % of the short edge,
  56–180 dp) and the ring is drawn last.

### Concurrency and lifecycle

| Defect | Symptom |
|---|---|
| `nextEpoch()` published its value *after* the monitor released | Two calls could get the same epoch, so one call's teardown could clear the other's state |
| `teardownNativeMedia()` inherited the EDT | Native teardown on the UI thread |
| Capture generation | A superseded capture loop's `finally` cleared the `started` flag of the loop that replaced it, killing two consecutive camera calls |
| ICE candidate buffers cleared without a guard | Five unguarded triple-clears could drop a candidate between publish and consume |
| Mixer thread took `g_sessionMutex` 200×/sec | Contention against a join-under-mutex teardown. Now reads a lock-free epoch |
| `readinessCache` derived field-by-field with no lock | Concurrent readiness updates clobbered each other |
| `LiveKitRemoteVideoFrameStore` check-then-commit | Ghost tiles that outlived the call. See the trap above |
| `WindowsAudioDucker` double COM release | Access violations inside WASAPI, minutes later, in unrelated code |
| `CallRingtone` flag read without a happens-before edge | Could ring forever |
| `endCall()` leave-job not cancelled | The coroutine leaked; only the *wait* was bounded |
| `loopbackDuckJob` handle dropped | Playback stuck at 30 % |
| `CallMediaKeyExchangeManager` unsynchronised retire | Drain raced with the retire that produced it |
| `REPLY_ON_RECEIVE_MIN_INTERVAL_MS` never read | No rate limit at all |

Three were fixed as a group because a test against racy code just bakes the race
in: the epoch, the capture generation, and the readiness cache all needed the
shared state extracted into something testable first (`MonotonicEpoch.kt`).

### The two "it works on my machine" video defects

Both of these only bite at 1080p, which is why a camera at 640x360 always looked
fine and a shared window never did. The numbers are in run 19.

**A screen share was invisible; a camera at the same moment was not.** The remote
video reader loop was

```cpp
while (reader->running && reader->stream->read(event)) {
    dispatchRemoteVideoFrame(reader, event, sequence);   // full frame, every frame
}
```

No downscale, no rate cap, no backpressure. A 1920x1080 share is 8.29 MB of RGBA
and the SFU pushed 28.8 fps (`seq 60 -> 120 in 2080 ms`), so ~237 MB/s went into the
JVM as `NewByteArray` + `SetByteArrayRegion`. The UI then amplified every one of
those four times over: `ByteArray` -> `BufferedImage` raster -> `toComposeImageBitmap`
-> Skia `Bitmap`. That is ~1 GB/s of allocation from 8 MB live objects, which is
permanent full-GC territory — the camera survived it at 12 MB/s, the share did not.

Fixed by bounding the *input* rather than the symptom:

| Bound | Value | Why that value |
|---|---|---|
| Long edge | 1280 px | The standard "big layer" size, and about what a call tile can resolve anyway. 1080p -> 1280x720 is 2.25x less data |
| Delivery | 20 fps | Nobody distinguishes 20 from 30 on a talking head or a shared window |

Both are one-line constants, and `DEBUG [RemoteVideo] frame` now prints
`read=` / `rateSkipped=` / `srcWidth=` / `downscaledFrames=`, so if it is *still*
too slow the log says by how much instead of the next guess being another guess.
The downscale is an exact-area box filter (not point sampling — a shared window is
mostly text, and point-sampling drops half the glyph strokes) and it runs while
the pixels are still in native memory, so the detail is discarded once instead of
being copied four times and thrown away later.

**A screen share could be filed as a camera.** `updateFrame` handles frames that
arrive before `trackAdded` by calling `trackStarted(participantIdentity, trackSid)`
— with `trackSource` defaulted to `""`. An empty source makes `isScreenShare`
false, and `CallGrid` keys its entire layout off `RenderNode.isScreenShare`: a
share gets the 2.5-weight spotlight, a camera gets an equal grid cell. So this was
not "a slightly smaller video", it was the wrong UI shape on the path that decides
what is on screen at all.

The race is real, not theoretical: native starts the reader thread immediately
after queueing `onRemoteVideoTrackAdded`, and that queue only runs inline when the
session lock is free. It did not happen to lose in run 19, which is exactly why it
survived. Fixed by passing the source in with every frame — the native reader
already knows it. That changed the JNI signature of `onRemoteVideoFrame`, which
means the method-ID string in `nativeSetRemoteVideoSink` and the interface in
`NativeMediaEvents.kt` are a matched pair; change one without the other and the
sink fails to install with `NativeRemoteVideoSink methods not found`.

Pinned by `RemoteVideoTrackClassificationTest` (5 cases). Reverting the one-word
fix turns exactly 2 of them red, and the 2 that go red are the 2 share cases —
verified, not assumed.

### A 12-second microphone probe sat on the join path

`JOIN-TIMING` from run 19, added the previous session:

```
JOIN-TIMING ice=216ms token=1177ms publish=5179ms encProbe=208ms native=14216ms (attempts=1) total=21001ms
```

`native=14216ms` looked like SFU connect time. It was not. Inside it:

```
DEBUG [LiveKitNativeMediaSession] microphone self-test before call start durationMs=3000
DEBUG [LiveKitNativeAudioCapture] self-test started durationMs=3000 mixers=4
... one line per mixer, 3 seconds apart, four of them ...
```

`probeInputMixers` maps over the candidates, and each probe is `durationMs` of
blocking `TargetDataLine.read()`. **Four mixers x 3 s = 12 s of the 21 s join**,
spent listening to microphones before the SFU connection was allowed to start. On
a laptop with one built-in mic it is 3 s, which is why this reads as "the network
is slow here" rather than as a bug.

The result does not change between two calls in a session: which mic is plugged in
and which one the user picked are both the same. The chosen mixer was *already*
cached in `preparedMixerKey` and reused when the capture line opened — only the
probing repeated, which is the expensive part, and the fresh answer was then
discarded in favour of the cached one.

So the probe now records a signature of what it probed (override + every
candidate's name, description, format support, override match, score) and skips
when nothing changed. Plugging a mic in, editing the override, or a device
changing its supported formats all produce a different signature and force a real
probe. `DEBUG [LiveKitNativeAudioCapture] microphone self-test reused cached probe`
says so, with the milliseconds it saved.

**The honest limit:** this only helps from the *second* call onwards. The first
join still pays the full probe, because that is the join that has to discover what
the hardware is. Removing that would mean not blocking the connect on the device
choice at all — a restructure of `LiveKitNativeMediaSession`, not a cache. Not
done.

### Silent data loss

- `stopCallRosterPolling` stopped one of the two roster flows and left the other
  running. Both are cleared now — but the flow *object* is deliberately kept, so
  existing subscribers don't hold a dead reference and silently miss everything.
- `WebRtcClientImpl.endCall()` wrapped its whole body in one `try`. The first
  failure skipped every remaining step, including disposing the screen source and
  screen video track. Now per-step `runCatching`.
- `setParticipantGainEverywhere` used two `find(...) != npos` substring tests,
  so `@alice:server` matched inside `@alice:server2`. Now delimiter-aware.

### The app told you things succeeded that never happened

The single worst class of bug in this codebase, and it had **one** root cause:
there was no user-visible error surface at all. `OrbitLog` writes to the console.
Nothing else existed. So every failure became invisible, and fifteen call sites
took advantage of that:

```kotlin
runCatching { repository.deleteMessage(channel.id, eventId) }   // Result dropped
runCatching { repository.kickUser(channel.id, userId, null).getOrThrow() }
else -> repository.sendMessage(channel.id, body)                 // Result dropped
```

Kick and ban are the worst of them: they called `.getOrThrow()` *inside* the
`runCatching`, so the exception was raised and then thrown away. The UI reported
success for a moderation action that did nothing. Every one of those sites also
followed the write with an unconditional re-read from the server, so a failure
painted the unchanged state back over the optimistic state the user had just been
shown — failure was visually indistinguishable from success.

Three new pieces:

| Piece | Why |
|---|---|
| `ui/components/OrbitNotices.kt` | A process-wide notice store. Not per-screen, because the failures that matter are the ones you did not initiate from the screen you are looking at — a call membership update that failed, a presence change that did not land |
| `ui/components/OrbitNoticeHost.kt` | Renders them bottom-end. Three layout traps from §2 are avoided explicitly and commented |
| `MessageInputBar.draftToRestore` | The composer cleared itself synchronously the instant send was pressed, so a failed send destroyed the message. The parent now pushes the text back |

Every one of the fifteen sites now reports. Sites where the user explicitly acted
get a **Retry** that re-runs the same operation; sites they did not (presence, call
membership, ICE) report without one, because nagging someone to retry something
they did not ask for is worse than the state being briefly wrong.

Two of them were worse than silent and are worth naming:

- `startCallMediaKeyExchange`'s Result was discarded. If it failed, the call
  proceeded with media keys that were never agreed — **and looked like a working
  encrypted call.**
- `updateGroupCallMembershipCapabilities` returns a bare `Boolean`, not a
  `Result`. Wrapping it and only inspecting the throwing path would have let every
  `false` — the ordinary offline case — pass as success. Both routes now report.

`OrbitNoticesTest` covers the store (14 tests). It is the only piece of this work
that is pure enough to pin: removing the visible cap, or discarding the retry
action, each makes it go red. I verified both.

### Documentation that described deleted code

`README.md`, `docs/planning/headache_medicine.md`, `docs/planning/implementation_plan.md` and
`repository/README.md` all listed files and features that no longer exist. The
worst case was `repository/README.md`, which claimed `getSpaceHierarchy()` was
unimplemented, `fromToken` was unused, `observeActiveGroupCall()` was a stub and
`observeCallEvents()` was never emitted. **All four are wrong**, and the fourth
names a symbol that does not exist in the codebase any more. That is exactly the
failure mode of a stale doc: nothing flags it, so it quietly becomes the thing
everyone believes. The gap list has been rewritten to contain only claims verified
against the source today, plus an explicit "corrections" list so nobody re-adds a
fixed item from memory.

---

## 5. What was removed, and why

Two passes, ~1,550 lines of Kotlin plus 4.7 MB of orphaned assets. Every entry
was verified with a reference count (or a compile) before cutting.

### Pass 1 — features that never existed

| Removed | Lines | Reason |
|---|---:|---|
| `theme/Color.kt` | 277 | Whole colour-override system. Its only entry point was a `colourOverrides` parameter nobody ever passed; its resource table returned `0` for every key. A stub, not a feature |
| `drawer/WhatsAppDrawer.kt` | 345 | Lite mode. 7 composables, zero references once the toggle went |
| `jvmMain/.../SplashScreen.kt` | 177 | `Main.kt` never imports it. Self-referential only |
| `SettingsPerksPanes.kt` | 196 | "Orbit Plus", Nitro boosts, quests, shop tiers and a subscription pane — for a paid tier that does not exist and never did. It also carried the 7-line `import ui.screen.*` block, which is why it appears in both passes |
| `SettingsManager` dead keys | 133 | 69 keys, no readers or writers. `spellcheck`, `colorblindMode`, `taskbarBadge`, `reducedBandwidth`, `betaTestMode` — settings that read as features and have never done anything |
| `Stub.kt` `OverridableColourScheme` | 31 | 29 nullable `Int?` colour fields whose one consumer was deleted |
| Lite-mode branches | ~100 | Early returns across 11 files |
| `Theme.kt` scaffolding | 20 | `systemSupportsDynamicColors()` hard-returned `false`, making `getDefaultTheme()` a constant with a non-existent caller |
| `OrbitIcons.File` / `.Notifications` | 25 | Only 2 of 23 icons with no call site |
| `OrbitPopoverCard` | 35 | Zero refs; a third spelling of "floating panel" |
| `OrbitPill` | 32 | Zero refs; `OrbitCountBadge` covers it |
| `OrbitLazyColumn` | 30 | Zero refs — **and its KDoc falsely claimed every scrolling surface used it** |
| `DrawerActionButton` | 26 | Died when Invite/Create moved out of the sidebar |
| `OrbitMotion` helpers | 20 | A second reduced-motion convention nobody adopted |
| `OrbitMenuCheck` | 18 | Zero refs |
| `TestStats.kt` | 11 | Zero refs |
| `ShutdownReason` | 9 | Enum with no producer or consumer |
| Unused imports | ~45 | 31 `m.call.*` / `ProfileField` across 9 repositories, plus fallout |

### Pass 2 — residue from pass 1, plus single-reference orphans

| Removed | Lines | Reason |
|---|---:|---|
| `import ui.screen.*` block | 119 | 7 star-imports × 17 files in `repository/` and `ui/theme/`. The inverted dependency; see trap above |
| `DirectMessageNav` + `DirectMessageSearchBar` + `OrbitNavStub` + `DirectMessageNavItem` | 129 | A "Friends / Nitro / Shop / Quests" nav and a fake search field. Every row was inert; the real DM list is 200 lines below them |
| `DeviceVerificationManager.kt` | 67 | A class nothing constructed, whose three methods each only print a warning and refuse to act. `VerificationOverlay` drives Trixnity's `ActiveDeviceVerification` — a different type with a confusingly similar name |
| `OrbitLoadingState` | 51 | Zero refs, and a sweep-gradient loader for a screen that already had one |
| `hueToRgb` | 11 | Dead HSV helper |
| `OrbitRowTrailing` | 5 | A `@Immutable` wrapper class with no constructor call |
| `shadowSmall/Medium/Large` | 5 | No `Modifier.shadow` call site ever passed them |
| `LocalFontSize` + its provider | 2 | Provided on every frame, read by nothing — font size reaches the UI as a custom `Density` |
| `OrbitRadius` Dp layer + `OrbitMetrics` dead constants | 14 | `xs/sm/md/lg/xl/full` were public Dp *and* `*Shape` twins, so a call site could wrap a radius in its own shape and bypass the scale. Now `private`, shapes only. `drawerWidthWide`/`titleBarHeight`/`rowHeight` had no readers |
| `splash_center.png`, `splash_rings.png`, `splash_rings_bare.png` | **4.7 MB** | Orphans of the deleted `SplashScreen.kt` |

### Left behind deliberately

- **7 settings with a reader but no writer** — `liveKitServiceUrl`,
  `desktopNotifs`, `joinLeaveSound`, `callRingtone`, `deafenSound`,
  `use24HourClock`, `dateFormat`. The readers are live and returning a default.
  Each needs either a UI or deletion; both are real product decisions.
- **62 deleted settings that arguably should be BUILT**: `spellcheck`,
  `colorblindMode`, `highContrast`, `ttsRate`, `reducedBandwidth`, `taskbarBadge`,
  `overlayOpacity`. They are dead, but they are a wishlist, not cruft. Anything you
  want back should go through Settings UI, not be re-added as a bare key.
- **`OrbitDoodleWallpaper.Dark02` and 20 WhatsApp-style doodle SVGs.** Reachable
  from the wallpaper picker, so live — independent of the lite-mode toggle.
- **`Color.kt`'s removal was the one judgement call.** `colourOverrides` was a
  public parameter on `StoatTheme`. If theme customisation is ever planned, this
  needs re-deriving rather than copy-pasting.
- **`orbit_livekit_audio.cpp:~1640` still takes `g_sessionMutex`** for
  `g_connected` / `g_rawDisconnecting`. That is contention, not deadlock, and
  the mixer takes it 200×/sec. An atomic-mirror scheme was drafted and reverted:
  one missed writer would have been a worse bug than the contention. Left with a
  comment saying so.

---

## 6. Known-unimplemented

Honest list. **Every row below was re-checked against the code on 2026-09-30, in
this pass, and not taken from the earlier version of this file.** That mattered:
two of the previous entries were wrong. One said the banner was "parsed and
rendered nowhere" when only *half* of that was true. One listed
`callRenderThumbnail` / `callFlagDirectChatForRoom` as being called into APIs that
do not exist — they are not called anywhere, so there is nothing left to fix.

The fixed entries are struck through rather than deleted, with what was actually
wrong, because "it was broken and here is why" is the part worth keeping.

### Costs the user something today

| Thing | State |
|---|---|
| ~~**Video heap**~~ | **Fixed for remote video, see §4.** The reader forwarded every frame at full size: 1920x1080 RGBA is 8.29 MB, the SFU pushed 28.8 fps, so ~237 MB/s of JNI `byte[]`, amplified ~4x by the UI. Now averaged to a 1280 px long edge in native and capped at 20 fps |
| ~~**Key-exchange convergence sweep never gives up**~~ | **Fixed.** 4 attempts, 30s backoff, `exhaustedDevices` excluded |
| ~~**Avatars in 1:1**~~ | **Fixed, in three separate places** — see §13. The group "You" tile read `profileForIdentity(selfId)` but nothing ever requested a profile for our own identity, so it could only return null; the P2P surface had no parameter to receive one; and the fallback colour was a hard-coded red in one surface and a six-entry palette in the other |
| ~~**Chat history stops at 50**~~ | **Fixed.** `loadOlderMessages` in `OrbitApp` |
| ~~**Room topic is never read back**~~ | **Fixed.** `MatrixRoomTopics` |
| ~~**`raise_hand` does nothing**~~ | **Fixed.** Sent via a raw `PUT /rooms/{id}/send/{type}/{txnId}`, since `sendCallEvent` is a `when` over trixnity's *closed* `CallEventContent` |
| ~~**No way to *start* device verification**~~ | **Fixed.** `VerificationOverlay` was already complete including `OwnRequest`; the only missing thing was the call that begins one. Now `Settings > My Account > Verify another device`, SAS only |
| ~~**`CallGrid` tile overflow**~~ | **Fixed.** Capped at 4 rows / 8 faces with a "+N" tile. It never overflowed — each tile just took an ever-smaller share of the height |
| ~~**Reconnect watchdog**~~ | **Fixed.** `Reconnecting` had no deadline at all. Now 90s. Adding it exposed a second bug: `stageEnteredAtMs` was never stamped on that transition, so the deadline would have fired on every network blip |
| ~~**Banner URL**~~ | **Fixed — and the note was half wrong.** `m.room.banner` was never read, which left `ServerHeader`'s complete banner layout running on a field nobody assigned. The *profile* banner was fine and is still fine; what was wrong there is in §13 |
| **`OrbitVideoEngine.injectVideoProcessor`** | No implementation in the shipped DLL, which is why the virtual background is inert. **Re-verified by test on every run** — `NativeSymbolAvailabilityTest` fails if the symbol ever appears, and says what to do then |
| **`OrbitAudioDeviceModule.setAttenuationStrength`** | Same. Also why **Ducking Strength is inert**. Same test, same guard |
| Soundboard on a legacy-signalling 1:1 | **Resolved, and the old description of it was wrong.** It said "on P2P there is no JVM-side mic tap" as though all 1:1s were affected. They are not: every call Orbit *starts* is a LiveKit group call, and that path folds the mixer bus into outgoing mic frames correctly. Only a 1:1 arriving through legacy signalling is affected, and the panel now explains itself rather than playing locally-only. See §13.8 |

### Not fixable in this codebase

| Thing | Why |
|---|---|
| Deafen-of-others | **Impossible by protocol.** Local output muting only; there is no wire signal in MatrixRTC. Never promise this |
| E2EE key lifecycle test coverage | Needs two encrypted clients and a mid-call network drop. Zero coverage today and no way to get it without a harness |

### Corrected as of this session

- **The playback underrun counter was reporting a healthy call as broken.** I
  recorded a "playback underrun defect" here on the strength of
  `underruns=364` and it was not real. The check was
  `available() >= out.bufferSize`, and `SourceDataLine.available()` is bytes
  writable *without blocking* — so that condition means the line's buffer is
  completely **empty** and the next write returns instantly. It is "we are
  ahead". The second half of the condition was dead as well: it compared
  against a timestamp stamped ten lines earlier, so it measured the
  microseconds spent in `ensureLine` and was always true.

  `underruns` is now a write-gap measurement (>60 ms between completed
  writes, stamped *after* the write), which is what an output underrun
  actually is. The producer-side counter was already right and is now
  printed under its real name: `starved`, meaning `poll` returned null, i.e.
  the mixer delivered nothing for 50 ms. `underruns`, `starved`,
  `droppedOldest` and `writeFails` all at zero is the healthy call.

  So the real backlog is one shorter than I listed, and playback was never
  on it. The `Speakers (NVIDIA Broadcast) matched no mixer, using default`
  warning is still real and still unexplained, but it is a device-selection
  fallback, not a starvation signal.
- The 50ms `g_sessionMutex` self-cleanup wait on both remote readers is gone.
  It could never succeed (the holder is the thread blocked joining) and only
  added 50ms to every hangup. Now one non-blocking attempt. **The video
  reader's warning still appears on a *mute* event, not teardown** — a track
  that goes muted exits its reader mid-call while the room delegate holds the
  lock. Harmless (the rescan restarts it, and run 14 shows
  `rescan recovered missing readers video=1`) but the warning is misleading.
- `jbMinMs` was a cumulative sum printed per-frame (38,438,400 ms = 10.7
  hours). Now normalised like `jbTargetMs`; run 14 reads 26–34ms.
- Remote audio is genuinely working: run 14 shows `inRms=339 outRms=339` and
  `RXSTAT lost=0`. The silence that looked like a second bug was the mixer
  evicting its own buffer.
- One thing that is *not* a bug, and will read as one: run 14 shows
  `concealedSamples=228165 silentConcealed=221520` and `inRms=0` at several
  sample points, alongside repeated `remote mic muted` / `remote mic
  unmuted`. The remote peer is muting. `silentConcealed` tracks their mute
  state, not a fault on our side.

### What still has no error surface

OrbitNotices covers the chat, moderation, settings, presence and call-signalling
paths. Two categories remain, both deliberate rather than overlooked:

- **Call signalling inside `CallManager` itself** — negotiation retries, the ICE
  restart path, LiveKit reconnect. These fire while a fullscreen call surface owns
  the window, and a notice behind it is not something you read mid-conversation.
  They log at DEBUG and are only visible with `OrbitLog.debugEnabled`.
- **Repository reads.** `getMessagesForChannel` and friends return an empty list on
  failure by contract, so "the network is down" and "this room is empty" are the
  same value. Changing that is a signature change across every repository, and it
  is a design decision rather than a fix — §11.

### The 58 sites I did not convert

`LiveKitNativeMediaSession.readinessCache` has 58 remaining places that read
`readinessCache.X` field-by-field and write a derived copy. Each one can
overwrite a field another thread just set. I converted the five that genuinely
raced (`Connected`, `AudioPublished`, `VideoPublished`,
`handleVideoCaptureFailure`, `updateReadinessWithError`); the rest pass every
field explicitly so the clobber window is narrower, but it is not zero.

Converting them properly means routing all of them through
`mutateReadiness { old -> old.copy(...) }`, which is a mechanical but wide edit
to the file that owns most of the call lifecycle. Worth doing before adding
anything new to readiness — not worth doing blind.


---

## 7. Verifying a change

```powershell
.\gradlew.bat :app:compileKotlinJvm --console=plain -q     # fast, ~6s
.\gradlew.bat :app:jvmTest --console=plain                 # 182 tests, must be 0 failures
.\gradlew.bat :app:run --console=plain                     # ONLY when the user asks
```

The native build runs as part of `:app:jvmTest`, so C++ changes are covered by the
test task. Kotlin incremental compilation occasionally fails with
`IC_FAILED_TO_COMPUTE_FILES_TO_RECOMPILE` and falls back to a full build — that is
harmless, just slow.

### What the test suite does and does not cover

105 tests at the start of this work; 182 now. What got added:

- `CallStateResolver` — every branch of the terminated/aged-out/fresh-membership
  decision, as a pure function.
- `MonotonicEpoch` — **verified to fail against the original defect.** The fix was
  reverted, the test was confirmed red, then the fix was restored. Do this for any
  concurrency fix you can extract into a pure class; a test written against racy
  code just bakes the race in.
- `RemoteVideoTrackClassification` — the same discipline, on the screen-share
  race. Reverting the fix turns exactly 2 of its 5 cases red, and the 2 that go
  red are the 2 share cases. Note the SIDs in that test are deliberately neutral:
  `isScreenShare` also matches on the SID containing "share", so a SID of
  `TR_SHARE` makes every camera assertion pass for the wrong reason.
- `ChannelGrouping` — drawer grouping, including the lite-mode rows it now shares
  with nothing.
- `MatrixRtcMembership` — membership acceptance, the 120 s start-skew allowance,
  and the "one junk element must not wipe the parse" rule.

The native box filter has no test *in the suite* — it is C++ in a file with
LiveKit headers, so a JVM test cannot reach it. I copied the function verbatim into
a standalone program and ran it against reference cases instead (uniform frame
preserved exactly, 2x2 block averaging to the true mean of 0/100/200/255 = 138,
exact 2:1 ratio not round-upping to source size, null/zero/1x1 all refused, output
size always exactly `w*h*4`). That verifies the arithmetic; it does not verify the
JNI handoff, which only a real call does.

What the suite does **not** cover, and I am not going to pretend otherwise: the frame-store
commit lock, the COM de-duplication, the ringtone flag, the readiness cache. Those
need a real audio device, a real WASAPI session, or a native reader thread. They are
compiler-verified and reasoned, not test-verified. **The next app run is the test for
those four.**

### Log lines that mean something

| Line | Meaning |
|---|---|
| `JoinFocus … exists=… reason=…` | Why a room is or isn't considered to have a call. Gated on debug logging |
| `active-speaker … <identities>` | The literal tokens the native mixer reported. This is how the `TR_…` vs `@user:host` namespace bug was found |
| `roster tick <room>: current call began Nms ago` | Call-start gating is live |
| `[RemoteVideoUI] render stopped reason=…` | The frame store reset. If a tile survives this line, you have the ordering race back |
| `[RemoteVideoUI] frame updated … seq=N` | A track is genuinely flowing. `seq` resets when the store is re-armed |
| `MatrixCategories: /categories unavailable; falling back to type grouping` | Homeserver has no MSC3315. Expected on Synapse |
| `[Browser] authenticated media fetch failed status=…` | 403 = token no longer good, 404 = no such media. Different problems |
| `[CallRingtone] playback failed: …` | The ringtone's output device died. It now resets its own latch; if the ringtone stops working entirely after this line, that reset regressed |
| `nudge scope: N roster device(s) not present in the LiveKit room` | Rate-limited to ghost-count changes. N>0 is normal; it means the Matrix roster has leftovers |

If a branch you touched produces no output, **add a log line**. Absence of output
should carry information.

---

## 8. Git state

**This section was written when nothing was committed and is out of date. Read the
current state from `git log` and `git status`; the history is the record.**

What it described at the time: everything staged, nothing committed, nothing
pushed, deliberately — the index was what made a `Set-Content` corruption
recoverable.

`git add -A` at the repo root is still unsafe. The repo tracks ~1,900 committed
`.class` files under `webrtc-java-lib/build/` (pre-existing, unrelated to any change
here) plus some root-level scratch files that `.gitignore` does not cover because
they were committed *before* the ignore rules existed. Use the narrower form:

```
git add -A -- app/src deploy *.md *.gradle* gradle .gitignore
```

Deleted in this work and staged as deletions: `Color.kt`, `WhatsAppDrawer.kt`,
`SettingsPerksPanes.kt`, `SplashScreen.kt`, `TestStats.kt`, `BottomNavBar.kt`,
`DeviceVerificationManager.kt`, the three `splash_*.png` assets (4.7 MB, orphans
of the deleted `SplashScreen.kt`), and `animated.gif` (an unreferenced sample).

`app/src/jvmMain/resources/webrtc-deps/` shows two DLLs as **modified** — that is
the C++ in this session, rebuilt. The old root-level `abseil_dll.dll`,
`orbit_livekit_video.dll`, `webrtc-java-orbit-windows-x86_64.dll` and
`animated.gif` show as deleted because HEAD still has them at the old paths
while the tracked copies live under `webrtc-deps/`. That consolidation predates
this session; nothing was lost. All 31 files under `resources/` are tracked, so a
fresh clone gets its native libraries.

`ListMics.java`, `ProbeMics.java` and their `.class` files are root-level
debugging scratch. They are **still on disk** and are now in `.gitignore`
along with `/scratch_test.kt`, `/fix_gif_crash.kt` and `/build_baseline_test.log`.
They were never part of the app.

`.gitignore` also stopped ignoring every `*.zip` in the tree and now ignores only
`**/build/jni/_downloads/*.zip`. The blanket rule was silently untracking real
project resources.

---

## 9. Self-hosting

`deploy/` holds the whole bundle:

```
deploy/
  README.md            Tailscale / public-domain / Tuwunel guides
  docker-compose.yml   LiveKit + coturn
  livekit.yaml
  coturn/turnserver.conf
  synapse/             compose + homeserver.yaml + .env.example + README
```

In-app: **Host your own server**, reachable from the login screen. It generates
the `.well-known` body, the Synapse `extra_well_known_client_content` block and
the Tailscale `serve` command *for the homeserver you typed*, each with a copy
button. It installs nothing and deploys nothing, and says so — a button labelled
"Set up my server" that does not would teach people to distrust the app.

Three limitations it states plainly, because they are counter-intuitive:

1. **Tailscale cannot proxy LiveKit's UDP range** (7882, 50000–50200). Signalling
   works fine; a participant outside your tailnet cannot join a call without a
   public TURN relay.
2. **Do not serve Synapse from a subdirectory.** Subpath support has broken more
   than once, and `serve_server_wellknown` ignores `public_baseurl`, so it looks
   configured and silently fails.
3. **matrix.org's focus is Jitsi**, which Orbit cannot join.

---

## 10. Open UI work

The call view and drawer now match Discord's *structure* and palette. The
call/chat arrangement was settled on 2026-09-30 after three wrong attempts,
and the reasoning is worth keeping because it is not obvious:

- **Call in the main column at full width, room timeline in a 380dp side
  panel.** Both on screen, neither a mode. Below 600dp they stack.
- A `Voice` row under every room in the drawer. It is a **call action on the
  room above it** — no `OrbitChannel`, no id, no category — because Discord
  has two kinds of channel and Matrix does not, and synthesising a fake voice
  channel is what produced three rooms called "General" where clicking one
  opened another.
- Pressing it opens the room and joins. **It never hangs up**; leaving is the
  red button. It was a toggle once and that was a bug — see §4.
- The in-room "join call" banner was deleted. Two affordances for one action
  is how a call ends up running while the UI still invites you to start it.

Not redone:

- Message list density — Discord runs ~22px rows; Orbit's are roomier
- Server rail — Discord's is 48px flat; Orbit's is wider and rounder
- The light theme has a contrast regression: `Input_Bg`, `Sunken` and `CardBg`
  are all `Paper100`, so a borderless field on a card reads flat. Fixing it
  properly means changing token values

(The channel-header topic line is **not** on this list — `ChatHeader` does render
`channel.topic` as a subtitle when it is non-blank. It is always blank because
`m.room.topic` is written on create and never parsed back. That was a repository
gap, not a UI gap; it was fixed in §13.)

Done in this pass, and worth listing here because they were on the §6 list and read
as UI gaps:

- **Call tiles show real avatars**, in a 1:1 and in a group. Three separate causes —
  see §13.1.
- **The call grid stops shrinking itself away.** Capped at eight faces with a "+N"
  tile for the rest.
- **A settings Notifications pane exists.** It was deleted once as a mock-up; it
  is back because those four switches now have setters and honoured readers.
- **Clock format and date format are selectable** in Appearance, after being
  permanently 12-hour and DD/MM/YYYY since they were written.
- **A self-hosted SFU URL is settable** in Voice & Video > Advanced, with the
  scheme validated, after being permanently blank.
- **A stuck screen share shows a spinner** from the moment the publication is
  announced, not from the first frame, and offers a reconnect if it never arrives.
- **You can start a device verification**, not only accept one.

---

## 11. Product decisions I did not make for you

Three things where the right answer is a preference, not a defect. Each is
one line of code away from either resolution, and none should be resolved by
whoever happens to be editing the file.

| Question | Options |
|---|---|
| **Roles & permissions editor.** `ServerRolesDialog.kt` lets you configure a role's name, colour and hoist state, and then writes nothing. | Map it onto `m.room.power_levels` so it actually governs, or delete the dialog. Wiring it properly is a day; the current state is worse than either, because it looks like it works |
| **Virtual background.** The toggle exists and the ONNX model is bundled. | 3–5 days of native work, or remove the switch. A toggle that does nothing is the third option and it is the current one |
| **The 62 deleted settings.** `spellcheck`, `highContrast`, `colorblindMode`, `reducedBandwidth`, `taskbarBadge`, `overlayOpacity`, `ttsRate` | Build them, or leave them out permanently. They were settings-shaped prompts to implement a feature later; they are also, today, 62 keys nothing reads |
| **7 settings with a reader and no writer.** `liveKitServiceUrl`, `desktopNotifs`, `joinLeaveSound`, `callRingtone`, `deafenSound`, `use24HourClock`, `dateFormat` | Give each a control, or delete the reader. The ringtone *sounds* procedurally today regardless of the setting, which is its own small lie |
| **`awaitNativeResult(userInitiated)`** | The parameter is unreachable — its only caller never passes it — but collapsing it would silently change the error code emitted for a user-initiated hangup. That is a product question, so I left it |

---

## 12. What I could not verify

Being explicit, because "it compiles" is not "it works".

**Now verified in a real run** (2026-09-30, two accounts, real LiveKit call):

- **A 1920x1080 screen share from Element is now visible.** This was the
  "can't see the stream" report. Run 20 shows the tile holding a live frame for
  the whole share: `tiles=2 withFrame=1 perTrack=[camera/SFF9CQ=<none>
  screenshare/t8bVcs=1280x720]`, and the reader logging `srcWidth=1920
  srcHeight=1080 width=1280 height=720 downscaledFrames=480 read=985
  rateSkipped=505`. Byte rate fell from ~125 MB/s to ~40 MB/s and heap now
  oscillates 68-183 MB instead of climbing. The user confirmed visibility.
  **Caveat, stated plainly:** it worked from the *second* share attempt. The first
  share in that run was still not seen, and the log shows its tile holding a frame
  too, so the flood was necessary but may not be the whole story. See
  "Still unverified" below.
- **Element's shared-window audio now arrives, and the cause was a subscribe race
  Orbit caused itself.** `auto_subscribe = true`, yet run 21 caught the track
  sitting unsubscribed on the server: `screenshare_audio/TR_AsX64VRzkuR4yn=UNSUB`.
  The rescan force-subscribed it on the next tick, it went `=sub`, and
  `MIXSTAT participants` went 1 -> 2 with `inRms=3052`. So this was never an
  Element bug or a protocol limit. The `screenshare_audio` string was absent from
  runs 19 and 20 for the same reason: an unsubscribed publication left no log
  trace at all, so a track Orbit never claimed was indistinguishable from one
  Element never sent.
- **A screen share could be published and never subscribed — the first attempt in
  every run showed nothing.** Same race, video side, and it was left in place by
  the audio fix: the guard read `kind() == KIND_AUDIO &&`, so
  `screenshare/TR_VSHDWz6h52h3pe=UNSUB` sat unsubscribed for three consecutive
  ticks and was then unpublished. It was never `=sub` once in the whole run.
  **The guard now covers both kinds.** Writing a check against the symptom instead
  of the mechanism fixed one instance of a bug and left the identical bug beside
  it.
- Remote audio actually decodes and plays. `inRms=339 outRms=339`,
  `RXSTAT lost=0 jbTargetMs=26`, and `drops=0 catchUp=0 qDepth=3360` for a
  whole call where it previously climbed past 19,000. The mixer catch-up fix is
  measured, not reasoned.
- Roster shows 2 people for a 2-person call.
- Navigating spaces and rooms during a call does not disconnect it. The only
  `endCall` in the log comes from `CallControlsBar`.
- The drawer no longer starts a poller for rooms you merely opened. The
  121-day `roster tick` is gone from the log entirely.

**Still unverified:**

- **Four concurrency fixes have no test.** The frame-store commit lock, the COM
  de-duplication, the ringtone latch, and the readiness cache. Each needs a
  native reader thread, a WASAPI session, or an audio device. For
  `MonotonicEpoch` I could prove the test works by reverting the fix and
  watching it go red; for these four I could not.
- **Avatars, the speaking ring, and the 1:1 P2P path** have still never been
  exercised at runtime. They need a second 1:1 call, not a group call.
- **58 readiness derive sites** still read fields individually. Narrower clobber
  window, not zero. §6 explains why I stopped.
- **The first share of run 20 was still invisible, and the log cannot say why.**
  Its tile read `screenshare/HNUQvu=1280x720` — pixels present — exactly like the
  share that *was* visible four readings later. So "frame in the tile" does not
  imply "on screen", and the renderer is now the prime suspect. Nothing in the log
  records whether an image was actually drawn: the conversion in
  `rememberConvertedRgbaFrame` is fire-and-forget on `Dispatchers.Default` and
  reports nothing. That is the next thing to instrument if it recurs.
- **`publish=5179ms` was reduced by one round trip, not by much.** Measured:
  `verify=430ms via=all-state`. I claimed 1.7s in the commit message and that was
  wrong. Note the run also took `publish=8247ms` versus 5179ms previously, so
  that phase is dominated by matrix.org round trips and network variance, not by
  anything Orbit controls. The remaining two round trips cannot be overlapped:
  the first must pick the reference member event before the payload exists, and
  the publish must land before the state can be read back.
- **The cached microphone probe still has not been seen skipping.** Run 20 was
  again a first call in its session, so it probed all four mixers for 12s and
  `native=14732ms` was unchanged. Two consecutive calls in one session are needed.
  I have now twice built a run that could not exercise the thing I was testing.
- **The screen-share-as-camera race did not lose in run 19.** The classification is
  now correct by construction and 5 tests cover it, but the ordering that exposed it
  has not been observed happening. If the next run shows a share landing as a
  half-size camera tile again, the fix is not in the store — it is that
  `onRemoteVideoTrackAdded` and the first frame are racing somewhere I have not
  looked.
- **The cached microphone probe has not been seen skipping.** It is written to skip
  on the second join in a session; nobody has run two calls back to back yet. Look
  for `microphone self-test reused cached probe`.
- **`publish=5179ms` was reduced by removing one of three round trips, not by
  making anything faster.** The post-publish check re-read all 89 room-state events
  to confirm the one event it had just written; it now asks
  `GET /rooms/{id}/state/{type}/{stateKey}` and keeps the full scan as the fallback
  when the single-event fetch cannot answer. The check itself is unchanged and
  still gates the join. **Corrected:** the measured saving is 430ms, not 1.7s.
- **FIXED: a first screen share now attaches, at ~18 s, with no reconnect.**
  Run 27: `publication trackless for 18s (attempt), renegotiating kind=video
  subscribed=true`, then the track subscribed at 1920x1080 and the tile read
  `screenshare/ZWDm4x=1280x720` for the rest of the call. Both audio and video, on
  the FIRST share, without the stuck prompt or the auto-reconnect ever firing.
  What actually fixed it was not the reconnect but the counter bug two commits
  earlier: the tick counter sat behind `if (!subscribed && trackless)`, so the
  first renegotiate subscribed the publication and froze the counter at 3, and
  the 18 s attempt could never run. **The detail worth remembering: the 6 s
  attempt fired with `subscribed=false` and did nothing; the 18 s attempt fired
  with `subscribed=true` and worked.** The false->true cycle only helps once the
  publication is already subscribed. The stuck prompt at 30 s and the automatic
  reconnect are now a safety net for the case where all three attempts fail.
- **A screen share's VIDEO track used not to recover where its AUDIO track did.**
  Run 25 is the cleanest evidence yet, and the renegotiate fix works -- partially.
  Both share publications arrived `=UNSUB/noTrack`; the rescan renegotiated both;
  the audio track went `sub/+track` within seconds and played, and the video track
  stayed `sub/noTrack` for the entire rest of the call. The user reported exactly
  that: "stream audio started, no stream". Only a hang-up and rejoin rescued the
  video. **The single renegotiate attempt is therefore not enough for video**, and
  the bound is now 3 attempts at 6 s / 18 s / 36 s rather than one. Unverified: no
  run has yet seen the second and third attempts fire.
- **After the video track finally appears, the first frame is a long wait.** Run 25
  call 2: the reader started, the tile existed, and the tile read `<none>` for
  roughly 23 two-second samples before the first frame arrived. The track is known
  and the reader is running, so this is `stream->read()` returning nothing while the
  subscription sits half-completed server-side. Same family as the trackless bug and
  possibly the same fix, but not yet proven.
- **The microphone probe cache is verified.** `reused cached probe ... skipped
  12000ms`, and the join went from `native=14290ms total=24676ms` on the first call
  to `native=1872ms total=8051ms` on the rejoin in the same session. 16.6 s saved,
  which is the whole of the probe plus the difference in SFU warm-up.
- **FIXED (unverified): the outbound screen share was never given the budget the
  inbound one got, and it starved call audio.** Inbound video is capped at 20 fps /
  1280 px; publishing was not capped at all, and the stored setting was 60 fps at
  1440p -- ~186 MB/s of I420 conversion on the same box as the mixer. Run 21
  measured `starved` 10 -> 283 and `qDepth` 3360 -> 480 (the 10 ms floor) while
  sharing, with playback dropping chunks. Two fixes: the publish ceiling is now 30
  in all three places it can be set, and the mixer thread raises itself to
  THREAD_PRIORITY_TIME_CRITICAL (falling back to ABOVE_NORMAL, and a no-op off
  Windows) because it is the only thread in the process with a hard 10 ms
  deadline. **Neither has been run yet** -- this is the next thing a run proves or
  disproves.
- **Sharing your own screen starves call audio.** Run 21, during Orbit's own
  outbound share: `starved` 10 -> 307, `drops` 8160 -> 11520, and playback
  `droppedOldest=183` with `play.arrived=582 play.played=489`. The mixer queue
  collapsed from 1920 to 480 samples. It happens while the *local* screen share is
  publishing, not on the remote one, and the call audio is what suffers — so in a
  group call someone talking to you stutters the moment you share. Not yet
  diagnosed; the obvious suspects are the 60 fps 1920x1080 I420 publish path and
  the share-audio loopback thread competing with the mixer for the same cores.
- **`ActivateAudioInterfaceAsync failed hr=0x8000000e` on this machine**, so
  share audio falls back to full-device loopback and captures Orbit's own
  playback. It ducks to 0.3 to limit the echo and the AEC unlocks again almost
  immediately, so it is a mitigation, not a fix. Process loopback is unavailable
  on this Windows build; there is no code-side answer beyond the duck.

---

## 13. The known-unimplemented list, worked through

Six commits against the §6 backlog. The theme that keeps repeating is worth
stating once: **the bug was almost never where the symptom appeared.** Four of
these looked like one thing and were two or three.

### 13.1 Avatars: three bugs wearing one costume

The report was "1:1 calls show a seed colour". Reading the code found three
unrelated causes, and fixing any one of them would have changed nothing visible:

1. The group "You" tile called `profileForIdentity(selfId)`, but
   `requestProfilesFor` was only ever fed *remote* participants and track owners.
   Nothing requested a profile for our own identity, so that lookup could only
   return null. Our own tile was a circle with "Y" in it in every group call,
   permanently. The usual reason a tile lacks an avatar — we never saw the peer
   publish a member event — does not apply to us at all.
2. `CallScreen`, the P2P surface, had no parameter to receive an avatar. It built
   its two tiles from a name and one hard-coded red.
3. The fallback colour was a six-entry palette in the group surface and a single
   constant in the P2P one. Fixed one, left the identical one beside it — which
   is the exact pattern that produced §5's removals in the first place. Both now
   come from one palette in `CallTileIdentity.kt`.

The peer's avatar comes from `resolveDmContacts`, **not** from the channel's
`avatarUrl`. For a non-DM room that field is the *room's* picture, and putting it
on a person's face tile would be a different kind of wrong.

### 13.2 The reconnect watchdog had two bugs, and the test found the second

`Reconnecting` fell through to the `else` branch of `evaluateDeadlines`, which
returns `None` unconditionally — so a dead socket advertised "Reconnecting…" while
the reconnect loop kept firing and the token kept refreshing, forever.

Adding the deadline alone would have shipped **worse than the original**.
`stageEnteredAtMs` is stamped on every forward stage transition *except* this one,
so the deadline would have been measured from the original join: the instant a call
dropped, elapsed time was already past 90s and every transient network blip would
have become a failed call. The first run of the new tests failed with
`expected None but was GiveUp` at 85s, which is what exposed it.

And the stamp must be on the *transition only*. The readiness callback arrives
repeatedly while a reconnect is in flight; re-stamping each time would reset the
clock forever and the deadline would never be reached.

Each half is independently pinned: reverting the deadline fails one test, reverting
the stamp fails two more.

### 13.3 Seven settings with a reader and no writer

Not missing *controls* — missing **setters**. Each is read by real code and
honours the value correctly, so the app behaved as though it were configurable
while the only reachable value was the default, forever. No way to turn the
ringtone off. No way to ask for a 24-hour clock. No way to point Orbit at your own
SFU.

Auditing the whole file afterwards rather than fixing the seven I was handed found
two more — `spellcheck` and `alwaysOnTop` — where the *reader* did not exist
either. Both deleted. The class is now empty: **zero settings with a reader and no
writer, and every reader has a UI control.**

Two bugs surfaced while building the controls:

- **`"D MMM YYYY"` rendered as `07 MAR 2026`.** `LocalDate.month.name` is the enum
  constant. That branch has always been wrong and nobody had seen it, because the
  setting that selects it could not be selected from anywhere. Turning the setting
  on is what exposed it.
- **`trimEnd('/')` turns a typed `https://` into `https:`.** Caught by a test on
  the SFU validator I had just written. The trailing-slash trim now stops at the
  scheme boundary.

The date picker reads its options from `DATE_FORMATS` beside the renderer instead
of a hand-typed list in the settings pane. `formatDate` has no `else -> error`, so
a pattern with no arm behind it is not a crash — it is a control that takes a tap
and does nothing.

### 13.4 The banner: the note was half wrong

§6 said "Banner URL: parsed and rendered nowhere". Checking rather than fixing
first showed that was two separate claims, one true and one false.

**True:** `m.room.banner` — the standard Matrix room banner that Element and
everyone else sets — was never read. The consequence was not a missing feature but
dead code: `ServerHeader` carries a complete banner layout behind
`hasBanner = server.bannerUrl != null`, and `OrbitServer.bannerUrl` was never
assigned anywhere. Reviewed, paid for, never once executed.

It now reads from the same cached `GET /rooms/{id}/state` the voice probe and the
room topic already read, so it is the third feature on one response and adds no
request. Nineteen tests, mostly about not throwing: the state array is untrusted
input and the `jsonObject` assertion in `isVoiceRoom` already threw a real crash
that took the whole drawer down.

One of those tests caught a live defect in the new code: `convertMxcUrl` is a
pass-through for anything that is not an mxc URI, so a malformed banner came
straight back out and went to Coil, which cannot load it and fails *silently* —
a blank rectangle indistinguishable from a room with no banner, and therefore never
reported by anyone.

**False:** the profile banner saved and previewed correctly all along. What was
wrong there was different — it demanded a pasted URL, and the comment said no
upload was wired up "because reusing `uploadAvatar` would overwrite the avatar".
That reason was stale: `uploadBanner` exists precisely to avoid it, and had no
caller in the entire app. There is now an Upload button, and `uploadBanner` returns
the *converted* http URL rather than the raw mxc, because the value goes straight
into Coil and Coil cannot read `mxc://`. The caption now says the banner is shown at
the top of that page and not yet displayed to other people.

### 13.5 Claims that were checked once and left as fact

`injectVideoProcessor` (virtual background) and `setAttenuationStrength` (Ducking
Strength) were recorded as "no implementation in any shipped DLL". True when
someone ran it, true today — the scan finds 222 `Java_dev_onvoid` exports and both
symbols absent — but a claim nobody re-runs is a guess with a date on it.
`NativeSymbolAvailabilityTest` now reads the shipped binary on every test run and
fails if either symbol appears, with a message saying what to do when it does. It
carries a positive control asserting it is scanning the DLL that actually gets
loaded, and it fails loudly if the resource is not on the classpath: a check that
cannot see anything must not report that it confirmed something.

`callRenderThumbnail` / `callFlagDirectChatForRoom` were listed as "called into
APIs that don't exist". They are not called anywhere. Entry deleted rather than left
to be re-derived.

### 13.6 The grid was never overflowing

`nodes.chunked(2)`, every row at `weight(1f)`, no limit. Ten participants meant five
rows each taking a fifth of the height — which is why this read as a minor cosmetic
issue for as long as it did. Nothing spilled off-screen; every tile just shrank to
a sliver while still being composed and still being handed frames. A busy call
looked broken rather than crowded.

Now capped at four rows. The slicing is extracted as `gridRowsFor` and pinned by
eight tests, because a cap that only exists inside a composable is a cap nobody will
notice being widened.

### 13.7 The screen-share dropout: partially fixed, and the rest is measured

This one took three runs to get right, and the reason it took three runs is worth
recording, because **two of the three conclusions I drew from the counters were
wrong.**

**What the counters said, and what the user heard.** Runs 28 and 29 both showed
`starved` climbing to 98–607 and `qDepth` pinned at 480 the instant a share
started — the exact signature of the original bug. The user said: *"a slight drop
the moment I started streaming, then it seemingly was fine."*

That one sentence changed the target twice:

- Everything *after* the first few seconds was a counter artifact nobody could
  hear. `qDepth` sits at 480 for the rest of a call and it does not sound bad.
- So the defect was never "sharing is expensive". It was "one second at share
  start".

**Wrong turn 1 — the peer's mute.** Run 29 looked like "Element muted, so the
empty mixer starved". The user said *"actually I muted from Element"*, which was
true, and I accepted it. Reading the whole log rather than an excerpt then showed
the ordering that matters: the mixer collapse is timestamped at `ts=...461963` and
the mute sits between that and `ts=...466952`, with `recv` still climbing (422 →
648) through it. The collapse preceded the mute by half a second, with audio
flowing. **The mute was a reaction, not the cause.**

**Wrong turn 2 — frame rate and resolution.** I capped the publish at 30 fps and
predicted resolution was the cause. Run 30 tested it directly, 1440p against 720p:

| Share | Frames | Queue overflow |
|---|---|---|
| A | 1920×1080, 3.11 MB | yes |
| B | 1280×720, 1.38 MB | yes |
| C | 1920×1080, 3.11 MB | yes |

Bandwidth dropped as expected and the overflow was identical. Resolution is not
the cause. The 30 fps cap is kept — it is correct on its own merits — but it did
not fix this.

**What was actually there.** Desktop enumeration happened **four** times in
`WebRtcClientImpl.jvm.kt`: to fill the share picker, again in `startScreenShare`
moments later, and then twice more inside `trackSharedWindow` — the last of which
built and disposed a `WindowCapturer` **every two seconds for the whole share**.
Each of those walks every monitor and every top-level window through COM.

Measured after the fix:

```
desktop sources enumerated n=9 in 52ms    ← once, at call connect
screen share prewarmed in 182ms           ← at call connect
desktop sources from cache  ×4            ← picker and click: no enumeration
share source resolved id=0 in 0ms         ← the click itself
```

The button went from about a second of COM walking to **0 ms**.

**And it was still not the cause.** The collapse survived the fix intact:
`starved` 2 → 86, `qDepth` 2880 → 480, `pushMaxMs` 64 → 207 at the same instant.
So the duplicate enumeration was real waste worth deleting — twice over, given the
2-second poller — but it was not the mixer.

**What is left, and it is small.** The gap between the first two share frames,
every share, both measured runs:

| Share | #1 → #2 | #2 → #3 | #3 → #4 |
|---|---:|---:|---:|
| 1 | **196 ms** | 45 ms | 42 ms |
| 2 | **239 ms** | 42 ms | 42 ms |

A ~200 ms stall after the first frame, then a normal ~22 fps cadence. That is the
shape of a video encoder building itself on the first frame it has to encode, and
it is very likely where the 207 ms of scheduling delay lands. **There is no log
line for encoder initialisation anywhere in the codebase**, so this is a hypothesis
with a measurement attached, not a diagnosis. The user's verdict after the fix:
*"theres still a lil well anyways its not that big of an issue"* — a small residual
click, down from a clear one-second dropout.

Also noted: heap reached **345 MB** during a share at 1440p, against a 2 GB cap.

### The lesson worth keeping

Three runs, three hypotheses, and the counters pointed at a plausible story each
time. What actually settled it was the user describing a sound, and one of the
three things I was confident about turned out to be the peer's microphone being
muted by hand. **A counter that moves when something bad happens is not evidence
that the thing you are looking for caused it** — and a symptom report from someone
who can hear the audio beats any amount of log archaeology.

---

## 13.8 Soundboard in a 1:1: not a bug, a wrong assumption about which calls exist

The notes have carried "Soundboard in 1:1 — local-monitor only" as a feature gap
for several sessions. Reading the code to build it showed the premise was wrong.

**Every call Orbit STARTS is a LiveKit group call.** `CallManager.beginCall` sets
`isGroupCall = true` (line 517) regardless of how many people are in it, and the
LiveKit capture path is exactly where the soundboard bus is folded into outgoing
audio (`LiveKitNativeAudioCapture.kt:721`). So a 1:1 that Orbit starts works
perfectly — the bus, the SFU, the everybody-hears-it behaviour, all of it.

The broken case is narrower: **a 1:1 that arrives through legacy WebRTC
signalling.** That takes a completely different capture path, inside the prebuilt
`webrtc-java-orbit` native audio device module.

### Why it cannot be fixed in this repository

| Route | Why not |
|---|---|
| Add a JNI symbol to the WebRTC DLL | `webrtc-java-lib/src` contains **zero** `.cc`/`.cpp` files. The DLL is a 21.7 MB prebuilt binary and there is no WebRTC C++ here to rebuild. The only buildable C++ is our own three files |
| `HeadlessAudioDeviceModule` | Would be ideal — drive the mic from the JavaSound loop that already exists and mix the bus in exactly where group calls do. In this binding it is a **dead stub**: its only member is a private `initialize()`. No push method exists |
| Mutate bytes in `AudioSink.onRecordedData` | It exists, and it is an **observer**, not an injector. WebRTC hands the sink a const pointer; changing the array would not change what is encoded |
| Route 1:1s through MatrixRTC instead | Would work immediately — it is a group call with two people. But it is a **protocol change**: the call becomes visible to other clients as a different kind of call, interop with clients that only ring legacy 1:1 changes, and it changes the signalling Element uses. That is a product decision, not a fix |

### What was done instead

The panel now **says** it cannot work, rather than playing the effect into your own
speakers while the other person hears nothing.

Hiding the button outright was the alternative and is worse. A control that
vanishes is a question with no answer available; one that explains itself is an
answer, and the panel is where somebody has already decided they want the feature.
The explanation names the fix that does exist — start the call from the room, or
join one already running — because "not supported" without a next step is the same
dead end in nicer words.

**Not verified by a run.** This is a UI branch with no logic to assert on; it needs
an incoming 1:1 and an eye on it.

### 13.9 Message caching: built, and what it was actually missing

Deferred twice. Worth recording that "there was no caching" understated it:

`getMessagesForChannel` calls `trixnity.room.getTimelineEvents`, which is a
network call to `/messages`, on **every room open**. Trixnity keeps its own SQLite
store, but nothing ever read messages out of it. So every room cost a round trip
and a spinner, and a room that could not be reached rendered as an **empty room** —
which is indistinguishable from a conversation with nothing in it.

- First page served from `~/.orbit/cache/rooms/`, written as messages arrive via
  the sync path rather than only when a room is reopened.
- A scrollback page still comes from the server — it is anchored to an event id,
  and the server is the only thing that knows what sits before it. It is merged
  *into* the cache so the room keeps its depth, but never served *from* it.
- A failed refresh returns the cache. That is the whole point: a stale room is
  wrong in a way you can see and correct; an empty room is wrong in a way you
  cannot.
- Cleared on logout. Cached messages are readable with no session at all, which is
  what makes them useful when the network is down and exactly why they must not
  outlive the account.

JSON files rather than the SQLite already on the classpath: the existing database
is Room plus the AndroidX bundled driver, shaped for an annotated DAO rather than
hand-written SQL, and the cache is small, bounded, disposable and inspectable.
Writes are write-then-rename so a crash mid-write leaves the previous good file.

**The bug the round-trip test found on its first run.**
`MessageContent.Unsupported` has a property named `type`, which is
kotlinx.serialization's reserved class discriminator for sealed hierarchies, so it
throws on serialisation. The cache write swallows exceptions — correctly, because
losing a cache must never fail a room — so every message of that type would have
quietly stopped the **entire room** from ever caching, with nothing anywhere
saying so. It would have looked like a sync bug or a lost message. Fixed with
`@SerialName("matrixEventType")`; the Kotlin property name is unchanged.

Sixteen round-trip tests, one per content variant, including the two recursive ones
where a mistake is hardest to spot: a `Reply` coming back as plain text would
still render the right words while losing the quoted message and its accent bar.

---
## 14. Still open after this pass

- **A ~200 ms audio click at the start of every screen share.** Down from ~1 s;
  see §13.7. Suspected video-encoder startup, **not instrumented**. The honest next
  step is a timing log around encoder creation in the share publish path, not
  another guess.
- **Heap reaches ~345 MB while sharing at 1440p.** Under the 2 GB cap, and it does
  not appear to climb without bound, but it is higher than it should be.
- **`VM attach current thread failed`** on the AWT thread at hangup, after cleanup
  has already completed twice. Cosmetic, and it happens on every run.
- **Message caching.** **Built.** Rooms are served from `~/.orbit/cache/rooms/`
  and written as messages arrive; see §13.9. A failed refresh returns the cache
  rather than an empty room.
- **Soundboard in a legacy-signalling 1:1.** The panel now explains itself instead
  of playing a sound only you can hear. The real fix needs native work this
  repository cannot do. See §13.8.
- **The 58 `readinessCache` derive sites.** Unchanged; §6 covers why.
- **`ActivateAudioInterfaceAsync failed hr=0x8000000e`** — process loopback is
  unavailable on this Windows build, so share audio captures Orbit's own playback.
  Ducking to 0.3 limits the echo; it is a mitigation, not a fix, and there is no
  code-side answer. It also means a share *with* audio captures Orbit's own
  playback back, which is a feedback loop that no setting can currently prevent.
- **The outbound-share starvation fix has now been run, three times, and is only
  partly fixed.** See §13.7 for the measurements, the two wrong turns, and what is
  still outstanding. Read that section rather than §12 on this.