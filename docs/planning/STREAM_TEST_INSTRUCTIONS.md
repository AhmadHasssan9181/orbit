# Orbit Screen-Share Streaming Test Instructions

Use this after building the stream telemetry changes. The goal is to separate
local capture/CPU cost from encoder cost and network/BWE behavior.

## Log files

- Stream telemetry: `%LOCALAPPDATA%\Orbit\logs\orbit-stream.log`
- General app log: `%LOCALAPPDATA%\Orbit\logs\orbit.log`
- If `ORBIT_LOG_DIR` is set, both files live there instead.
- `orbit-stream.log` rotates at 4 MB into `orbit-stream.1.log` and
  `orbit-stream.2.log`. Send all files only if the run was very long.

The stream log contains:

- `STREAMDIAG phase=config ...` on every share start
- `STREAMDIAG phase=captureStart ...` once when capture starts
- `STREAMDIAG phase=captureFrame ...` once when the source resolution changes
- `STREAMDIAG phase=capture ...` once per second
- `STREAMDIAG phase=sink firstFrame ...` once when the first sink frame arrives
- `STREAMDIAG phase=sink ...` once per second
- `STREAMDIAG phase=preview ...` every 5 seconds
- `STREAMDIAG STATS [CallPathStats/5s] ...` every 5 seconds
- `SHARESTAT`, `SHARELAYER`, `SHAREREMOTE`, `SHARETRANSPORT` every 5 seconds
  while a group-call screen share is active

## Launch

From PowerShell in `G:\projects\orbit`:

```powershell
.\launch.bat
```

Optional safety net for the first run, in case the native-to-Kotlin log bridge
fails. This captures the console as well:

```powershell
.\launch.bat 2>&1 | Tee-Object -FilePath "$env:LOCALAPPDATA\Orbit\logs\console-$(Get-Date -Format yyyyMMdd-HHmmss).log"
```

Do not use the stale installed app shortcut. `launch.bat` runs Gradle and uses
the fresh DLL from `app\build\jniOut`.

## Before starting

- Join a group call, not a 1:1 call. Native share stats only run in group calls.
- Start screen sharing.
- Confirm `orbit-stream.log` exists and contains `phase=config` and at least one
  `SHARESTAT`.
- If `SHARESTAT` is missing from `orbit-stream.log`, tell me; the JNI upcall
  failed and the console tee file is the fallback.

## Important timing rules

- Preset changes apply when the share starts. Stop sharing, change the preset,
  then start sharing again.
- After starting a preset, wait 15 seconds for `phase=config` and at least
  three `SHARESTAT` samples.
- Discard the first 60 seconds of each preset. Bitrate estimation is still
  ramping and the numbers are not settled.
- Do not switch presets every 5-18 seconds. That invalidated the previous run.
- Keep the call connected. Only stop/restart the share to apply a preset.

## Focused test matrix (about 17 minutes)

| Preset | Duration | Purpose |
| --- | --- | --- |
| 720p30 | 2 min | Control; capture should keep up, low encode cost |
| 720p60 | 3 min | Local 60fps cap with low encode cost |
| 1080p30 | 2 min | 1080p control at a rate capture can sustain |
| 1080p60 | 4 min | Main complaint; local cap and encoder load |
| 1080p15 | 2 min | Low bitrate control |
| Source30 | 2 min | Uplink/BWE stress at 12 Mbps |
| Source60 | 2 min | Optional; 19.2 Mbps uplink stress |

1440p is not a real 1440p test on a 1920x1080 display. The capture stays
1920x1080, so skip it or treat it as a bitrate-only test.

If time is short, run 1080p60 for 5 minutes first, then 720p60 for 3 minutes,
then 1080p30 for 2 minutes.

## What to record during the test

- Remote viewer's FPS, if displayed.
- Remote viewer's description: smooth, slightly stuttery, very stuttery, blurry,
  frozen.
- Wall-clock timestamps for each quality dip or stutter burst.
- CPU and GPU utilization during 1080p60 (Task Manager is enough).
- Network type: Ethernet or Wi-Fi.
- Whether the remote viewer is on the same LAN or over the internet.

## What to send back

Send only the file paths. No console paste needed:

- `%LOCALAPPDATA%\Orbit\logs\orbit-stream.log`
- `%LOCALAPPDATA%\Orbit\logs\orbit.log` if there was a crash or a WARN/ERROR
- The optional console tee file, if used
- Your notes from the remote viewer

There is no remote-side telemetry in this run, so remote decode/render cannot be
fully proven. Sender-side stats plus the remote viewer's observations are enough
to separate capture, encoder, and sender-network causes.

## How to read the stream log

### Local capture / CPU cap

- `phase=capture captureFps` stays around 33-38 at 60fps presets.
- `captureCallAvgMs` is high, and `colorAvgMs + pushAvgMs` accounts for most of
  the frame budget.
- `SHARESTAT fps` is close to `captureFps`.
- `SHARESTAT quality=cpu` or the encoder is simply not being fed 60fps.

This means the PC is capping the share before the encoder. Fix capture cost
first.

### Encoder CPU cap

- `phase=capture captureFps` is at or near 60.
- `SHARESTAT fps` is below 50.
- `SHARESTAT quality=cpu`.

This means the encoder cannot keep up at that resolution/frame rate.

### Network / BWE cap

- `SHARESTAT targetBitrate` collapses.
- `SHARESTAT quality=bandwidth`.
- `SHARESTAT availableOutgoingBitrate` is below the preset bitrate.
- `SHARETRANSPORT congestion` or packet loss indicators worsen.
- `SHAREREMOTE fractionLost` is more than a few percent, RTT rises, or
  `retransmittedPackets`/`nackCount` climb.

This means congestion control is throttling the share.

### Local send queue / network

- `phase=capture pushFail` rises.
- `SHARETRANSPORT packetsDiscardedOnSend` or `bytesDiscardedOnSend` rise.
- `SHARESTAT totalPacketSendDelay` climbs.

This means frames or packets are being dropped on the local send path.

### Remote decode/render (not provable without remote log)

- `SHARESTAT` looks healthy and `SHARESTAT fps` is near target, but the remote
  viewer still sees low FPS or stutter.

This points at the remote decoder/renderer or the network path after the sender.
It needs the remote side's log to prove.

## Preset bitrate reference

| Quality | 15 fps | 30 fps | 60 fps |
| --- | --- | --- | --- |
| 720p | 1.5 Mbps | 2.5 Mbps | 4 Mbps |
| 1080p | 3 Mbps | 5 Mbps | 8 Mbps |
| 1440p | 4.8 Mbps | 8 Mbps | 12.8 Mbps |
| Source | - | 12 Mbps | 19.2 Mbps |
