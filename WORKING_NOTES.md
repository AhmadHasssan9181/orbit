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

`README.md`, `headache_medicine.md`, `implementation_plan.md` and
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

Honest list, re-verified against the code on 2026-09-30 rather than trusted
from an earlier pass. Two items that used to be here are gone
(`switchCamera()` has no definition anywhere in `app/src`; the roster
reconciliation and the join-call banner are fixed and deleted respectively).
Everything below is confirmed still open by reading the code or a run log.

### Costs the user something today

| Thing | State | Evidence |
|---|---|---|
| ~~**Video heap**~~ | **Fixed for remote video, see §4.** The reader forwarded every frame at full size: 1920x1080 RGBA is 8.29 MB, the SFU pushed 28.8 fps, so ~237 MB/s of JNI `byte[]`, amplified ~4x by the UI into a `BufferedImage` raster and a Skia `Bitmap`. Now averaged down to a 1280 px long edge in native and capped at 20 fps | run 19, `vid.remote n=79 mb=624` in one 5 s window; `seq 60 -> 120 in 2080 ms` |
| **Key-exchange convergence sweep never gives up** | `convergence sweep: resending key index=N to 1 underdelivered device(s) ([@nfak_ism:matrix.org:ZVK65HTOZW])` every 3s for the whole call, then `deferring 1 device(s) without stored keys` — the same device, immediately after deferring it. The loop has no attempt cap or backoff | run 14, every run |
| **Avatars in 1:1** | The local "You" tile and the whole P2P path never resolve `avatarUrl`, so 1:1s fall back to a seed colour next to group tiles | read 2026-09-30 |
| **Chat history stops at 50** | The repository's `fromToken` is correct; the UI never supplies one, so there is no scrollback | read 2026-09-30 |
| **Room topic is never read back** | `createRoom` writes `m.room.topic`, and no code path parses it back into `OrbitChannel.topic`. `ChatHeader` renders the subtitle correctly and it is always blank | grep: `topic =` appears only on the create path |
| **`raise_hand` does nothing** | `sendCallEvent` is a `when` over trixnity's *closed* `CallEventContent` — only `m.call.invite/answer/negotiate/candidates/hangup`. `io.orbit.raise_hand` is not a case, so the button logs `Unknown call event type` and stops | `MatrixCallsRepository.sendCallEvent` |
| **`OrbitVideoEngine.injectVideoProcessor`** | No implementation in any shipped DLL (verified: sibling symbols are present). Also why the virtual background is inert | DLL symbol check |
| **`OrbitAudioDeviceModule.setAttenuationStrength`** | Same. Also why **Ducking Strength is inert in group calls** | DLL symbol check |
| No way to *start* device verification | `VerificationOverlay` handles an incoming request and SAS completion correctly, but nothing ever calls `verification.requestVerification()`. You can verify when asked; you can never ask | read 2026-09-30 |
| `CallGrid` tile overflow | Tiles stack unbounded past the grid | read 2026-09-30 |
| Reconnect watchdog | `GroupCallConnectionTracker.Reconnecting` has no timeout, so a dead socket reconnects forever | read 2026-09-30 |
| Soundboard in 1:1 | Local-monitor only. In a group call it is broadcast; in a P2P call you hear yourself | read 2026-09-30 |
| Banner URL | Parsed and then rendered nowhere | read 2026-09-30 |
| `callRenderThumbnail` / `callFlagDirectChatForRoom` | Called from `WebRtcEffects` into `trixnity` APIs that don't exist | read 2026-09-30 |

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

Everything is staged, nothing is committed, nothing is pushed. That is
deliberate: the index is what made the `Set-Content` corruption recoverable.

Be careful with `git add -A` here. The repo tracks ~1,900 committed `.class`
files under `webrtc-java-lib/build/` (pre-existing, unrelated to any change here)
plus some root-level scratch files that `.gitignore` does not cover because they
were committed *before* the ignore rules existed.

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
`m.room.topic` is written on create and never parsed back. That is a repository
gap, not a UI gap; it is in §6.)

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
- **Element's shared-window audio is still inaudible, and the track never
  arrives.** `screenshare_audio` appears nowhere in run 20; the only audio track
  Element published was a microphone. `MIXSTAT participants=1` throughout, so only
  one remote queue ever existed. The user could hear the peer's mic, so the audio
  path itself works — the screen-audio track is simply not being received. Two
  gaps closed this round (both in the rescan/publish handlers) but **this is not
  fixed**; see §4.
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
- **Element's shared-window audio does not arrive, and I cannot yet say whose
  fault that is.** The mixer already downmixes stereo to mono correctly
  (`pushParticipantFrame` averages channels), so a track that arrived would play.
  `auto_subscribe = true` and there is no `TrackSubscription` filter, so Orbit asks
  for everything. The track is simply never seen. Two observability gaps are now
  closed — `onTrackPublished`'s audio branch was completely silent, and the rescan
  only logged when it *started* something, so a handled track and a missing track
  looked identical. **The next run decides it:** if `remote publication inventory`
  shows no `screenshare_audio` entry, Element never sent it and the bug is not
  Orbit's; if it shows `=UNSUB`, the rescan now force-subscribes it once and
  should recover.
