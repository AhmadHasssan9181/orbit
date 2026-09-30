# Orbit to Element Call Interop Debug Report

## 1. Overview

This document records the full debugging journey for Orbit to Element audio/video call interoperability using MatrixRTC and LiveKit.

The main goal was:

```text
Orbit user joins an Element call.
Element user joins the same call.
Both sides should hear each other.
Orbit should appear as a proper participant inside Element.
```

The final working result:

```text
Element to Orbit audio: working
Orbit to Element audio: working
Orbit mute stops audio in Element: working
Orbit card/media mapping in Element: working
Test tone from Orbit to Element: working
Real microphone path: working after final verification
```

The biggest discovery:

```text
The core blocker was not C++ audio, not microphone capture, not LiveKit token fetch, and not the AudioFrame format.

The real blocker was MatrixRTC membership shape.
Element did not attach Orbit media until Orbit mirrored the visible Element participant membership shape.
```

## 2. System Architecture

The calling system has these layers:

```text
Orbit Kotlin UI
        |
        | MatrixRTC discovery and membership
        |
Matrix room state events
        |
        | LiveKit JWT token fetch
        |
LiveKit SFU
        |
        | Native media backend
        |
LiveKit C++ SDK / JNI audio bridge
        |
        | PCM audio capture and playback
        |
Element Call / Element Web
```

### Main components

```text
CallManager.kt
Controls call lifecycle and when MatrixRTC membership is published.

MatrixCallsRepository.kt
Reads and writes MatrixRTC call/member state events.

LiveKitTokenProvider.kt
Fetches OpenID token from Matrix and exchanges it for a LiveKit JWT.

LiveKitNativeMediaSession.kt
Controls native media session lifecycle.

orbit_livekit_audio.cpp
Native C++ audio bridge for LiveKit publish/subscribe.

LiveKitNativeAudioCapture.kt
Captures microphone frames or generates test tone.

LiveKitNativeAudioPlayer.kt
Plays remote mixed audio from Element.
```

## 3. Initial Problem

At the start, the behavior was inconsistent:

```text
Orbit could join the call.
Orbit could hear Element.
Element could not hear Orbit.
Element sometimes showed "Waiting for media..."
Sometimes Orbit's card did not appear in Element at all.
```

This created confusion because the media pipeline looked partly alive:

```text
LiveKit token fetch worked.
LiveKit connect worked.
Orbit could subscribe to Element audio.
Orbit could hear Element.
Orbit could publish an audio track.
Chrome/Element received RTP packets from Orbit.
```

But Element still did not play Orbit audio.

## 4. Important Symptom That Changed the Diagnosis

The critical missed symptom was:

```text
When Orbit joined the same room, Orbit's card did not appear in Element.
But Orbit could still hear Element.
```

Meaning:

```text
LiveKit subscribe/inbound path worked.
MatrixRTC participant visibility/mapping was broken.
```

This meant the problem was not only media.

Element needs the MatrixRTC membership event to map a LiveKit participant/track to a visible participant card. If the membership is wrong, Element may receive RTP but not attach or play it as valid call media.

## 5. Test Timeline and Results

## 5.1 MatrixRTC call discovery

Orbit successfully discovered the active Element call:

```text
FOUND LIVEKIT CALL INSIDE DIRECT MEMBER FETCH
LiveKit service URL: https://livekit-jwt.call.matrix.org
callId: m.call
roomId: !MqachzXtDbUPLqAUel:matrix.org
```

Result:

```text
PASS
```

Meaning:

```text
Orbit could find the Element MatrixRTC call state.
```

## 5.2 LiveKit token fetch

Orbit successfully fetched a LiveKit token using the Matrix OpenID flow:

```text
Request OpenID token from Matrix
Exchange OpenID token at livekit-jwt.call.matrix.org/get_token
Receive LiveKit JWT
```

Token request shape:

```json
{
  "room_id": "!MqachzXtDbUPLqAUel:matrix.org",
  "slot_id": "m.call#ROOM",
  "member": {
    "id": "@nfak_ism:matrix.org:reakukDlqU",
    "claimed_user_id": "@nfak_ism:matrix.org",
    "claimed_device_id": "reakukDlqU"
  },
  "openid_token": {
    "access_token": "MATRIX_OPENID_TOKEN",
    "token_type": "Bearer",
    "matrix_server_name": "matrix.org",
    "expires_in": 3600
  }
}
```

Result:

```text
PASS
```

Meaning:

```text
Matrix auth and LiveKit JWT fetch were working.
```

## 5.3 Native LiveKit connect

Native C++ LiveKit connected successfully:

```text
LiveKit C++ SDK initialized
native connect started
native connect succeeded
local participant connected
```

