# Orbit LiveKit Group Call — Master Implementation Plan
## Hybrid Architecture: Control Plane (JNA/Kotlin) + Data Plane (C++ JNI)

---

> [!IMPORTANT]
> ## 🤖 Agent Instructions — Read This First
>
> This document is your single source of truth for the LiveKit integration. Follow these rules strictly:
>
> 1. **Work in order.** Complete steps top-to-bottom. Do NOT skip ahead. Each step depends on the previous one compiling successfully.
> 2. **Tick off steps as you go.** When you finish a step, change `- [ ]` to `- [x]` in this document so progress is tracked.
> 3. **Verify before moving on.** After each step, confirm the project still builds and the existing 1-on-1 calls still work. Never break the current system.
> 4. **Never touch these files** unless a step explicitly says so: `CallManager.kt`, `WebRtcClientImpl.jvm.kt`, `DesktopVideoRenderer.kt`, `WebRtcEffects.kt`. The 1-on-1 call system must stay operational at all times.
> 5. **When stuck**, document the exact error and stop. Do not attempt workarounds that touch files outside the `livekit/` folder without user approval.

---

## Architecture Overview

This integration is split into two completely separate lanes that run in parallel during a group call:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  CONTROL PLANE (Kotlin + JNA)                                           │
│  Handles: Signaling, authentication, room state, participant roster     │
│  Files: LiveKitClient.kt, LiveKitFfiLibrary.kt, MatrixCallsRepository  │
└──────────────────────────┬──────────────────────────────────────────────┘
                           │ fires events, sends requests
┌──────────────────────────▼──────────────────────────────────────────────┐
│  DATA PLANE (C++ JNI)                                                   │
│  Handles: Video frame memory, I420 → ARGB conversion, local camera push │
│  Files: orbit_livekit_video.cpp, CMakeLists.txt                         │
└─────────────────────────────────────────────────────────────────────────┘
```

> [!NOTE]
> **The current 1-on-1 call system (webrtc-java) is completely untouched.**
> All LiveKit group call code lives inside a brand new isolated folder:
> `d:\Onichat\app\src\jvmMain\kotlin\livekit\`

---

## What Already Exists (From `C:\live_kit_hell`)

These files were already written and will be ported directly into Orbit:

| File | Location in `live_kit_hell` | Status |
|---|---|---|
| `LiveKitFfiLibrary.kt` | `jvmMain/kotlin/livekit/` | ✅ Exists — needs GC crash fix |
| `LiveKitClient.kt` | `jvmMain/kotlin/livekit/` | ✅ Exists — needs GC crash fix + thread fix |
| `LiveKitWrapper.kt` | `commonMain/kotlin/livekit/` | ✅ Exists — stub only |
| `livekit_ffi.dll` | `jvmMain/resources/webrtc-deps/` | ✅ Binary exists — needs to be ported |
| Proto generated sources | `jvmMain/kotlin/livekit/proto/` | ✅ Exists in generated sources |

---

## Phase 1: Stabilize the Control Plane (JNA)

### Step 1.0 — Port LiveKit Files into Orbit

- [x] Copy `C:\live_kit_hell\app\src\jvmMain\kotlin\livekit\` → `D:\Onichat\app\src\jvmMain\kotlin\livekit\`
- [x] Copy `C:\live_kit_hell\app\src\commonMain\kotlin\livekit\` → `D:\Onichat\app\src\commonMain\kotlin\livekit\`
- [x] Copy `C:\live_kit_hell\app\src\jvmMain\resources\webrtc-deps\livekit_ffi.dll` → `D:\Onichat\app\src\jvmMain\resources\webrtc-deps\livekit_ffi.dll`
- [x] Copy any proto-generated sources from `live_kit_hell` into the same path in Orbit
- [x] Add the `livekit` package to `build.gradle.kts` dependencies if needed
- [x] **Verify:** Run `.\gradlew assemble`. The project must build. Existing 1-on-1 calls must still work.

**Goal:** Orbit compiles. LiveKit code quietly sits inside its own namespace doing nothing yet.

---

### Step 1.1 — Pin the Native JNA Callback (Fix the `hs_err` Crash)

- [x] Extract the `FfiCallbackFn` instantiation out of `initialize()` into a `companion object` permanent `val`
- [x] Pass `PINNED_CALLBACK` into `livekit_ffi_initialize` instead of the local variable
- [x] **Verify:** Run the app. No `hs_err_pid*.log` files should appear in `D:\Onichat\`

**Goal:** JVM never destroys the callback. The fatal crash is eliminated.

---

### Step 1.2 — Isolate the Network Thread (Fix the Deadlock Risk)

- [x] Clone the byte array immediately upon callback entry
- [x] Wrap ALL event processing in `eventScope.launch(Dispatchers.Default)`
- [x] Wrap the entire body in a top-level `try/catch(Throwable)` so nothing propagates to native
- [x] **Verify:** Run the app. Connect to a test server. No freezes or deadlocks.

**Goal:** The native Rust thread is freed within microseconds. All processing happens safely in Kotlin coroutines.

---

## Phase 2: Matrix MSC3401 Handshake (Getting the Keys)

### Step 2.1 — Parse MSC3401 State Events

- [x] In `observeActiveGroupCall()`, find the existing `org.matrix.msc3401.call` parsing block
- [x] Extract the `m.foci_preferred[0].livekit_service_url` field from the event content JSON
- [x] Store the extracted `wss://...` URL alongside the call ID in the flow value
- [x] **Verify:** Add a `println` to confirm the URL is correctly extracted when an Element call is detected

