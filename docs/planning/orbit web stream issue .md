# Orbit Web & Desktop Streaming Diagnostics: Bottlenecks, Telemetry & Fixes

This document compiles all streaming issue discoveries, telemetry logging specifications, root-cause diagnostics (PC CPU vs. Encoder vs. Network BWE vs. Remote Decoder), and testing instructions developed for Orbit desktop and web clients.

---

## 1. Summary of Streaming Issues & Root Causes

When screen sharing in WebRTC/LiveKit environments (especially in group calls or web clients), users frequently encounter poor quality, blurry text, stutter, or a 20 FPS cap despite selecting 1080p60 or 1440p.

### The 6 Hidden Bottlenecks Identified in Orbit

1. **Fixed Publish Bitrate Ceiling**:
   - *Bug*: The sender initializes screen sharing with a static bitrate cap (e.g., 2.5 Mbps) across all presets.
   - *Symptom*: Selecting 1440p or 1080p produces **worse** quality than 720p because the same 2.5 Mbps is spread over 2.25× to 4× as many pixels (drastically lowering bits-per-pixel).
   - *Fix*: Scale the bitrate dynamically based on resolution and frame rate (see Bitrate Reference Table below).

2. **Receiver-Side Downscale & Framerate Cap ("The 720p20 Trap")**:
   - *Bug*: WebRTC clients often treat screen share tracks with default camera constraints (e.g., `kRemoteVideoMaxLongEdge = 1280` and `kRemoteVideoMinFrameIntervalNs = 50ms` / 20 FPS).
   - *Symptom*: The sender captures and sends 1080p60, but the receiver automatically downscales it to 1280x720 and drops 2 out of every 3 frames down to 20 FPS.
   - *Fix*: Detect `track.source == Track.Source.ScreenShare` or `SOURCE_SCREENSHARE`. Exempt screen shares from camera downscaling, allowing up to 4K (3840 px long edge) and 60 FPS.

3. **Frame Pacing & Timer Drift (Sleep-After-Capture)**:
   - *Bug*: Calling `captureFrame()` followed by `sleep(16.6ms)`.
   - *Symptom*: Actual frame rate becomes `1 / (captureTime + 16.6ms)`. If capture takes 10 ms, the frame rate drops from 60 FPS to 37 FPS.
   - *Fix*: Pace frames against absolute monotonic deadlines (`nextFrameTime = prevFrameTime + intervalNs`). If capture finishes early, wait only the remaining duration; if it overruns, emit immediately and schedule next.

4. **RGB/BGR Channel Swapping (Color Inversion & Text Fringing)**:
   - *Bug*: Desktop OS screen capture often outputs `FOURCC_ABGR` / `BGRx`, while standard WebRTC video sources expect `FOURCC_ARGB` or `I420`.
   - *Symptom*: Red and blue color channels are flipped, causing chromatic aberration, text fringing, and washed-out colors.
   - *Fix*: Apply explicit color matrix correction (`ABGR` -> `ARGB` or proper canvas pixel conversion) before feeding frames to the encoder.

5. **Expensive Intermediate Buffer Conversions**:
   - *Bug*: Converting frame buffers through intermediate abstractions (e.g., raw bytes -> CPU image object -> canvas/texture) that loop over every pixel on the main thread.
   - *Symptom*: CPU spikes to 100%, starving the audio mixer/render loop and dropping frames.
   - *Fix*: Upload frame buffers directly into GPU textures/surfaces (e.g., direct Skia `Bitmap.installPixels` or WebGL `texImage2D` with `ImageBitmap`).

6. **Missing Simulcast / Single-Layer Congestion Collapse**:
   - *Bug*: Publishing only one high-bitrate layer with no lower-resolution fallback.
   - *Symptom*: As soon as network congestion occurs, WebRTC drops the entire stream's bitrate or drops packets, causing sudden severe artifacting rather than graceful layer switching.

---

## 2. Telemetry Architecture: What to Log & When

To diagnose whether an issue is local to the PC, the encoder, the network, or the receiver, implement a dedicated streaming telemetry log (`orbit-stream.log`).

### The 5 Telemetry Logging Phases