Result:

```text
PASS
```

Meaning:

```text
LiveKit URL and JWT were valid.
C++ SDK could join the LiveKit SFU room.
```

## 5.4 Element to Orbit audio

Orbit could hear Element audio.

Result:

```text
PASS
```

Meaning:

```text
Remote audio subscription worked.
Remote audio reader worked.
Mixer worked.
JVM playback worked.
Element to Orbit direction was functional.
```

## 5.5 Orbit to Element audio failed initially

Element could not hear Orbit.

Early Chrome stats showed:

```text
packetsReceived > 0
bytesReceived > 0
audioLevel = 0
totalAudioEnergy = 0
totalSamplesReceived = 0
packetsDiscarded high
```

Result:

```text
FAIL
```

Meaning:

```text
Element received RTP packets but did not decode/play usable samples.
```

## 6. Microphone and Test Tone Investigation

## 6.1 Microphone capture test

Initial microphone frames were nearly silent:

```text
peak around 1
rms around 0.4
```

This was too low and created a possible false lead.

Fixes added:

```text
Microphone self-test
Input mixer logs
Device selection logs
Optional capture gain
-Dorbit.audio.inputDeviceName=...
-Dorbit.audio.captureGain=...
```

Result:

```text
Mic diagnostics improved.
But this was not the main blocker.
```

## 6.2 Test tone generation

A loud 1 kHz test tone was added.

Expected test tone values:

```text
peak = 12000
RMS = 8485.18
sampleRate = 48000
channels = 1
samplesPerChannel = 480
durationMs = 10
format = int16 signed little-endian
```

Result:

```text
PASS
```

Meaning:

```text
Orbit could generate valid loud PCM.
```

## 6.3 JNI transfer to C++

C++ received the same loud tone:

```text
pushed mic frame
peak=12000
rms=8485.18
allZero=false
acceptingAudioFrames=true
nativeTrackMuted=false
```

Result:

```text
PASS
```

Meaning:

```text
Kotlin to JNI to C++ audio transfer was working.
```

## 7. C++ AudioSource Debugging

## 7.1 Original direct capture problem

Original C++ path had:

```text
captureAudioFrameAsync timed out after 20 ms
```

This suggested concurrent or bad async frame submission.

Fix:

```text
Do not call capture directly from nativePushAudioFrame.
Add native worker queue.
Feed AudioSource at exact 10 ms cadence.
Use backpressure counters.
Prevent concurrent captureFrame calls.
```

Result:

```text
Timeout spam fixed.
```

## 7.2 Worker queue result

Worker counters became clean:

```text
received increasing
submitted increasing
accepted increasing
timedOut = 0
dropped = 0
lastCaptureError = <none>
```

Result:

```text
PASS
```

Meaning:

```text
C++ worker was feeding frames correctly.
```

## 7.3 AudioFrame format audit

AudioFrame format was matched to the official LiveKit C++ shape:

```text
AudioSource(48000, 1, 10)
LocalAudioTrack::createLocalAudioTrack("audio", audioSource)
TrackPublishOptions.source = SOURCE_MICROPHONE
AudioFrame(buffer, 48000, 1, 480)
captureFrame(frame, 0)
one frame every 10 ms
```

Frame values:

```text
buffer.size = 480
totalSamples = 480
sampleRate = 48000
numChannels = 1
samplesPerChannel = 480
duration = 0.01
```

Result:

```text
PASS
```

Meaning:

```text
AudioFrame sample count and PCM format were correct.
```

## 7.4 Worker pre-capture proof

Logs proved the tone reached `captureFrame`:

```text
worker pre-capture frame
peak = 12000
rms = 8485.18
allZero = false
muted = false
trackMuted = false
AudioFrame(buffer.size=480, sampleRate=48000, channels=1, samplesPerChannel=480)
```

Result:

```text
PASS
```

Meaning:

```text
The tone was not lost before LiveKit captureFrame.
```

## 8. Standalone LiveKit C++ Publisher Test

A minimal standalone C++ tone publisher was built.

Purpose:

```text
Remove Kotlin/JNI/Orbit app logic from the test.
Use the same LiveKit URL/token.
Publish a 1 kHz tone directly using LiveKit C++.
```

It connected and published:

```text
connect result=true
room state=Connected
audio track published
pre-capture peak=12000
captureFrame result=ok
```

But Element/Chrome still showed:

```text
packetsReceived > 0
totalSamplesReceived = 0
totalAudioEnergy = 0
audioLevel = 0
packetsDiscarded high
```

Initial conclusion:

```text
Maybe LiveKit C++ SDK path is incompatible or not enough by itself.
```