**Goal:** Orbit knows exactly which LiveKit server to connect to for this room.

---

### Step 2.2 — Negotiate the SFU JWT Token

- [x] Create a new file `LiveKitTokenProvider.kt` in the `livekit` package
- [x] Add a `suspend fun fetchToken(roomId: String, homeserverUrl: String, accessToken: String): String` function
- [x] Inside that function, use Ktor to make an HTTP POST to the Matrix SFU endpoint
- [x] Parse the returned JSON to extract the signed JWT string
- [x] Return the JWT string from the function
- [x] **Verify:** Write a test call with a known room ID and `println` the returned JWT to confirm it is a valid token string

**Goal:** Orbit receives a cryptographic JWT that authorizes it to connect to the LiveKit server.

---

### Step 2.3 — Execute the Connect Request

- [x] Wire `LiveKitTokenProvider.fetchToken()` into the group call join flow
- [x] Pass the resulting `wss://` URL and JWT into `LiveKitClient.connect(url, token)`
- [x] In `handleFfiEvent`, add a handler for `FfiEvent.MessageCase.CONNECT`:
  - On **success**: store the returned `RoomHandle` and `LocalParticipantHandle` in the object state
  - On **failure**: set an error `StateFlow` that the UI can observe
- [x] **Verify:** Trigger a join from the UI. Confirm you see `DEBUG [LiveKitClient] Connect SUCCESS!` in the logs

**Goal:** Orbit successfully joins the LiveKit room. A valid `RoomHandle` is returned, proving the live connection.

---

## Phase 3: The Video Data Plane (C++ JNI Bridge)

### Step 3.1 — Initialize the JNI Native Library

- [x] Create a `cpp/` folder under `jvmMain/`
- [x] Create `CMakeLists.txt` that defines a shared library target linking against `livekit_ffi`
- [x] Create `orbit_livekit_video.cpp` with a basic hello-world JNI function
- [x] Hook the CMake module into Gradle's build pipeline
- [x] Create `LiveKitVideoSink.kt` that calls `System.loadLibrary("orbit_livekit_video")` and declares the `external fun nativeInit()`
- [x] **Verify:** Run `.\gradlew assemble`. The C++ must compile. Call `nativeInit()` from Kotlin and confirm no crash.

**Goal:** The C++ bridge compiles and loads cleanly into the JVM. We have a verified JNI handshake.

---

### Step 3.2 — Intercept Tracks via the Control Plane

- [x] In `handleFfiEvent`, add a new handler for `FfiEvent.MessageCase.TRACK_SUBSCRIBED`
- [x] When the subscribed track is a **video** track, extract its `TrackSid` string
- [x] Call `LiveKitVideoSink.nativeAttachTrack(trackSid)` to hand the track ID to C++
- [x] When the subscribed track is an **audio** track, log it for now (audio routing comes in Phase 4)
- [x] **Verify:** Join a call from Element. Confirm you see the `TRACK_SUBSCRIBED` log firing in Orbit

**Goal:** The Kotlin Control Plane detects the remote video stream and hands its ID to the C++ Data Plane.

---

### Step 3.3 — Attach the Native Video Stream in C++

- [x] In `nativeAttachTrack`, look up the track by `TrackSid` using the LiveKit C++ SDK
- [x] Attach a `livekit::VideoStream` listener to the track
- [x] Spin up a dedicated C++ thread that continuously reads decoded I420 frames
- [x] Add a `printf` that logs `"C++: Got frame %dx%d"` so we can verify frames are flowing
- [x] **Verify:** Join an Element call with a camera on. Confirm C++ frame logs appear at 30+ fps.

**Goal:** C++ is continuously receiving decoded raw I420 video frames from the remote participant.

---

### Step 3.4 — Direct Memory Access (DMA) Transfer to Kotlin

- [x] In Kotlin's `LiveKitVideoSink`, allocate a `ByteBuffer.allocateDirect()` sized for ARGB
- [x] Pass the buffer's raw native memory address to C++ via a JNI call using `GetDirectBufferAddress`
- [x] In C++, write the Y plane, then U plane, then V plane bytes directly into the address — zero allocation, zero copy
- [x] Expose a `StateFlow<ByteBuffer>` from `LiveKitVideoSink` that emits every time a new frame is written
- [x] **Verify:** Observe the `StateFlow` in Kotlin. Confirm the buffer is non-zero and changing on each emission.

**Goal:** 60fps video frames flow from C++ native memory directly into Kotlin-accessible memory with zero GC pressure.

---

### Step 3.5 — Build the LiveKit Compose Renderer