| Phase | Cadence | Content & Key Fields | Purpose |
| --- | --- | --- | --- |
| `phase=config` | On share start | `quality`, `fps`, `maxWidth`, `maxHeight`, `bitrateBps`, `simulcast` | Verifies selected preset and bitrate ceiling applied to the encoder. |
| `phase=captureStart` | Once on capture init | `sourceId`, `sourceWidth`, `sourceHeight`, `pixelFormat` | Verifies source display/window resolution before scaling. |
| `phase=capture` | 1 second interval (1 Hz) | `captureFps`, `captureCallAvgMs`, `droppedFrames`, `totalCaptured` | Evaluates local OS capture performance. |
| `phase=sink` | 1 second interval (1 Hz) | `fpsIn`, `fpsPushed`, `pushAvgMs`, `pushFail` | Evaluates queueing into the WebRTC native/web track source. |
| `phase=preview` | 5 second interval | `previewFps`, `renderTimeMs` | Verifies local preview performance (should be capped at ~10 FPS to save CPU). |

### The 5-Second WebRTC Stats Telemetry

Every 5 seconds, query WebRTC `getStats()` for screen share outbound/inbound RTP streams and log:

```text
STREAMDIAG STATS [CallPathStats/5s]
  SHARESTAT: verdict=sending fpsTarget=60 fps=58.4 framesEncoded=292 framesSent=292 targetBitrate=7850000 quality=none enc=H264
  SHARELAYER: layer=0 targetWidth=1920 targetHeight=1080 active=true
  SHAREREMOTE: fractionLost=0.01 roundTripTimeMs=24.5 jitterMs=3.2 nackCount=4
  SHARETRANSPORT: availableOutgoingBitrate=14500000 packetsSent=18200 bytesDiscardedOnSend=0
```

#### Meaning of Fields:
- `fpsTarget`: The negotiated target frame rate (e.g. 60).
- `fps`: Achieved frames encoded/sent per second in the last 5-second window.
- `targetBitrate`: Current bitrate assigned by the WebRTC Bandwidth Estimator (BWE).
- `quality`: Quality limitation reason:
  - `none`: Not limited, running at full speed.
  - `cpu`: Limited because the CPU / encoder cannot keep up.
  - `bandwidth`: Limited because the network bandwidth / BWE throttled the stream.
  - `other`: Limited by frame rate cap or socket congestion.
- `availableOutgoingBitrate`: Estimated uplink bandwidth from RTCP feedback.

---

## 3. Decision Matrix: How to Pinpoint the Bottleneck

Use this matrix to identify where the stream is bottlenecked:

```
                                  [Check Telemetry]
                                         │
        ┌────────────────────────────────┼────────────────────────────────┐
        ▼                                ▼                                ▼
[phase=capture]                  [SHARESTAT]                      [SHAREREMOTE /
captureFps < 50                  quality=cpu                      SHARETRANSPORT]
(Target: 60)                     fps < 50                         quality=bandwidth
        │                                │                        fractionLost > 5%
        ▼                                ▼                                │
┌──────────────────┐             ┌──────────────────┐                     ▼
│  BOTTLENECK 1:   │             │  BOTTLENECK 2:   │             ┌──────────────────┐
│  LOCAL PC/CPU    │             │   ENCODER CPU    │             │  BOTTLENECK 3:   │
│ Screen capture   │             │ Software encoder │             │   NETWORK/BWE    │
│ cannot keep up   │             │ cannot keep up   │             │ Uplink congested │
└──────────────────┘             └──────────────────┘             └──────────────────┘
```

### 1. Local Capture / PC Bottleneck (Sender PC CPU)
- **Log Indicators**:
  - `phase=capture captureFps` stays around 30–38 FPS when 60 FPS is requested.
  - `captureCallAvgMs` is high (>14 ms in a 16.6 ms frame budget).
  - `SHARESTAT fps` matches `captureFps`.
  - `SHARESTAT quality=none` or `quality=cpu`.
- **Diagnosis**: The PC's display capture API or window manager cannot grab frames at 60 FPS.
- **Action**: Use hardware-accelerated screen capture (DirectX Desktop Duplication / ScreenCapture API on Web), avoid capturing minimized windows, avoid software pixel copy.

### 2. Encoder CPU Bottleneck (Sender Encoder)
- **Log Indicators**:
  - `phase=capture captureFps` is healthy at 60 FPS.
  - `SHARESTAT fps` is depressed (<45 FPS).
  - `SHARESTAT quality=cpu` (`qualityLimitationReason == "cpu"`).
  - CPU usage spikes on encoder threads.
- **Diagnosis**: The video encoder (VP8/VP9/openh264) cannot encode frames fast enough in real time.
- **Action**: Enable WebRTC hardware encoding (`WebCodecs` on web, NVENC/AMF/QuickSync natively), reduce resolution from 1080p to 720p, or switch from VP9 to H.264 baseline/main profile.