Later this was revised because JS showed the same mapping issue.

## 9. LiveKit JS Test

A standalone LiveKit JS browser test was created.

Purpose:

```text
Check if LiveKit JS can publish into the same room using the same token.
```

JS connected successfully after using a fresh token:

```text
room connected event
connected room
localParticipantIdentity = 5GDgjx38YzG8elPykQXulmXuiF1aRDOA/hfDPJysArk
tone published
publicationSource = microphone
trackMuted = false
microphone published
```

Element SDP confirmed the JS track reached the Element PeerConnection:

```text
a=msid:PA_bnLmuDcgDQqz|TR_AMeaWcqBDQAzGH TR_AMeaWcqBDQAzGH
a=ssrc:2989827350 label:TR_AMeaWcqBDQAzGH
```

But Element still did not play it correctly until MatrixRTC membership was fixed.

Result:

```text
JS could publish.
Media reached Element.
Still not sufficient without correct MatrixRTC membership mapping.
```

Meaning:

```text
The issue was not only C++.
The issue was Element's MatrixRTC member to LiveKit participant mapping.
```

## 10. MatrixRTC Membership Investigation

## 10.1 Key finding

Orbit could hear Element, but Orbit card did not appear in Element.

This meant:

```text
Orbit was connected to LiveKit.
Orbit could subscribe to Element media.
But Element did not recognize Orbit as a valid MatrixRTC participant.
```

## 10.2 Existing Orbit membership shape

Orbit was publishing modern-looking membership events:

```json
{
  "application": "m.call",
  "call_id": "m.call",
  "device_id": "reakukDlqU",
  "membershipID": "@nfak_ism:matrix.org:reakukDlqU",
  "created_ts": 1780346317844,
  "m.call.intent": "audio",
  "scope": "m.room",
  "expires": 14400000,
  "foci_preferred": [
    {
      "livekit_service_url": "https://livekit-jwt.call.matrix.org",
      "livekit_alias": "!MqachzXtDbUPLqAUel:matrix.org",
      "type": "livekit"
    }
  ],
  "focus_active": {
    "type": "livekit",
    "focus_selection": "oldest_membership"
  },
  "foci_active": [
    {
      "foci_id": "livekit",
      "type": "livekit",
      "livekit_service_url": "https://livekit-jwt.call.matrix.org",
      "livekit_alias": "!MqachzXtDbUPLqAUel:matrix.org"
    }
  ],
  "capabilities": {
    "audio": true,
    "video": false
  }
}
```

Element did not show Orbit card with this shape.

## 10.3 Visible Element participant shape

The actual visible Element participant used this shape:

```text
event type = org.matrix.msc3401.call.member
state_key = _@nfakism:matrix.org_mQWLDHfwtl_m.call
membershipID = @nfakism:matrix.org:mQWLDHfwtl
device_id = mQWLDHfwtl
call_id = blank
application = m.call
intent = audio
scope = m.room
expires = 43200000
foci_preferred = present
focus_active = present
foci_active = null
capabilities = null
```

This was the key.

## 10.4 Correct diagnosis

Orbit was publishing a valid-looking event, but not the shape Element UI was actually accepting.

The fix was to mirror the visible Element participant shape.

## 11. Final Working MatrixRTC Fix

The working runtime flag:

```powershell
-Dorbit.matrixrtc.mirrorVisibleElementShape=true
```

This mode publishes:

```text
event type = org.matrix.msc3401.call.member
call_id = blank
expires = 43200000
foci_active = null
capabilities = null
```

Working member event content:

```json
{
  "application": "m.call",
  "call_id": "",
  "device_id": "reakukDlqU",
  "membershipID": "@nfak_ism:matrix.org:reakukDlqU",
  "created_ts": 1780347139223,
  "m.call.intent": "audio",
  "scope": "m.room",
  "expires": 43200000,
  "foci_preferred": [
    {
      "livekit_service_url": "https://livekit-jwt.call.matrix.org",
      "livekit_alias": "!MqachzXtDbUPLqAUel:matrix.org",
      "type": "livekit"
    }
  ],
  "focus_active": {
    "type": "livekit",
    "focus_selection": "oldest_membership"
  }
}
```

Logs confirmed:

```text
selected_reference_is_remote=true
selected_reference_type=org.matrix.msc3401.call.member
selected_reference_call_id=<blank>
selected_reference_expires=43200000
selected_reference_has_foci_active=false
selected_reference_has_capabilities=false
publish mode=legacy-only
Published org.matrix.msc3401.call.member status=200
orbit member visible=true
```

## 12. "Waiting for media..." Stage

After the membership fix, Element started showing Orbit but displayed:

```text
Waiting for media...
```

This meant:

```text
Element saw the MatrixRTC participant,
but had not yet attached usable LiveKit media to it.
```

This was progress.

Before:

```text
Orbit card did not appear at all.
```

After:

```text
Orbit participant/card appeared but waited for media.
```

Final test then proved media attached and audio played.

## 13. Final Pass

After the working membership mode was used:

```powershell
-Dorbit.audio.testTone=true -Dorbit.matrixrtc.mirrorVisibleElementShape=true
```

Element heard loud beeps from Orbit.

When Orbit mic/mute was closed:

```text
The beep stopped in Element.
```

Also:

```text
Orbit could still hear Element audio.
```

This proved two-way routing.

Final confirmed result:

```text
Orbit to Element test tone: PASS
Orbit mute affects Element: PASS
Element to Orbit audio: PASS
MatrixRTC membership/card mapping: PASS
```

Then running without test tone confirmed the real microphone path.

## 14. What Was Ruled Out

The following were not the final root cause:

```text
Matrix call discovery
LiveKit token fetch
LiveKit connect
Element to Orbit subscription
Remote audio playback
Microphone capture
Test tone generation
JNI transfer
C++ worker queue
AudioFrame format
Mono vs stereo channel count
Track name
Track source
Chrome playback device
Element speaker output
Basic LiveKit JS publish
Basic LiveKit C++ publish
```

The final root cause was:

```text
Incorrect MatrixRTC membership event shape for Element interop.
```

## 15. Final Root Cause

Element did not accept Orbit as a proper MatrixRTC participant because Orbit's member event did not match the shape of Element's own visible participant events.

The fields that mattered most were:

```text
event type
call_id
expires
foci_active
capabilities
state_key pattern
membershipID
device_id
```

The working shape was the legacy MatrixRTC member event:

```text
org.matrix.msc3401.call.member
```

with:

```text
call_id blank
expires 43200000
foci_active omitted/null
capabilities omitted/null
```

## 16. Final Working Command

For the working test tone run:

```powershell
cd E:\Orbit
.\gradlew --stop
Remove-Item Env:\JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue

$env:JAVA_TOOL_OPTIONS="-Dorbit.audio.testTone=true -Dorbit.matrixrtc.mirrorVisibleElementShape=true"
.\gradlew :app:run
```

For real microphone test:

```powershell
cd E:\Orbit
.\gradlew --stop
Remove-Item Env:\JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue

$env:JAVA_TOOL_OPTIONS="-Dorbit.matrixrtc.mirrorVisibleElementShape=true"
.\gradlew :app:run
```

If mic is too low:

```powershell
$env:JAVA_TOOL_OPTIONS="-Dorbit.matrixrtc.mirrorVisibleElementShape=true -Dorbit.audio.captureGain=8.0"
.\gradlew :app:run
```

## 17. Recommended Cleanup

Now that the working path is proven, cleanup should be conservative.

Do:

```text
Make mirrorVisibleElementShape the default Element interop mode.
Keep old modern/legacy/both modes behind debug flags.
Keep detailed MatrixRTCVisibilityAudit logs behind a debug flag.
Remove stale broken self-events if safe.
Preserve C++ audio worker queue.
Preserve queueSizeMs=10 if it remains required.
Preserve trackName=audio and SOURCE_MICROPHONE.
Run final runtime test.
Commit only after final test passes.
```

Do not:

```text
Do not touch C++ audio randomly.
Do not change AudioFrame format.
Do not remove the working membership shape.
Do not switch to JS/WebView yet.
Do not rework token identity unless required.
Do not add video until audio is committed.
```

## 18. Suggested Commit Message

```text
Fix MatrixRTC member shape for Element audio interop
```

## 19. Final Acceptance Checklist

Before committing, run:

```text
1. Orbit joins Element call.
2. Orbit card appears in Element.
3. Element hears Orbit real mic.
4. Orbit hears Element real mic.
5. Orbit mute stops audio in Element.
6. Element mute stops audio in Orbit.
7. Leave from Orbit removes or expires the Orbit member.
8. Rejoin works.
9. No duplicate Orbit participants remain.
10. compileKotlinJvm passes.
```

## 20. Final Status

```text
Status: Core audio interop working.

Element to Orbit audio: working
Orbit to Element audio: working
Orbit participant visibility: working
Orbit media mapping: working
Test tone: working
Mute behavior: working
Real mic: working after tone disabled
```

The main lesson:

```text
For Element Call interop, media can be perfectly valid and still fail if MatrixRTC membership shape does not match what Element expects.

Fix membership visibility and media mapping first.
Only then debug audio.
```