- [x] Create a new Compose component `@Composable fun LiveKitVideoRenderer(sink: LiveKitVideoSink)`
- [x] Inside the component, collect the `sink.frameFlow` as Compose state
- [x] Convert I420 bytes to ARGB format using a fast loop (re-use the pattern from `DesktopVideoRenderer.kt`)
- [x] Create an `ImageBitmap` from the ARGB array
- [x] Render the bitmap with Compose's `Image()` composable, filling the available space
- [x] Use `LaunchedEffect` or `rememberUpdatedState` to ensure recomposition fires on each new frame
- [x] **Verify:** Open a group call. A live video feed from the remote participant should appear on screen.

**Goal:** Full remote video renders inside Orbit's Compose UI at up to 60fps.

---

## Phase 4: Sending Local Media (Orbit → LiveKit)

### Step 4.1 — Intercept the Orbit Camera Capture Loop

- [x] In `startLocalMedia(isVideo: Boolean)`, add a check: if the current call is a group call, skip the standard `webrtc-java` path
- [x] Instead, call `LiveKitLocalTrack.startCapture()` to begin routing camera frames to LiveKit
- [x] **Verify:** Start a group call. Confirm camera frames are captured but NOT sent through `webrtc-java`

**Goal:** Local camera is captured and its frame data is routed to the LiveKit pipeline instead of the existing peer connection.

---

### Step 4.2 — Create the Native Video Source in C++

- [x] Expose a JNI function `nativePushVideoFrame(ByteBuffer frameData, int width, int height, long timestampUs)`
- [x] Inside C++, instantiate a `livekit::VideoSource` (Implemented via FFI request in Kotlin/C++ helper)
- [x] In `nativePushVideoFrame`, read the Direct `ByteBuffer`, wrap it as a LiveKit `VideoFrame`, and call `onFrame()`
- [x] **Verify:** Call `nativePushVideoFrame` from Kotlin with a dummy green frame. Confirm C++ receives it without crashing.

**Goal:** C++ has a video source that accepts frames from Kotlin and feeds them into the LiveKit encoder.

---

### Step 4.3 — Pump Camera Frames to C++

- [x] Create `LiveKitLocalTrack.kt` with a `fun startCapture()` function
- [x] Inside, hook into the existing `VideoCapturer` output callback from `WebRtcClientImpl`
- [x] On each frame callback, write the frame bytes into a Direct `ByteBuffer`
- [x] Call `nativePushVideoFrame(buffer, width, height, timestampUs)` on every frame
- [x] **Verify:** Open a group call while watching C++ logs. Confirm frames are arriving at 30fps in C++.

**Goal:** The LiveKit C++ layer receives a continuous live camera feed from Orbit.

---

### Step 4.4 — Publish the Track via the JNA Control Plane

- [x] After `LiveKitLocalTrack.startCapture()` is running, call `LiveKitClient.publishVideoTrack()`
- [x] Inside `publishVideoTrack()`, build and send a `PublishTrackRequest` via the JNA `request()` function
- [x] ~~Repeat for audio: create a `livekit::AudioSource`, hook it into the microphone input, and send a `PublishTrackRequest` for the audio track~~ **SUPERSEDED 2026-08-22** — audio publishing was built natively in `orbit_livekit_audio.cpp` (`nativePublishAudio` with per-call Opus maxBitrate/dtx/red, see CALLS_MASTER_PLAN D5); mic frames flow via `nativePushAudioFrame`. Do NOT build a separate JNA AudioSource path.
- [ ] **Verify:** Join from Element on another device. Confirm Element can see and hear you from Orbit. *(still open — runtime cross-app verification pending for the whole stack)*

**Goal:** Remote participants, including Element users, can see and hear Orbit in the group call.

---

## Phase 5: Room State & UI Synchronization

### Step 5.1 — Sync the Participant Roster

- [x] In `handleFfiEvent`, handle `FfiEvent.MessageCase.ROOM_EVENT`
- [x] Within that, handle subcases `PARTICIPANT_JOINED` and `PARTICIPANT_LEFT`
- [x] Create a `data class LkParticipant(val sid: String, val name: String, val isAudioMuted: Boolean, val isVideoMuted: Boolean)`
- [x] Maintain a `MutableStateFlow<Map<String, LkParticipant>>` that is updated on each join/leave event
- [x] Expose this flow publicly so the Compose UI can collect it
- [x] **Verify:** Have two devices join. Confirm both appear in the map. Have one leave. Confirm the map updates.

**Goal:** A live, accurate roster of everyone in the call, always in sync with the LiveKit server.

---

### Step 5.2 — Active Speaker Detection

- [x] In `handleFfiEvent`, handle the `ACTIVE_SPEAKERS_CHANGED` room event
- [x] Extract the list of currently speaking participant SIDs from the event
- [x] Update a `MutableStateFlow<Set<String>>` with the active speaker SIDs
- [x] In `LiveKitVideoRenderer.kt`, collect the active speakers flow
- [x] If the rendered participant's SID is in the active speakers set, draw an animated glowing border
- [x] **Verify:** Speak into a microphone. Confirm the correct participant's video tile gets the glowing border in real time.

**Goal:** The UI clearly shows who is currently talking with a visible animated speaking indicator.
