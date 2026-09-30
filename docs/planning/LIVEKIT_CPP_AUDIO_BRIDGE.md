# LiveKit C++ Audio Bridge

This branch moves the MatrixRTC/LiveKit audio path away from direct Kotlin/JNA FFI calls and behind a small JNI bridge backed by the LiveKit C++ SDK.

## Scope

- Keep Matrix call discovery, membership events, and token fetching in Kotlin.
- Connect and disconnect LiveKit rooms through the native bridge.
- Publish Orbit microphone PCM to LiveKit through a native `AudioSource`.
- Receive remote LiveKit audio in native code and hand PCM frames back to Kotlin for playback.
- Leave existing LiveKit video behavior unchanged until audio is stable.

## Non-goals

- Replacing the classic Matrix one-to-one WebRTC path.
- Rewriting camera or screen-share publishing in this phase.
- Changing MatrixRTC state event semantics.

## Milestones

1. Add native bridge build scaffolding.
2. Add Kotlin JNI facade and feature flag (`orbit.livekit.cppAudio`).
3. Move LiveKit connect/disconnect lifecycle to native.
4. Route local microphone frames to native audio source.
5. Route remote audio frames back to JVM playback.
6. Remove direct raw-FFI audio capture from the JVM group-call path.
