# LiveKit JS Tone Test

Standalone browser test for checking whether LiveKit JS can publish audio into the same Element Call room where the C++ publisher fails.

## Run

```powershell
cd E:\Orbit\tools\livekit-js-tone-test
python -m http.server 5173
```

Open:

```text
http://localhost:5173
```

## Test Tone

1. Open Element in Chrome and join the same call.
2. Open `http://localhost:5173` in another Chrome tab.
3. Paste the same LiveKit URL and JWT token that Orbit printed.
4. Click `Connect`.
5. Click `Publish 1 kHz Tone`.
6. Check `chrome://webrtc-internals` on the Element tab.

Expected Element Chrome stats:

```text
packetsReceived increases
packetsDiscarded near 0
totalSamplesReceived > 0
totalSamplesReceived/s ~= 48000
totalAudioEnergy > 0
audioLevel > 0
```

Element should hear the 1 kHz tone.

## Test Microphone

1. Click `Stop`.
2. Click `Connect`.
3. Click `Publish Microphone`.
4. Speak into the selected browser microphone.

Element should hear the microphone.

## Interpretation

If JS tone and JS mic work, LiveKit JS is viable for Element compatibility and the remaining problem is specific to the C++ SDK publish path.

If JS tone also fails with the same URL/token, investigate token permissions, participant identity/metadata, Element Call expectations, room encryption, and MatrixRTC/LiveKit compatibility before changing Orbit media code.