### 3. Network / BWE Bottleneck (Sender Internet Uplink)
- **Log Indicators**:
  - `SHARESTAT quality=bandwidth` (`qualityLimitationReason == "bandwidth"`).
  - `targetBitrate` collapses (e.g. from 8 Mbps to 1.2 Mbps).
  - `availableOutgoingBitrate` is lower than preset bitrate.
  - `SHAREREMOTE fractionLost` rises above 3–5%.
  - `roundTripTimeMs` (RTT) and `nackCount` escalate.
- **Diagnosis**: Congestion control (Google Congestion Control / GCC) detects packet loss or queuing delay and throttles the stream to prevent connection drop.
- **Action**: Test on Ethernet instead of Wi-Fi, reduce preset from 1080p60 to 720p30, or implement simulcast so lower bitrate layers can be sent cleanly.

### 4. Local Send Socket Queue Bottleneck
- **Log Indicators**:
  - `phase=sink pushFail` increases.
  - `SHARETRANSPORT packetsDiscardedOnSend` or `bytesDiscardedOnSend` rises.
  - `SHARESTAT totalPacketSendDelay` climbs.
- **Diagnosis**: The network socket or internal WebRTC packet queue is backlogged.

### 5. Remote Receiver / Decoder Bottleneck
- **Log Indicators**:
  - Sender logs show perfect transmission: `SHARESTAT fps=59.8`, `bitrate=8000000`, `quality=none`, `fractionLost=0`.
  - Yet the remote viewer experiences stutter, low frame rate, or video freezing.
- **Diagnosis**: The receiving peer's browser or device cannot decode/render frames fast enough (e.g. web canvas repaint bottleneck or mobile software decoding).
- **Action**: On the receiver side, verify `RTCRtpReceiver` stats: `framesDecoded`, `framesDropped`, `jitterBufferDelay`.

---

## 4. Preset Bitrate Reference Table

When configuring screen share tracks in WebRTC, scale encoder bitrate ceilings with this formula:

$$\text{Bitrate} = \text{BaseBitrate}(\text{resolution}) \times \text{FpsMultiplier}(\text{fps})$$

- $\le 15\text{ fps} \implies \times 0.6$
- $16\text{–}30\text{ fps} \implies \times 1.0$
- $> 30\text{ fps} \implies \times 1.6$

| Resolution | 15 FPS | 30 FPS | 60 FPS |
| :--- | :--- | :--- | :--- |
| **720p** (1280×720) | 1.5 Mbps | 2.5 Mbps | 4.0 Mbps |
| **1080p** (1920×1080) | 3.0 Mbps | 5.0 Mbps | 8.0 Mbps |
| **1440p** (2560×1440) | 4.8 Mbps | 8.0 Mbps | 12.8 Mbps |
| **Source / 4K** (3840×2160) | 7.2 Mbps | 12.0 Mbps | 19.2 Mbps |

*Note*: These are encoder caps, not reserved minimums. WebRTC GCC will still ramp down if the uplink is constrained.

---

## 5. The 17-Minute Benchmark Test Procedure

To systematically pinpoint any streaming regression:

1. **Test Environment**:
   - Join a group call with 2 participants (1 sender, 1 remote receiver).
   - Display dynamic text content (e.g. VS Code with smooth scrolling or a 60fps moving video/timer).
2. **Execution Matrix**:

| Sequence | Preset | Duration | What to Observe |
| :--- | :--- | :--- | :--- |
| 1 | **720p30** | 2 min | Baseline control. CPU should be low, capture near 30 FPS. |
| 2 | **720p60** | 3 min | Low encode cost, tests 60 FPS pacing and local capture limit. |
| 3 | **1080p30** | 2 min | High resolution baseline at modest frame rate. |
| 4 | **1080p60** | 4 min | Primary stress test: local capture cap vs encoder CPU. |
| 5 | **1080p15** | 2 min | Low bitrate / high text sharpness verification. |
| 6 | **Source30** | 2 min | High resolution stress at 12 Mbps uplink. |
| 7 | **Source60** | 2 min | Optional extreme uplink stress at 19.2 Mbps. |

### Test Rules:
- **Wait 15 Seconds**: Allow `phase=config` and at least 3 `SHARESTAT` samples after starting each share.
- **Discard First 60 Seconds**: BWE ramps up gradually; do not record numbers during initial ramp.
- **Keep Call Connected**: Only toggle screen share on/off to apply the preset; do not leave and re-join the room.
