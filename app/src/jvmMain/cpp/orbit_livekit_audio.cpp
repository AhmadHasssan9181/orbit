/*
 * Orbit Desktop — Native LiveKit Audio & Media Engine
 * Copyright (c) 2026 Ahmad Hassan / Orbit Contributors.
 *
 * Dual-licensed under the Apache License, Version 2.0 (for open-source use)
 * and the Orbit Commercial License (for proprietary/commercial use).
 * See LICENSE and LICENSE-COMMERCIAL in the project root for details.
 */

#include <jni.h>

#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <audioclientactivationparams.h>
#include <objbase.h>

#include "livekit/livekit.h"
#include "livekit/stats.h"
#include "livekit/video_stream.h"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cmath>
#include <condition_variable>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <exception>
#include <functional>
#include <future>
#include <iostream>
#include <limits>
// std::map is used for g_participantMediaKeys; it is not pulled in
// transitively on every toolchain, so include it explicitly.
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <variant>
#include <vector>

namespace {

std::once_flag g_initializeOnce;
std::timed_mutex g_sessionMutex;
std::unique_ptr<livekit::Room> g_room;
std::unique_ptr<livekit::RoomDelegate> g_delegate;
std::shared_ptr<livekit::AudioSource> g_audioSource;
std::shared_ptr<livekit::LocalAudioTrack> g_audioTrack;
std::shared_ptr<livekit::VideoSource> g_videoSource;
std::shared_ptr<livekit::LocalVideoTrack> g_videoTrack;
std::string g_matrixRtcMemberId;
bool g_connected = false;
// Atomic so the audio-source worker can gate frames without contending on
// g_sessionMutex — a try-lock fallback here used to default to "capture anyway",
// letting live PCM into the AudioSource while the user was muted.
std::atomic<bool> g_micPublished{false};
std::atomic<bool> g_acceptingFrames{false};
// Last-known mute intent for g_audioTrack; refreshed opportunistically whenever
// the worker does win the session mutex, and written authoritatively by
// nativeSetAudioMuted. Fail-closed input for the worker's mute gate.
std::atomic<bool> g_micTrackMutedCache{false};
bool g_videoPublished = false;
bool g_acceptingVideoFrames = false;
bool g_cameraRequested = false;
int g_videoWidth = 0;
int g_videoHeight = 0;
int g_videoFps = 0;
int g_videoFormat = 0;
std::string g_videoPublicationSid;
std::string g_videoLocalTrackSid;
std::shared_ptr<livekit::VideoSource> g_screenShareSource;
std::shared_ptr<livekit::LocalVideoTrack> g_screenShareTrack;
int g_screenShareWidth = 0;
int g_screenShareHeight = 0;
int g_screenShareFps = 0;
std::string g_screenSharePublicationSid;
bool g_screenSharePublished = false;
bool g_acceptingScreenShareFrames = false;
bool g_screenShareFrameDroppedNotReadyLogged = false;
int g_screenSharePushDiagnosticsLogged = 0;
std::shared_ptr<livekit::AudioSource> g_shareAudioSource;
std::shared_ptr<livekit::LocalAudioTrack> g_shareAudioTrack;
std::string g_shareAudioPublicationSid;
bool g_shareAudioPublished = false;
std::atomic_bool g_shareLoopbackActive{false};
std::thread g_shareLoopbackThread;
// True when the share-audio capture fell back to full-device loopback because
// the OS rejected process-loopback exclusion (pre-24H2 Windows).
std::atomic_bool g_shareLoopbackFullDeviceFallback{false};
// Set once the loopback thread has resolved which capture mode it got.
std::atomic_bool g_shareLoopbackModeResolved{false};
// Share-audio echo cancellation: cached JVM upcall into livekit.ShareAudioAec,
// which subtracts Orbit's own playout from full-device loopback captures and
// feeds cleaned frames back via nativeFeedProcessedShareAudioFrame.
// The pair is written by whichever thread resolves it first (the publishing JVM
// thread or the loopback worker) and read by the loopback worker every 10 ms,
// so it is published under g_shareAecMutex: reading the class and the method id
// without the lock can observe a new class with a stale method id.
std::mutex g_shareAecMutex;
jclass g_shareAecRouterClass = nullptr;
jmethodID g_shareAecOnFrameMethod = nullptr;
std::mutex g_streamDiagMutex;
jclass g_streamDiagClass = nullptr;
jmethodID g_streamDiagOnLineMethod = nullptr;
// Raw publisher source handed to the AEC round-trip. Deliberately NOT guarded
// by g_sessionMutex: teardown clears it before joining the loopback thread,
// and the source outlives every in-flight feed call because destruction only
// happens after join returns. Keeps the loopback path mutex-free.
std::atomic<livekit::AudioSource*> g_shareAudioFeedTarget{nullptr};
bool g_videoFrameDroppedNotReadyLogged = false;
int g_videoPushDiagnosticsLogged = 0;
std::int64_t g_lastVideoTimestampNs = 0;
int g_publishedAudioChannels = 0;
std::atomic_bool g_expectedTestTone{false};
std::atomic_bool g_firstAudioFrameAccepted{false};
bool g_frameDroppedNotReadyLogged = false;
int g_audioPushDiagnosticsLogged = 0;
std::atomic_bool g_callbackShutdown{false};
std::atomic_bool g_sessionDestroyed{false};

JavaVM* g_javaVm = nullptr;
std::mutex g_sinkMutex;
jobject g_remoteAudioSink = nullptr;
jmethodID g_remoteAudioSinkMethod = nullptr;

std::mutex g_remoteVideoSinkMutex;
jobject g_remoteVideoSink = nullptr;
jmethodID g_remoteParticipantConnectedMethod = nullptr;
jmethodID g_remoteParticipantDisconnectedMethod = nullptr;
jmethodID g_remoteAudioMuteChangedMethod = nullptr;
jmethodID g_remoteConnectionQualityMethod = nullptr;
jmethodID g_remoteVideoTrackAddedMethod = nullptr;
jmethodID g_remoteVideoFrameMethod = nullptr;
jmethodID g_remoteVideoTrackStuckMethod = nullptr;
jmethodID g_remoteVideoAnnouncedMethod = nullptr;
jmethodID g_remoteVideoTrackRemovedMethod = nullptr;

// Participant SIDs already announced through onRemoteParticipantConnected.
// The periodic rescan re-enumerates the whole roster; without this gate every
// tick would re-fire the JNI upcall for every participant. Entries are erased
// when a participant leaves and the whole set clears on session teardown.
std::mutex g_announcedParticipantsMutex;
std::set<std::string> g_announcedParticipantSids;

// Releases a reservation taken by dispatchRemoteParticipantConnected when the
// JNI upcall turned out to be undeliverable, so a later attempt (the rescan) can
// still announce the participant. Deliberately not a "was it ours" check: the
// only caller holds the reservation it just took, and the alternative -- tracking
// ownership per key -- would be a second source of truth for one boolean.
void unreserveParticipantAnnouncement(const std::string& announceKey) {
    std::lock_guard<std::mutex> announcedLock(g_announcedParticipantsMutex);
    g_announcedParticipantSids.erase(announceKey);
}

std::mutex g_mixedAudioSinkMutex;
jobject g_mixedAudioSink = nullptr;
jmethodID g_mixedAudioSinkMethod = nullptr;

std::mutex g_eventSinkMutex;
jobject g_eventSink = nullptr;
jmethodID g_eventSinkMethod = nullptr;

std::mutex g_jniAttachLogMutex;
std::unordered_set<std::string> g_loggedJniAttachSuccess;
std::unordered_set<std::string> g_loggedCallbackSkips;

// g_sessionMutex is non-recursive, so a JNI upcall made while this thread holds
// it can re-enter a native entry point (nativeSetAcceptingFrames,
// setMixedAudioSink, disableRemoteVideoSink, ...) that takes the same mutex and
// deadlock the SDK worker that raised the event. Upcalls raised under the lock
// are therefore queued here and flushed by SessionLock as soon as it unlocks,
// which keeps their relative order intact.
// thread_local, NOT a shared vector: a queued lambda captures a JNIEnv*, which
// is thread-confidential. With a global queue, one thread's ~SessionLock could
// flush another thread's pending entry and run it with a foreign env (undefined
// behaviour, and it also inverts event order across threads). Per-thread is
// also all the design ever needed -- only the lock-owning thread defers, and
// only the lock-owning thread flushes.
thread_local std::vector<std::function<void()>> g_deferredDispatches;
// Per-thread nesting depth of SessionLock; only this thread's own dispatches
// are deferred, so a reader thread is never blocked by another thread's lock.
thread_local int g_sessionLockDepth = 0;

struct RemoteAudioReader {
    std::string trackSid;
    std::string participantIdentity;
    std::shared_ptr<livekit::Track> track;
    std::shared_ptr<livekit::AudioStream> stream;
    std::thread thread;
    std::atomic_bool running{true};
    // H6 jitter-buffer watchdog state. jbHighStreak is touched only by the
    // reader thread; the atomics are also written by the detached resubscribe
    // worker after it fires.
    std::chrono::steady_clock::time_point createdSteady{std::chrono::steady_clock::now()};
    std::atomic<long long> lastFrameSteadyMs{0};
    int jbHighStreak{0};
    std::atomic<int> resubCount{0};
    std::atomic<long long> lastResubSteadyMs{-1};
    // Per-interval RTP counters for the connection-quality bars (-1 = no prior
    // sample yet). Touched only by this reader's stats pass every ~5s.
    std::atomic<long long> prevPacketsReceived{-1};
    std::atomic<long long> prevPacketsLost{-1};
};

struct RemoteVideoReader {
    jlong sessionId{1};
    jlong epoch{0};
    std::string participantIdentity;
    std::string participantSid;
    std::string publicationSid;
    std::string trackSid;
    std::string trackName;
    std::string trackSource;
    bool isScreenShare{false};
    bool muted{false};
    std::shared_ptr<livekit::VideoStream> stream;
    std::thread thread;
    std::atomic_bool running{true};
    std::atomic_bool removedDispatched{false};
};

std::unordered_map<std::string, std::shared_ptr<RemoteAudioReader>> g_remoteAudioReaders;
std::unordered_map<std::string, std::shared_ptr<RemoteVideoReader>> g_remoteVideoReaders;

// Publications the rescan has already tried to force-subscribe, so a track
// the server keeps refusing costs one attempt rather than one per tick.
std::mutex g_forcedSubscribesMutex;
std::unordered_set<std::string> g_forcedSubscribes;

// Last remote publication inventory, compared so the rescan logs a line only when
// the set of tracks in the room actually changes. The rescan runs on a timer for
// the whole call; a per-tick dump would bury everything else.
std::string g_remotePublicationInventory;
std::string g_lastDelegateCounts;

// Publications observed WITH a track while alive, so the withdrawal handler can
// tell a track that never existed from one the SDK has already torn down.
std::mutex g_everHadTrackMutex;
std::unordered_set<std::string> g_publicationsEverSeenWithTrack;

// Consecutive rescan ticks a publication has been seen with no track attached.
std::mutex g_tracklessTicksMutex;

// Video publications already announced to the UI, so the once-per-publication
// guarantee survives the rescan walking them on every tick.
std::mutex g_announcedVideoMutex;
std::unordered_set<std::string> g_announcedVideoPublications;
std::unordered_map<std::string, int> g_tracklessTicks;

// Consecutive rescan ticks a remote video publication has been ABSENT from
// participant->trackPublications() while a reader is still running for it.
//
// The rescan prune that reads this exists because a withdrawn publication does
// not always raise onTrackUnpublished. But a single missing sighting is not
// proof of anything: the SDK can drop a publication from that map for a tick
// while a sender replaces one track with another on the same m-line, and
// pruning on that tore down a live reader. Each prune published a track-removed
// into Java, so the tile came back on the next in-flight frame, the next tick
// pruned it again, and that cycle is what pinned the UI thread.
std::mutex g_absentVideoTicksMutex;
std::unordered_map<std::string, int> g_absentVideoTicks;

// Two ticks is 4s of the publication being genuinely gone, which is well under
// the time it takes a human to notice a stale tile and far longer than a
// bookkeeping blip in the SDK's publication map.
constexpr int kAbsentVideoTicksBeforePrune = 2;

// Consecutive trackless sightings (2 s apart) at which to make the SDK
// renegotiate, and the tick at which to give up and tell the UI.
//
// WHY THIS EXISTS. Run 23 established that `onTrackPublished` in SDK 0.4.1 arrives
// with a null publication EVERY time -- pub=5 nullPub=5, 100% -- so that event can
// never tell Orbit a track exists. `onTrackSubscribed` does carry a real track, but
// it only fires if the SDK subscribes us, and with auto_subscribe on it sometimes
// does not. The 2 s rescan is therefore the ONLY route by which Orbit learns about
// a track at all, and it was strictly passive: a publication with no track was
// skipped, so a share that never got one was invisible and stayed invisible.
//
// `setSubscribed(false)` then `setSubscribed(true)` is the only re-negotiation the
// SDK exposes for a remote publication (remote_track_publication.h declares nothing
// else, and room.h has no resync). It is the same cycle the audio jitter-buffer
// recovery already uses, so it is a known-good move on this SDK rather than a guess.
//
// TIMING. 3 / 9 ticks is 6 s then 18 s of retrying, and tick 15 (30 s) is where
// Orbit stops retrying and surfaces it to the UI. The gap after the last retry is
// deliberate: a share that attaches on the second attempt should not flash a
// reconnect button at someone for something that has already resolved.
constexpr int kTracklessTicksBeforeRenegotiate = 3;
constexpr int kTracklessRenegotiateEscalation[] = {3, 9};

// Trackless for this long and the share is declared stuck. The UI is told, and the
// one recovery that is actually proven to work -- a fresh SFU connection, which
// picks the track up on the fresh session -- is offered rather than left implicit.
constexpr int kTracklessTicksBeforeStuck = 15;

bool isTracklessRenegotiateTick(int ticks) {
    for (const int threshold : kTracklessRenegotiateEscalation) {
        if (ticks == threshold) {
            return true;
        }
    }
    return false;
}

// How many track-lifecycle delegate callbacks the SDK actually delivered, and how
// many arrived carrying no publication at all.
//
// Run 22 produced a publication that the 2 s rescan could see in
// participant->trackPublications() while NO log line claimed onTrackPublished had
// run for it -- and that was not evidence of anything, because every diagnostic on
// that path returns early on a null publication. So "the SDK never delivered the
// event" and "the SDK delivered an empty event" were the same log, and the first
// real screen share Orbit lost is unaccounted for.
//
// The rescan reading the publication proves the SDK knows about it; only these
// counters say whether the event path ran. Reported with the inventory so it costs
// no extra line.
std::mutex g_delegateEventMutex;
long long g_delegatePublished = 0;
long long g_delegateUnpublished = 0;
long long g_delegateSubscribed = 0;
long long g_delegateNullPublication = 0;

void noteDelegateEvent(const char* name, const std::shared_ptr<livekit::TrackPublication>& publication) {
    std::lock_guard<std::mutex> lock(g_delegateEventMutex);
    if (std::strcmp(name, "onTrackPublished") == 0) {
        g_delegatePublished += 1;
    } else if (std::strcmp(name, "onTrackUnpublished") == 0) {
        g_delegateUnpublished += 1;
    } else {
        g_delegateSubscribed += 1;
    }
    if (publication == nullptr) {
        g_delegateNullPublication += 1;
        std::cout << "WARN [OrbitLiveKitAudio] " << name
                  << " arrived with a NULL publication; the rescan is the only way this track becomes visible"
                  << std::endl;
    }
}

std::string delegateEventCounts() {
    std::lock_guard<std::mutex> lock(g_delegateEventMutex);
    return "pub=" + std::to_string(g_delegatePublished) +
           " sub=" + std::to_string(g_delegateSubscribed) +
           " unpub=" + std::to_string(g_delegateUnpublished) +
           " nullPub=" + std::to_string(g_delegateNullPublication);
}

constexpr jint kSampleRate = 48000;
constexpr jint kChannels = 1;
constexpr jint kMaxChannels = 2;
constexpr jint kFrameMs = 10;
constexpr jint kSamplesPerChannel = 480;
constexpr jint kFrameBytes = 960;
constexpr jint kBytesPerSample = static_cast<jint>(sizeof(std::int16_t));
constexpr int kAudioSourceCaptureTimeoutMs = 0;
constexpr std::size_t kTargetParticipantQueueSamples = kSamplesPerChannel * 8; // 80 ms target playout depth
constexpr std::size_t kMaxParticipantQueueSamples = kSamplesPerChannel * 16; // 160 ms burst headroom
constexpr std::size_t kStarvationReprimeSamples = kSamplesPerChannel * 2; // 20 ms top-up after a true drain

// Mixer catch-up budget. See `mixLoop` for why the loop must be able to run
// faster than real-time, and why the catch-up is bounded.
//
// 8 frames = 80 ms of backlog removable in a single tick, which comfortably
// covers ordinary scheduler jitter and a normal GC pause without the burst
// becoming audible as a discontinuity of its own.
constexpr int kMaxCatchUpFramesPerTick = 8;

// Past this much lateness the tick is resynced to now rather than repaid in
// full. At 80 ms of catch-up per tick, a sustained 200 ms deficit would still be
// climbing; resyncing converts an unrecoverable debt into a one-off latency
// spike, which the queue's overflow policy then handles by dropping the oldest
// audio. Losing old samples beats emitting an ever-growing pile of stale ones.
constexpr int kCatchUpResyncMs = 200;

// ── Remote video frame budget ───────────────────────────────────────────────
//
// The reader loop used to forward every frame the SFU delivered, at full size,
// into a fresh Java byte[] per frame. Measured on a 1920x1080 screen share from
// Element: the SFU pushed 28.8 fps (seq 60 -> 120 in 2080 ms) at 8.29 MB per
// RGBA frame, so ~237 MB/s was being pushed into the JVM. Each of those was then
// amplified on the UI side into a BufferedImage raster and a Skia Bitmap —
// roughly 1 GB/s of allocation — which is why a 1080p share was invisible while
// a 640x360 camera (about 12 MB/s) was fine.
//
// These are CAMERA bounds, applied while the pixels are still in native memory
// so the detail is thrown away once rather than copied four times and discarded
// later:
//
//   * long edge capped at 1280 px — about what a talking-head tile resolves.
//   * delivery capped at 20 fps.
//
// Screen shares are content, not talking heads, and bypass both guards below.
// The old code applied them to every track, which is why a 1080p/60 share
// arrived as 1280x720 at 20 fps. 3840 keeps 1440p and 4K intact while still
// bounding an accidental 8K capture.
constexpr int kRemoteVideoMaxLongEdge = 1280;
constexpr long long kRemoteVideoMinFrameIntervalNs = 50'000'000;  // 20 fps
constexpr int kRemoteScreenShareMaxLongEdge = 3840;

// H6: receiver jitter-buffer recovery. When the SDK's NetEQ target stays
// inflated (post-E2EE-key arrival storm, key rotations) the only cure is a
// fresh receiver, i.e. setSubscribed(false->true). Guarded so failure modes
// equal today's behavior: sustained-high streak + recent frame activity +
// warmup/cooldown/lifetime caps, and every SDK call wrapped in try/catch.
constexpr double kJitterBufferRecoverTargetMs = 400.0;
// jitter_buffer_target_delay is a CUMULATIVE SUM over all emitted frames, so a
// computed average above this cap means the math went wrong (e.g. emitted_count
// lagging), not that the receiver is really 2 minutes behind.
constexpr long long kJitterBufferGarbageCapMs = 120000;
constexpr int kJitterBufferRecoverStreak = 3;               // 3 consecutive 5 s polls ≈ 15 s sustained
constexpr long long kJitterBufferWarmupMs = 20000;          // let initial jitter settle before judging
constexpr long long kJitterBufferCooldownMs = 90000;        // per-reader minimum spacing
constexpr int kJitterBufferMaxResubPerReader = 4;           // lifetime cap
constexpr long long kJitterBufferActiveWindowMs = 10000;    // receiver must be actively producing frames
constexpr int kDefaultAudioMaxBitrateBps = 64000;
constexpr int kMinAudioMaxBitrateBps = 8000;
constexpr int kMaxAudioMaxBitrateBps = 512000;
constexpr int kDefaultVideoMaxBitrateBps = 1200000;
constexpr int kMinVideoMaxBitrateBps = 150000;
constexpr int kMaxVideoMaxBitrateBps = 6000000;
constexpr int kDefaultScreenShareMaxBitrateBps = 2500000;
constexpr int kMinScreenShareMaxBitrateBps = 300000;
constexpr int kMaxScreenShareMaxBitrateBps = 25000000;
constexpr int kDefaultShareAudioMaxBitrateBps = 128000;
constexpr jlong kRawSessionId = 1;
// g_rawEpoch is written under g_sessionMutex. Several dispatch helpers run on
// remote-reader threads, which teardown joins WHILE holding g_sessionMutex, so
// those helpers must not take that mutex just to read the epoch: a reader
// blocked on it would deadlock the teardown that is waiting for the reader.
// Mirror the epoch into an atomic instead; every writer refreshes it while it
// still holds g_sessionMutex.
jlong g_rawEpoch = 0;
std::atomic<jlong> g_rawEpochForCallbacks{0};
bool g_rawDisconnecting = false;
// Set via nativeConfigureE2EE before connect; survives reconnects until the
// next configure call. Read in connectRoom under g_sessionMutex.
// Module-level E2EE intent, read by connectRoom when it builds RoomOptions.
//
// Atomic, and deliberately NOT guarded by g_sessionMutex.
//
// This flag used to be a plain bool written under g_sessionMutex by
// nativeConfigureE2EE. That made a pre-connect call contend with session
// teardown, and the contention was not symmetric: teardown legitimately holds
// the mutex for a long time (it joins reader threads), while this call only
// needs to store one boolean whose every reader is already inside connectRoom
// holding the lock anyway.
//
// The cost of that unnecessary lock was a hang on rejoin. Reported as "the app
// is stuck" after Orbit itself disconnected and reconnected. The log shows the
// join stalling immediately after the last line that precedes this call:
//
//     MatrixRTC membership visible. Connecting native media at wss://...
//     (nothing further, for the remainder of the call)
//
// The line that would follow is printed by the caller AFTER this returns, so a
// stall here is silent -- no error, no warning, no retry, and no watchdog. The
// app sits on a spinner with the capture worker already dead (mic.read=0 for
// the rest of the run) and a heap that keeps climbing, which reads as "hung"
// rather than "failed".
//
// Atomic removes the dependency entirely. Storing a bool needs mutual exclusion
// against other stores, not against a reader that is itself serialized by the
// session mutex.
std::atomic<bool> g_e2eeRequested{false};
std::map<std::string, std::pair<std::vector<std::uint8_t>, int>> g_participantMediaKeys;

// The key this participant is ACTIVELY TRANSMITTING with.
//
// Every sender-side counter Orbit had was satisfiable by a peer hearing silence:
// capture could show tone, the encoder could emit RTP, packets and bytes could
// climb, the track could report encryption=gcm, and the key-exchange log could
// show the correct index being distributed and installed. All of those are true
// while the peer still hears nothing, because none of them observe the one thing
// that matters -- whether the frames leaving this machine are encrypted with the
// key the peer is holding.
//
// The index alone is not enough either: two peers can each be sitting on a
// DIFFERENT index and every number above still looks perfect. So the fingerprint
// travels too. It is an FNV-1a over the key bytes, truncated -- not a secret, it
// exists so two logs can be compared by eye and agree or disagree about which key
// material is in play. Compare this against what the far end reports installing
// and a cross-machine desync stops being a guess.
//
// Written only in nativeSetParticipantMediaKey when the identity being installed
// is the LOCAL participant; read by TXSTAT under SessionLock.
std::string g_ownKeyIdentity;
int g_ownKeyIndex = -1;
std::uint32_t g_ownKeyFingerprint = 0;

std::uint32_t fingerprintKeyBytes(const std::vector<std::uint8_t>& bytes) {
    std::uint32_t hash = 2166136261u;
    for (const std::uint8_t byte : bytes) {
        hash ^= byte;
        hash *= 16777619u;
    }
    return hash;
}

bool isSupportedAudioChannels(jint channels) {
    return channels == kChannels || channels == kMaxChannels;
}

bool isSupportedAudioSourceQueueMs(jint queueSizeMs) {
    // 0 = SDK real-time mode (captureFrame consumes synchronously, recommended
    // for self-paced producers like our worker); 10 = legacy buffered mode kept
    // for the property override. Buffered mode BLOCKS captureFrame until the
    // SDK drains its queue in real time, which stacked with the worker's own
    // 10 ms pacing to a ~31 ms/frame cycle and ~68% mic frame loss.
    return queueSizeMs == 0 || queueSizeMs == 10;
}

jint audioFrameBytes(jint channels, jint samplesPerChannel = kSamplesPerChannel) {
    return samplesPerChannel * channels * kBytesPerSample;
}

double audioFrameDurationMs(jint sampleRate, jint samplesPerChannel) {
    if (sampleRate <= 0) {
        return 0.0;
    }
    return (static_cast<double>(samplesPerChannel) * 1000.0) / static_cast<double>(sampleRate);
}

// SDK Track/Publication/Participant sids have been observed carrying trailing
// whitespace padding ("TR_...   "). Sids are exact-string map keys here, so
// every sid crossing a reader start/stop or announcement-dedup boundary is
// passed through this first.
static std::string normalizedSid(std::string sid) {
    while (!sid.empty()) {
        const char last = sid.back();
        if (last != ' ' && last != '\t' && last != '\r' && last != '\n' && last != '\0') {
            break;
        }
        sid.pop_back();
    }
    return sid;
}

void setRawErrorThreadSafe(
    const std::string& code,
    const std::string& message,
    const std::string& operation,
    bool recoverable);

// g_rawEpoch only ever moves FORWARD. Kotlin drives it from a monotonic
// counter, but a publish that overtakes a reconnect used to ASSIGN the older
// value and rewind the epoch; from then on every mute/frame push was rejected
// as stale for the rest of the process. Callers must hold g_sessionMutex.
void adoptEpochLocked(jlong epoch) {
    if (epoch > g_rawEpoch) {
        g_rawEpoch = epoch;
    }
    g_rawEpochForCallbacks.store(g_rawEpoch, std::memory_order_release);
}

// Full epoch write for the paths that legitimately own the value (session
// create, connect, disconnect). Callers must hold g_sessionMutex.
void setRawEpochLocked(jlong epoch) {
    g_rawEpoch = epoch;
    g_rawEpochForCallbacks.store(g_rawEpoch, std::memory_order_release);
}

struct ParticipantPcmQueue {
    // `identity` is what gets REPORTED to the UI (active-speaker events);
    // `trackSid` is kept for diagnostics, and is what the owning map is keyed by.
    //
    // These used to be a single field, initialised from the track SID. That made
    // every ActiveSpeakersChanged event carry a value like "TR_AMAobYX4..." rather
    // than "@user:host:DEVICE", so the Kotlin side compared it against
    // participant identities, never matched, and the speaking ring was permanently
    // dark. The event was firing correctly the whole time -- it was reporting in
    // the wrong namespace, which is why "no speaking events in the log" and
    // "speaking events with the wrong value" looked identical from the outside.
    //
    // They must stay separate because the queues are keyed by track SID on
    // purpose (see the comment where remote audio is subscribed): one participant
    // sharing screen-with-audio has TWO audio tracks, and collapsing them into an
    // identity-keyed queue doubles that participant's inflow and lets either
    // track's teardown destroy the shared buffer.
    ParticipantPcmQueue(std::string identity, std::string trackSid)
        : participantIdentity(std::move(identity)), trackSid(std::move(trackSid)) {}

    std::string participantIdentity;
    std::string trackSid;
    std::deque<std::int16_t> pcm;
    std::mutex queueMutex;
    std::atomic_bool active{true};
    std::atomic<std::int64_t> lastFrameNs{0};
    bool startupPrimed{false};
    bool starved{true};
    // Per-participant output gain (1.0 = unity, 0.0 = individually muted).
    // Written and read under queueMutex only: setParticipantGainEverywhere
    // writes it from the UI thread and mixOneFrame snapshots it from the mixer
    // thread. Muted queues still drain so starvation and jitter-depth
    // bookkeeping stay identical.
    float gain{1.0f};
    // Active-speaker detection: RMS of the RAW (pre-gain) decoded frames.
    // Fast-attack/slow-release EMA + hysteresis so the glow doesn't flicker
    // on word gaps; speaking state mutated only inside mixOneFrame's queue
    // lock section on the mixer thread.
    float levelEma{0.0f};
    bool speaking{false};
    std::int64_t lastVoiceNs{0};
};

void dispatchMixedAudioFrame(const std::vector<std::int16_t>& samples, std::int64_t timestampNs);
void dispatchRemoteParticipantConnected(const std::string& participantIdentity, const std::string& participantSid);

// Active-speaker thresholds (raw 16-bit RMS): enter above kSpeakStartLevel,
// drop below kSpeakStopLevel, hold kSpeakHoldNs through word gaps. Events are
// emitted at most every kSpeakerEmitMinIntervalNs and only on membership
// CHANGE of the speaking set.
constexpr float kSpeakStartLevel = 320.0f;
constexpr float kSpeakStopLevel = 170.0f;
constexpr std::int64_t kSpeakHoldNs = 500LL * 1000LL * 1000LL;
constexpr std::int64_t kSpeakerEmitMinIntervalNs = 150LL * 1000LL * 1000LL;

// Active-speaker emission state: only dispatch on set changes, throttled.
std::mutex g_speakersEmitMutex;
std::vector<std::string> g_lastSpeakers;
std::int64_t g_lastSpeakerEmitNs = 0;

// JNI thread attach helpers (defined near the other callback plumbing).
bool getJniEnv(JNIEnv** env, bool* attached, const char* callbackType = "jni-callback");
void releaseJniEnv(bool attached);

void dispatchActiveSpeakersChanged(JNIEnv* env, jlong sessionId, jlong epoch, const std::vector<std::string>& speakers);
void dispatchNativeEvent(JNIEnv* env, jobject event);
void dispatchRemoteParticipantDisconnected(const std::string& participantIdentity, const std::string& participantSid);
void dispatchRemoteAudioMuteChanged(const std::string& participantIdentity, bool muted);
void dispatchRemoteConnectionQuality(const std::string& participantIdentity, long long jitterMs, long long rttMs, double lossPct);
void requestAudioTrackResubscribe(const std::string& participantIdentity, const std::string& trackSid, long long jbTargetMs);
void stopRemoteReaderLocked(const std::string& rawTrackSid);
void stopRemoteReadersForParticipantLocked(const std::string& participantIdentity);

struct PcmDiagnostics {
    double rms{0.0};
    std::int32_t peak{0};
    std::int32_t min{0};
    std::int32_t max{0};
    bool allZero{true};
    std::vector<std::int32_t> firstSamples;
};

PcmDiagnostics computePcmDiagnostics(const std::int16_t* samples, std::size_t sampleCount) {
    PcmDiagnostics diagnostics;
    if (samples == nullptr || sampleCount == 0) {
        return diagnostics;
    }

    double sumSquares = 0.0;
    for (std::size_t i = 0; i < sampleCount; ++i) {
        const auto value = static_cast<std::int32_t>(samples[i]);
        const auto absValue = std::abs(value);
        if (i == 0 || value < diagnostics.min) {
            diagnostics.min = value;
        }
        if (i == 0 || value > diagnostics.max) {
            diagnostics.max = value;
        }
        if (diagnostics.firstSamples.size() < 10) {
            diagnostics.firstSamples.push_back(value);
        }
        if (value != 0) {
            diagnostics.allZero = false;
        }
        diagnostics.peak = std::max(diagnostics.peak, absValue);
        sumSquares += static_cast<double>(value) * static_cast<double>(value);
    }
    diagnostics.rms = std::sqrt(sumSquares / static_cast<double>(sampleCount));
    return diagnostics;
}

struct AudioSourceFramePacket {
    std::vector<std::int16_t> samples;
    int sampleRate{kSampleRate};
    int channels{kChannels};
    int samplesPerChannel{kSamplesPerChannel};
    std::int64_t timestampNs{0};
};

class NativeAudioSourceWorker {
public:
    ~NativeAudioSourceWorker() {
        stop();
    }

    void start(const std::shared_ptr<livekit::AudioSource>& source) {
        stop();
        resetStats();
        {
            std::lock_guard<std::mutex> lock(mutex_);
            source_ = source;
            running_ = source_ != nullptr;
            queue_.clear();
            lastStatsLog_ = std::chrono::steady_clock::now();
        }
        if (!source) {
            return;
        }
        worker_ = std::thread([this] { run(); });
        std::cout << "DEBUG [OrbitLiveKitAudio] audio source worker started cadenceMs="
                  << kFrameMs << " captureTimeoutMs=" << kAudioSourceCaptureTimeoutMs << std::endl;
    }

    void stop() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            running_ = false;
            queue_.clear();
        }
        cv_.notify_all();
        if (worker_.joinable()) {
            if (worker_.get_id() == std::this_thread::get_id()) {
                worker_.detach();
            } else {
                worker_.join();
            }
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            source_.reset();
        }
    }

    bool enqueue(
        std::vector<std::int16_t>&& samples,
        int sampleRate,
        int channels,
        int samplesPerChannel,
        std::int64_t timestampNs) {
        framesReceivedFromKotlin_.fetch_add(1, std::memory_order_relaxed);
        const auto nowNs = std::chrono::steady_clock::now().time_since_epoch().count();
        const auto prevEnqueueNs = lastEnqueueSteadyNs_.exchange(nowNs, std::memory_order_relaxed);
        if (prevEnqueueNs != 0 && nowNs > prevEnqueueNs) {
            const auto intervalNs = static_cast<std::uint64_t>(nowNs - prevEnqueueNs);
            enqIntervalTotalNs_.fetch_add(intervalNs, std::memory_order_relaxed);
            enqIntervalCount_.fetch_add(1, std::memory_order_relaxed);
            auto prevMin = enqIntervalMinNs_.load(std::memory_order_relaxed);
            while (intervalNs < prevMin &&
                   !enqIntervalMinNs_.compare_exchange_weak(prevMin, intervalNs, std::memory_order_relaxed)) {
            }
            auto prevMax = enqIntervalMaxNs_.load(std::memory_order_relaxed);
            while (intervalNs > prevMax &&
                   !enqIntervalMaxNs_.compare_exchange_weak(prevMax, intervalNs, std::memory_order_relaxed)) {
            }
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!running_ || source_ == nullptr) {
                setLastCaptureError("audio source worker is not running");
                return false;
            }
            if (queue_.size() >= 8) {
                queue_.pop_front();
                framesDroppedDueBackpressure_.fetch_add(1, std::memory_order_relaxed);
                logBackpressureDropThrottled();
            }
            AudioSourceFramePacket packet;
            packet.samples = std::move(samples);
            packet.sampleRate = sampleRate;
            packet.channels = channels;
            packet.samplesPerChannel = samplesPerChannel;
            packet.timestampNs = timestampNs;
            queue_.push_back(std::move(packet));
        }
        cv_.notify_one();
        return true;
    }

    /**
     * Frames this worker has produced and handed onward.
     *
     * Exposed for the TXSTAT sender-side diagnostic, which has to be able to say
     * "captured N frames, sent M packets" and let a reader check whether those two
     * are consistent. Without this the sender could only report the packet side,
     * and "my mic is dead" and "my encoder is dead" were indistinguishable from the
     * outside -- which is exactly why every "he can't hear me" report in this
     * project was unfalsifiable from the sender's own logs.
     *
     * Reads the same counter the `audio source stats` line reports, so the two
     * cannot drift by counting different things.
     */
    std::uint64_t workerFramesCaptured() const {
        return workerFramesCaptured_.load(std::memory_order_relaxed);
    }

private:
    // C2: backpressure drops used to be silent counters — a stalled JVM capture
    // thread looked like "connected but silent". Alarm (throttled to 1/s) on every drop.
    void logBackpressureDropThrottled() {
        const auto nowNs = std::chrono::steady_clock::now().time_since_epoch().count();
        auto prevNs = lastBackpressureWarnNs_.load(std::memory_order_relaxed);
        constexpr auto kThrottleNs = std::chrono::duration_cast<std::chrono::steady_clock::duration>(
            std::chrono::seconds(1)).count();
        if (prevNs != 0 && nowNs - prevNs < kThrottleNs) {
            return;
        }
        if (!lastBackpressureWarnNs_.compare_exchange_strong(prevNs, nowNs, std::memory_order_relaxed)) {
            return;
        }
        std::cerr << "WARN [OrbitLiveKitAudio] MIC_BACKPRESSURE_DROP operation=nativePushAudioFrame"
                  << " message=\"JVM mic frame dropped; native worker still held the previous frame\""
                  << " totalDropped=" << framesDroppedDueBackpressure_.load(std::memory_order_relaxed)
                  << std::endl;
    }

    void resetStats() {
        framesReceivedFromKotlin_ = 0;
        framesSubmittedToLiveKit_ = 0;
        framesAcceptedByLiveKit_ = 0;
        framesTimedOut_ = 0;
        framesDroppedDueBackpressure_ = 0;
        workerFramesWithTonePeakGreaterThan1000_ = 0;
        workerFramesSilent_ = 0;
        workerFramesCaptured_ = 0;
        workerFramesSkippedMuted_ = 0;
        captureTotalNs_ = 0;
        captureMaxNs_ = 0;
        captureCount_ = 0;
        sleepTotalNs_ = 0;
        sleepCount_ = 0;
        lastEnqueueSteadyNs_ = 0;
        enqIntervalTotalNs_ = 0;
        enqIntervalCount_ = 0;
        enqIntervalMinNs_ = UINT64_MAX;
        enqIntervalMaxNs_ = 0;
        formatDiagnosticsLogged_ = 0;
        captureFrameStatsLogged_ = 0;
        preCaptureDiagnosticsLogged_ = 0;
        lastMuteGateWarnNs_ = 0;
        workerFrameSequence_ = 0;
        lastFrameTimestampNs_ = 0;
        setLastCaptureError("");
    }

    void run() {
        using clock = std::chrono::steady_clock;

        while (true) {
            AudioSourceFramePacket packet;
            std::shared_ptr<livekit::AudioSource> source;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                cv_.wait(lock, [this] { return !running_ || !queue_.empty(); });
                if (!running_ && queue_.empty()) {
                    break;
                }
                if (queue_.empty()) {
                    continue;
                }
                packet = std::move(queue_.front());
                queue_.pop_front();
                source = source_;
            }

            // NO consumer-side pacing here. In queue_size_ms==0 real-time mode
            // captureFrame() costs ~0.07 ms (measured), and the producer is already
            // hardware-paced at exactly one frame per 10 ms (blocking TargetDataLine
            // read). A fixed kFrameMs sleep on top made the consumer run ~5 % slower
            // than the producer, so the single slot perpetually overwrote — measured
            // ~50 % frame loss ("stingy" voice). The cv-wait alone provides correct
            // backpressure: bursts are drained immediately instead of dropped.
            if (source == nullptr || packet.samples.empty()) {
                logStatsIfDue(false);
                continue;
            }

            bool timestampMonotonic = true;
            if (lastFrameTimestampNs_ != 0 && packet.timestampNs <= lastFrameTimestampNs_) {
                timestampMonotonic = false;
            }
            lastFrameTimestampNs_ = packet.timestampNs;
            if (formatDiagnosticsLogged_ < 10) {
                formatDiagnosticsLogged_ += 1;
                std::cout << "DEBUG [OrbitLiveKitAudio] audio source frame #"
                          << formatDiagnosticsLogged_
                          << " sampleRate=" << packet.sampleRate
                          << " channels=" << packet.channels
                          << " samplesPerChannel=" << packet.samplesPerChannel
                          << " bytes=" << packet.samples.size() * sizeof(std::int16_t)
                          << " durationMs=" << audioFrameDurationMs(packet.sampleRate, packet.samplesPerChannel)
                          << " format=int16 signed little-endian"
                          << " ownsNativeMemory=true"
                          << " timestampNs=" << packet.timestampNs
                          << " timestampMonotonic=" << (timestampMonotonic ? "true" : "false")
                          << std::endl;
            }

            try {
                livekit::AudioFrame frame(
                    std::move(packet.samples),
                    packet.sampleRate,
                    packet.channels,
                    packet.samplesPerChannel);
                const auto frameSequence = ++workerFrameSequence_;
                const auto& frameSamples = frame.data();
                const auto pcmStats = computePcmDiagnostics(frameSamples.data(), frameSamples.size());
                if (pcmStats.peak > 1000) {
                    workerFramesWithTonePeakGreaterThan1000_.fetch_add(1, std::memory_order_relaxed);
                }
                if (pcmStats.allZero || pcmStats.peak <= 3) {
                    workerFramesSilent_.fetch_add(1, std::memory_order_relaxed);
                }

                // Lock-free gate: atomics are the primary source of truth. The session
                // mutex is only tried opportunistically to refresh the track-mute cache;
                // on contention we fall back to the last known mute intent instead of
                // defaulting to "capture anyway".
                //
                // THE CACHE IS NOT REFRESHED HERE ANY MORE.
                //
                // It used to be, from g_audioTrack->muted(), and that was the bug:
                // the refresh is a READ of LiveKit's track state, while the mute
                // setter's write to g_micTrackMutedCache is the AUTHORITATIVE one
                // (see nativeSetAudioMuted, which stores it right beside
                // track->mute()). The two disagree in a window, and the worker won
                // that window often enough to run for a whole call with the gate
                // inverted.
                //
                // Observed in run 64, with the mic unmuted the entire time
                // (native mic muted=false, CallPathStats muted=0, no native mute
                // event at all):
                //
                //     workerFramesSkippedMuted:  0 -> 325 -> ... -> 35,244
                //
                // 35,244 discarded frames out of 66,771 captured. Over half the
                // microphone thrown away, so peers heard chopped fragments --
                // enough for Element's speaking indicator to light (it only needs
                // non-zero level) while producing no audible speech. That is the
                // exact shape of "his card lit up but he heard nothing".
                //
                // Reading g_audioTrack->muted() here cannot be a refresh, because
                // LiveKit's track state is set by the same setter but observed
                // through a shared_ptr whose mute flag can lag or be replaced on
                // reconnect. The setter already writes the truth; this gate just
                // has to read it.
                const bool acceptingFrames = g_acceptingFrames.load(std::memory_order_relaxed);
                const bool micPublished = g_micPublished.load(std::memory_order_relaxed);
                const bool trackMuted = g_micTrackMutedCache.load(std::memory_order_relaxed);

                if (trackMuted || !acceptingFrames || !micPublished) {
                    if (trackMuted) {
                        workerFramesSkippedMuted_.fetch_add(1, std::memory_order_relaxed);
                        // A gate stuck closed is now diagnosable in one line instead
                        // of only being visible as a rising counter nobody knows the
                        // meaning of. Deliberately rate-limited to once per second:
                        // every skipped frame is a log line otherwise.
                        //
                        // 30s at 10ms per frame is ~3,000 discarded frames with no
                        // mute event ever logged, which is far past anyone actually
                        // holding a mute button and squarely in "this is broken".
                        const auto skipped = workerFramesSkippedMuted_.load(std::memory_order_relaxed);
                        const auto nowNs = std::chrono::steady_clock::now().time_since_epoch().count();
                        // nowNs is a raw steady_clock count in NANOSECONDS, so the interval has to be
                        // expressed in nanoseconds too -- comparing against
                        // std::chrono::seconds is a unit mismatch and does not compile.
                        if (nowNs - lastMuteGateWarnNs_ >=
                            std::chrono::duration_cast<std::chrono::nanoseconds>(
                                std::chrono::seconds(1))
                                .count()) {
                            lastMuteGateWarnNs_ = nowNs;
                            std::cout << "WARN [OrbitLiveKitAudio] mic frames being discarded as MUTED: "
                                      << "consecutiveSkipped=" << skipped
                                      << " acceptingFrames=" << (acceptingFrames ? "true" : "false")
                                      << " micPublished=" << (micPublished ? "true" : "false")
                                      << std::endl;
                        }
                    }
                    if (preCaptureDiagnosticsLogged_ < 20) {
                        preCaptureDiagnosticsLogged_ += 1;
                        std::cout << "DEBUG [OrbitLiveKitAudio] worker frame skipped before captureFrame seq="
                                  << frameSequence
                                  << " acceptingFrames=" << (acceptingFrames ? "true" : "false")
                                  << " micPublished=" << (micPublished ? "true" : "false")
                                  << " trackMuted=" << (trackMuted ? "true" : "false")
                                  << " stateRead=true"
                                  << std::endl;
                    }
                    logStatsIfDue(false);
                    continue;
                }

                if (g_expectedTestTone.load(std::memory_order_relaxed) && pcmStats.peak < 1000) {
                    std::cerr << "ERROR [OrbitLiveKitAudio] test tone lost before captureFrame seq="
                              << frameSequence
                              << " peak=" << pcmStats.peak
                              << " rms=" << pcmStats.rms
                              << std::endl;
                }

                if (preCaptureDiagnosticsLogged_ < 20) {
                    preCaptureDiagnosticsLogged_ += 1;
                    std::cout << "DEBUG [OrbitLiveKitAudio] worker pre-capture frame seq="
                              << frameSequence
                              << " min=" << pcmStats.min
                              << " max=" << pcmStats.max
                              << " peak=" << pcmStats.peak
                              << " rms=" << pcmStats.rms
                              << " first10Samples=[";
                    for (std::size_t i = 0; i < pcmStats.firstSamples.size(); ++i) {
                        if (i > 0) {
                            std::cout << ",";
                        }
                        std::cout << pcmStats.firstSamples[i];
                    }
                    std::cout << "]"
                              << " allZero=" << (pcmStats.allZero ? "true" : "false")
                              << " muted=" << (trackMuted ? "true" : "false")
                              << " acceptingFrames=" << (acceptingFrames ? "true" : "false")
                              << " micPublished=" << (micPublished ? "true" : "false")
                              << " trackMuted=" << (trackMuted ? "true" : "false")
                              << std::endl;
                }

                // This was `if (formatDiagnosticsLogged_ <= 10)` — a
                // *comparison* where an increment was meant. The counter is
                // bumped a hundred lines earlier for a different diagnostic and
                // never here, so the guard never went false and this printed
                // once per 10 ms frame: ~100 lines/second, 1,259 of them in a
                // four-minute call. That drowned every other line in the log,
                // including the two real errors, and the iostream lock it
                // contends for is on the audio push thread (pushMaxMs was
                // 63 ms against a 10 ms frame budget).
                //
                // `captureFrameStatsLogged_` is its own counter for this one.
                if (captureFrameStatsLogged_ < 5) {
                    captureFrameStatsLogged_ += 1;
                    std::cout << "DEBUG [OrbitLiveKitAudio] LiveKit AudioFrame("
                              << "buffer.size=" << frame.data().size()
                              << ", totalSamples=" << frame.totalSamples()
                              << ", sampleRate=" << frame.sampleRate()
                              << ", numChannels=" << frame.numChannels()
                              << ", samplesPerChannel=" << frame.samplesPerChannel()
                              << ") duration=" << frame.duration()
                              << std::endl;
                }
                framesSubmittedToLiveKit_.fetch_add(1, std::memory_order_relaxed);
                const auto captureStartNs = clock::now().time_since_epoch().count();
                source->captureFrame(frame, kAudioSourceCaptureTimeoutMs);
                const auto captureCostNs = static_cast<std::uint64_t>(
                    clock::now().time_since_epoch().count() - captureStartNs);
                captureTotalNs_.fetch_add(captureCostNs, std::memory_order_relaxed);
                captureCount_.fetch_add(1, std::memory_order_relaxed);
                auto prevMaxNs = captureMaxNs_.load(std::memory_order_relaxed);
                while (captureCostNs > prevMaxNs &&
                       !captureMaxNs_.compare_exchange_weak(prevMaxNs, captureCostNs, std::memory_order_relaxed)) {
                }
                framesAcceptedByLiveKit_.fetch_add(1, std::memory_order_relaxed);
                workerFramesCaptured_.fetch_add(1, std::memory_order_relaxed);
                setLastCaptureError("");
                if (!g_firstAudioFrameAccepted.exchange(true)) {
                    std::cout << "DEBUG [OrbitLiveKitAudio] first audio frame accepted" << std::endl;
                }
            } catch (const std::exception& error) {
                const std::string message = error.what();
                if (message.find("timed out") != std::string::npos ||
                    message.find("timeout") != std::string::npos) {
                    framesTimedOut_.fetch_add(1, std::memory_order_relaxed);
                }
                setLastCaptureError(message);
            } catch (...) {
                setLastCaptureError("unknown captureFrame error");
            }

            logStatsIfDue(false);
        }

        logStatsIfDue(true);
        std::cout << "DEBUG [OrbitLiveKitAudio] audio source worker exited" << std::endl;
    }

    void setLastCaptureError(const std::string& error) {
        std::lock_guard<std::mutex> lock(errorMutex_);
        lastCaptureError_ = error;
    }

    std::string lastCaptureError() const {
        std::lock_guard<std::mutex> lock(errorMutex_);
        return lastCaptureError_;
    }

    void logStatsIfDue(bool force) {
        const auto now = std::chrono::steady_clock::now();
        bool shouldLog = force;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!shouldLog && now - lastStatsLog_ >= std::chrono::seconds(1)) {
                shouldLog = true;
            }
            if (shouldLog) {
                lastStatsLog_ = now;
            }
        }
        if (!shouldLog) {
            return;
        }

        const auto error = lastCaptureError();
        const auto captureCount = captureCount_.load(std::memory_order_relaxed);
        const auto sleepCount = sleepCount_.load(std::memory_order_relaxed);
        const auto enqCount = enqIntervalCount_.load(std::memory_order_relaxed);
        std::cout << "DEBUG [OrbitLiveKitAudio] audio source stats: received="
                  << framesReceivedFromKotlin_.load(std::memory_order_relaxed)
                  << " submitted=" << framesSubmittedToLiveKit_.load(std::memory_order_relaxed)
                  << " accepted=" << framesAcceptedByLiveKit_.load(std::memory_order_relaxed)
                  << " timedOut=" << framesTimedOut_.load(std::memory_order_relaxed)
                  << " dropped=" << framesDroppedDueBackpressure_.load(std::memory_order_relaxed)
                  << " workerFramesWithTonePeakGreaterThan1000=" << workerFramesWithTonePeakGreaterThan1000_.load(std::memory_order_relaxed)
                  << " workerFramesSilent=" << workerFramesSilent_.load(std::memory_order_relaxed)
                  << " workerFramesCaptured=" << workerFramesCaptured_.load(std::memory_order_relaxed)
                  << " workerFramesSkippedMuted=" << workerFramesSkippedMuted_.load(std::memory_order_relaxed)
                  << " captureAvgMs=" << (captureCount > 0
                        ? (captureTotalNs_.load(std::memory_order_relaxed) / captureCount) / 1e6 : 0.0)
                  << " captureMaxMs=" << captureMaxNs_.load(std::memory_order_relaxed) / 1e6
                  << " sleepAvgMs=" << (sleepCount > 0
                        ? (sleepTotalNs_.load(std::memory_order_relaxed) / sleepCount) / 1e6 : 0.0)
                  << " pushAvgMs=" << (enqCount > 0
                        ? (enqIntervalTotalNs_.load(std::memory_order_relaxed) / enqCount) / 1e6 : 0.0)
                  << " pushMinMs=" << (enqIntervalMinNs_.load(std::memory_order_relaxed) == UINT64_MAX ? 0.0
                        : enqIntervalMinNs_.load(std::memory_order_relaxed) / 1e6)
                  << " pushMaxMs=" << enqIntervalMaxNs_.load(std::memory_order_relaxed) / 1e6
                  << " lastCaptureError=" << (error.empty() ? "<none>" : error)
                  << std::endl;
    }

    mutable std::mutex mutex_;
    mutable std::mutex errorMutex_;
    std::condition_variable cv_;
    std::thread worker_;
    bool running_{false};
    std::shared_ptr<livekit::AudioSource> source_;
    std::deque<AudioSourceFramePacket> queue_;
    std::chrono::steady_clock::time_point lastStatsLog_{};
    std::string lastCaptureError_;
    int formatDiagnosticsLogged_{0};
    /**
     * Separate budget for the per-frame format dump. It previously shared
     * formatDiagnosticsLogged_ with a `<= 10` comparison that never incremented,
     * so it fired on every frame. See the use site for the full account.
     */
    int captureFrameStatsLogged_{0};
    int preCaptureDiagnosticsLogged_{0};
    std::uint64_t workerFrameSequence_{0};
    std::int64_t lastFrameTimestampNs_{0};

    std::atomic<std::uint64_t> framesReceivedFromKotlin_{0};
    std::atomic<std::uint64_t> framesSubmittedToLiveKit_{0};
    std::atomic<std::uint64_t> framesAcceptedByLiveKit_{0};
    std::atomic<std::uint64_t> framesTimedOut_{0};
    std::atomic<std::uint64_t> framesDroppedDueBackpressure_{0};
    std::atomic<std::int64_t> lastBackpressureWarnNs_{0};
    std::atomic<std::uint64_t> workerFramesWithTonePeakGreaterThan1000_{0};
    std::atomic<std::uint64_t> workerFramesSilent_{0};
    std::atomic<std::uint64_t> workerFramesCaptured_{0};
    std::atomic<std::uint64_t> workerFramesSkippedMuted_{0};
    // Rate limiter for the "mic frames discarded as muted" warning. Steady clock so
    // a wall-clock jump cannot stall the warning permanently or fire it in a burst.
    std::atomic<std::int64_t> lastMuteGateWarnNs_{0};
    std::atomic<std::uint64_t> captureTotalNs_{0};
    std::atomic<std::uint64_t> captureMaxNs_{0};
    std::atomic<std::uint32_t> captureCount_{0};
    std::atomic<std::uint64_t> sleepTotalNs_{0};
    std::atomic<std::uint32_t> sleepCount_{0};
    std::atomic<std::int64_t> lastEnqueueSteadyNs_{0};
    std::atomic<std::uint64_t> enqIntervalTotalNs_{0};
    std::atomic<std::uint32_t> enqIntervalCount_{0};
    std::atomic<std::uint64_t> enqIntervalMinNs_{UINT64_MAX};
    std::atomic<std::uint64_t> enqIntervalMaxNs_{0};
};

NativeAudioSourceWorker g_audioSourceWorker;

// Per-participant output gains requested from Kotlin. Kept outside the mixer
// so a setter that arrives before the participant's queue exists (or while no
// call is connected) is remembered and applied when addParticipant creates the
// queue. Entries are erased when the participant's readers are torn down, so
// Kotlin-side UI state and native state cannot diverge and the map cannot grow
// without bound across calls.
std::mutex g_participantGainsMutex;
std::unordered_map<std::string, float> g_participantGains;

class NativeAudioMixer {
public:
    ~NativeAudioMixer() {
        stop();
    }

// Raise the calling thread's priority where the platform allows it.
//
// Best effort by design: every call site must keep working on a platform or a
// scheduler where this does nothing, so failures are silent and the thread simply
// runs at default priority as it always did.
void raiseCurrentThreadPriority() {
#if defined(_WIN32)
    // TIME_CRITICAL is correct for the 10 ms mixer tick and safe for it, because
    // the thread sleeps between ticks rather than spinning. Restored to normal on
    // exit by the OS when the thread ends, so there is nothing to undo here.
    if (SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_TIME_CRITICAL) == 0) {
        // Fall back to the softer level rather than giving up: any boost beats none.
        SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_ABOVE_NORMAL);
    }
#endif
}

    void start() {
        bool expected = false;
        if (!running_.compare_exchange_strong(expected, true)) {
            return;
        }

        worker_ = std::thread([this] {
            // The mixer is the most time-critical thread in the process. It runs a
            // 10 ms tick that must never be late, because lateness is audible as
            // a dropout, and it is competing with threads that are not
            // time-critical at all.
            //
            // Measured: while Orbit was publishing its own screen share, the mixer
            // went from `starved=10 qDepth=3360` to `starved=283 qDepth=480` --
            // the playout buffer collapsed to its 10 ms floor and the line started
            // dropping chunks. A 1080p I420 share at 60 fps is 186 MB/s of frame
            // conversion, and at default priority the scheduler was quite happy to
            // let the mixer lose to it.
            //
            // THREAD_PRIORITY_TIME_CRITICAL rather than ABOVE_NORMAL because this
            // is a 10 ms deadline, not a throughput problem, and because the
            // alternative is the person on the other end hearing a glitch. The
            // thread sleeps between ticks, so it is not burning the CPU.
            raiseCurrentThreadPriority();
            mixLoop();
        });
    }

    void stop() {
        const bool wasRunning = running_.exchange(false);
        if (wasRunning && worker_.joinable()) {
            if (worker_.get_id() == std::this_thread::get_id()) {
                worker_.detach();
            } else {
                worker_.join();
            }
        }
        clearParticipants();
        mixedCallbackStartedLogged_ = false;
    }

    void addParticipant(const std::string& trackSid, const std::string& participantIdentity = "") {
        if (trackSid.empty()) {
            return;
        }

        std::shared_ptr<ParticipantPcmQueue> queue;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            // KEY BY PARTICIPANT IDENTITY, not track SID.
            //
            // This map is read by identity everywhere else -- applyParticipantGain,
            // removeParticipant, the active-speaker UI event -- while this one
            // writer used the track SID. Those are different strings, so every
            // identity-keyed lookup missed and silently returned:
            //
            //   stored   participants_["TR_AMwWxXt3pkqKK6"]
            //   sought   participants_["@hamzamasudbutt:matrix.org:BzPLGdcHdw"]
            //
            // Per-participant volume and mute were therefore dead on every group
            // call, which is why a caller heard some peers and not others: gain was
            // never applied to anyone, so audibility came down to which queue the
            // mixer happened to favour, and that changed with join order.
            //
            // Identity is also the correct key on the merits. A participant can
            // publish two audio tracks (screen share with audio mirrors their mic),
            // and two queues under one identity is exactly what the share-tile
            // volume ban in docs/planning/ESCAPE_FROM_HELL.md H15 depends on: both
            // queues share the identity key, so one slider controls both.
            //
            // Falls back to the track SID only when the identity is genuinely
            // unknown, because the mixer pushes this value into the active-speaker
            // event the UI matches on and an empty string would never match.
            // KEY BY TRACK SID. This is deliberate, and an earlier commit changed it to
            // identity -- which was wrong, and the struct's own comment above says
            // why:
            //
            //   "They must stay separate because the queues are keyed by track SID on
            //    purpose ... one participant sharing screen-with-audio has TWO audio
            //    tracks, and collapsing them into an identity-keyed queue doubles
            //    that participant's inflow and lets either track's teardown destroy
            //    the shared buffer."
            //
            // Two queues per participant is the CORRECT design here, not a bug. The
            // mixer drains each independently, so a screen-share track and a mic
            // track must not share a buffer. Identity-keying merged them, which is
            // what produced run 64's split voice.
            //
            // Identity-keyed operations (gain, removal) are handled by
            // setParticipantGainEverywhere, which already fans ONE identity out to
            // EVERY track SID belonging to it, and by removeParticipant call sites
            // which iterate readers rather than assuming a single key.
            //
            // So the volume/mute bug was never "the map is keyed wrongly". It was
            // that applyParticipantGain was reached with an identity, and the
            // fan-out's own identity matcher is what was fixed. This key was
            // reverted.
            auto& existing = participants_[trackSid];
            if (existing == nullptr) {
                // Report the PARTICIPANT identity, not the track SID: this value is
                // pushed into the active-speaker event the UI matches on, and an
                // empty string there silently never matches anything.
                //
                // The fallback below is what made ONE PERSON appear under TWO
                // NAMES in a live call. `startRemoteReaderLocked` computes the
                // same `participantIdentity.empty() ? track->sid() : ...`, and a
                // participant publishing two audio tracks (screen share with
                // audio mirrors the mic) produced:
                //
                //   active speakers [@marioreo:matrix.org:djpYnE6QIj, TR_AMpAsYgfYnJ4z7]
                //   active speakers [@marioreo:matrix.org:djpYnE6QIj]
                //   active speakers [TR_AMpAsYgfYnJ4z7]      <- same person, again
                //
                // Both entries are the same human. Consequences, all observed in
                // two real logs:
                //
                //  - the speaking ring lit the wrong tile, or counted one person
                //    as two speakers;
                //  - per-participant volume and mute were applied by an identity
                //    matcher (setParticipantGainEverywhere fans one identity out
                //    to every track SID), so a `TR_` key never matched a real
                //    identity and that track stayed at unity gain;
                //  - the call surface had to grow a workaround comparing both
                //    the full identity and `substringBeforeLast(':')` to avoid
                //    rendering the same human as two tiles.
                //
                // The map stays keyed by TRACK SID on purpose -- run 64's split
                // voice came from merging a share track and a mic track into one
                // identity-keyed buffer, and H15 depends on two queues existing.
                // Keying and REPORTING are different questions, and only the
                // reporting was wrong.
                //
                // So: keep the track SID as the key, and report the identity
                // whenever we have one. An empty identity is now the only case
                // that falls back, and it is logged at WARN because a queue with
                // no identity cannot be matched to a person by anything
                // downstream.
                const std::string reportedIdentity = participantIdentity.empty() ? trackSid : participantIdentity;
                if (participantIdentity.empty()) {
                    std::cerr << "WARN [OrbitLiveKitAudio] remote audio track has NO participant identity; "
                              << "speaking/volume will key on the track sid instead trackSid="
                              << trackSid << std::endl;
                }
                existing = std::make_shared<ParticipantPcmQueue>(reportedIdentity, trackSid);
                std::cout << "DEBUG [OrbitLiveKitAudio] remote audio participant added: trackSid="
                          << trackSid << " identity=" << existing->participantIdentity << std::endl;
            }
            existing->active = true;
            queue = existing;
        }
        // Re-apply any gain the UI set before this queue existed so a mute or
        // slider position survives participant reconnects mid-call.
        float rememberedGain = 1.0f;
        bool hasRememberedGain = false;
        {
            std::lock_guard<std::mutex> gainLock(g_participantGainsMutex);
            auto it = g_participantGains.find(trackSid);
            if (it != g_participantGains.end()) {
                rememberedGain = it->second;
                hasRememberedGain = true;
            } else if (!participantIdentity.empty()) {
                auto idIt = g_participantGains.find(participantIdentity);
                if (idIt != g_participantGains.end()) {
                    rememberedGain = idIt->second;
                    hasRememberedGain = true;
                }
            }
        }
        if (hasRememberedGain && queue != nullptr) {
            std::lock_guard<std::mutex> queueLock(queue->queueMutex);
            queue->gain = rememberedGain;
        }
    }

    void applyParticipantGain(const std::string& trackSid, float gain) {
        // PARAMETER IS A TRACK SID, and it must stay named that way.
        //
        // It was called `identity`, which is how this whole bug class formed:
        // participants_ is keyed by track SID, callers passed identities "because
        // the parameter said identity", and every such lookup missed in silence.
        // setParticipantGainEverywhere is the ONLY caller, and it fans one identity
        // out to every track SID first -- this function therefore only ever sees a
        // track SID.
        if (trackSid.empty()) {
            return;
        }

        std::lock_guard<std::mutex> lock(mutex_);
        auto it = participants_.find(trackSid);
        if (it == participants_.end() || it->second == nullptr) {
            // Logged rather than swallowed. This used to `return` in silence, which
            // is how a keying mismatch hid for so long: the setter was called, the
            // map was written, and the only evidence was a UI slider that did
            // nothing and no log line anywhere saying so.
            //
            // The known keys are printed because "not found" is only actionable if
            // you can see what IS in the map.
            std::cout << "WARN [OrbitLiveKitAudio] applyParticipantGain MISS trackSid=" << trackSid
                      << " gain=" << gain << " knownKeys=";
            for (const auto& entry : participants_) {
                std::cout << "[" << entry.first << "]";
            }
            std::cout << std::endl;
            return;
        }
        std::lock_guard<std::mutex> queueLock(it->second->queueMutex);
        it->second->gain = gain;
    }

    void removeParticipant(const std::string& trackSid) {
        // A TRACK SID, for the same reason as applyParticipantGain. All three
        // call sites pass reader->trackSid.
        //
        // One of those three briefly passed reader->participantIdentity, which
        // cannot match a track-SID-keyed map -- so the removal found nothing,
        // returned quietly, and left the queue alive for the rest of the call. That
        // is the leak behind PARTLEDGER reporting connects without disconnects, and
        // behind remote audio readers never being reaped.
        if (trackSid.empty()) {
            return;
        }

        std::shared_ptr<ParticipantPcmQueue> removed;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            auto it = participants_.find(trackSid);
            if (it == participants_.end()) {
                // A removal that finds nothing is exactly the failure that leaves a
                // queue alive for the rest of the call, so it is logged rather than
                // swallowed.
                std::cout << "WARN [OrbitLiveKitAudio] removeParticipant MISS trackSid=" << trackSid
                          << " (queue left in place) knownKeys=";
                for (const auto& entry : participants_) {
                    std::cout << "[" << entry.first << "]";
                }
                std::cout << std::endl;
                return;
            }
            removed = it->second;
            participants_.erase(it);
        }
        if (removed != nullptr) {
            removed->active = false;
            std::lock_guard<std::mutex> queueLock(removed->queueMutex);
            removed->pcm.clear();
        }
    }

    void clearParticipants() {
        std::unordered_map<std::string, std::shared_ptr<ParticipantPcmQueue>> participants;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            participants.swap(participants_);
        }

        for (const auto& entry : participants) {
            if (entry.second == nullptr) {
                continue;
            }
            entry.second->active = false;
            std::lock_guard<std::mutex> queueLock(entry.second->queueMutex);
            entry.second->pcm.clear();
        }

        // Fresh call must not inherit the previous call's speaking set.
        std::lock_guard<std::mutex> emitLock(g_speakersEmitMutex);
        g_lastSpeakers.clear();
        g_lastSpeakerEmitNs = 0;
    }

// Convert input PCM at inSampleRate to outSampleRate using linear interpolation
inline std::vector<std::int16_t> resampleLinear(
    const std::int16_t* inSamples,
    std::size_t inCount,
    int inSampleRate,
    int outSampleRate) {
    if (inSamples == nullptr || inCount == 0 || inSampleRate <= 0 || outSampleRate <= 0) {
        return {};
    }
    if (inSampleRate == outSampleRate) {
        return std::vector<std::int16_t>(inSamples, inSamples + inCount);
    }
    const std::size_t outCount = static_cast<std::size_t>(
        (static_cast<double>(inCount) * outSampleRate) / inSampleRate);
    if (outCount == 0) {
        return {};
    }
    std::vector<std::int16_t> outSamples(outCount);
    const double ratio = static_cast<double>(inCount) / static_cast<double>(outCount);
    for (std::size_t i = 0; i < outCount; ++i) {
        const double inIdx = i * ratio;
        const std::size_t idx0 = static_cast<std::size_t>(inIdx);
        const std::size_t idx1 = std::min(idx0 + 1, inCount - 1);
        const double frac = inIdx - idx0;
        const double s0 = static_cast<double>(inSamples[idx0]);
        const double s1 = static_cast<double>(inSamples[idx1]);
        const double val = s0 + frac * (s1 - s0);
        outSamples[i] = static_cast<std::int16_t>(std::clamp(val, -32768.0, 32767.0));
    }
    return outSamples;
}

    void pushParticipantFrame(
        // NAMED trackSid, and it IS the map key (see addParticipant). It was called
        // `identity`, which is how three call sites ended up passing the wrong thing:
        // a track SID where an identity was expected and vice versa. Both are
        // plausible-looking strings, so nothing failed loudly -- queues were just
        // created under keys no other operation could find.
        const std::string& trackSid,
        const std::int16_t* pcm,
        int sampleRate,
        int samplesPerChannel,
        int channels,
        std::int64_t timestampNs) {
        if (trackSid.empty() ||
            pcm == nullptr ||
            sampleRate <= 0 ||
            samplesPerChannel <= 0 ||
            channels <= 0) {
            return;
        }

        std::shared_ptr<ParticipantPcmQueue> queue;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            auto& existing = participants_[trackSid];
            if (existing == nullptr) {
                // Same key as addParticipant, so this REUSES the queue it created
                // instead of minting a second one for the same audio track. That
                // reuse is the whole point: two queues for one track meant the
                // mixer drained each independently and split one voice across two
                // buffers too thin to clear kStarvationReprimeSamples.
                //
                // The identity is unknown on this path, so it falls back to the
                // track SID -- which is what the active-speaker UI would see, and
                // why the LiveKit path (which knows the identity) must be the one
                // that creates the queue.
                existing = std::make_shared<ParticipantPcmQueue>(trackSid, trackSid);
                std::cout << "DEBUG [OrbitLiveKitAudio] remote audio participant added: "
                          << trackSid << " inSampleRate=" << sampleRate << " inChannels=" << channels << std::endl;
            }
            existing->active = true;
            queue = existing;
        }

        std::vector<std::int16_t> mono;
        mono.reserve(static_cast<std::size_t>(samplesPerChannel));
        for (int sample = 0; sample < samplesPerChannel; ++sample) {
            if (channels == 1) {
                mono.push_back(pcm[sample]);
            } else {
                std::int32_t sum = 0;
                for (int channel = 0; channel < channels; ++channel) {
                    sum += pcm[sample * channels + channel];
                }
                mono.push_back(static_cast<std::int16_t>(sum / channels));
            }
        }

        std::vector<std::int16_t> resampledMono;
        const std::int16_t* finalSamples = mono.data();
        std::size_t finalCount = mono.size();
        if (sampleRate != kSampleRate) {
            resampledMono = resampleLinear(mono.data(), mono.size(), sampleRate, kSampleRate);
            finalSamples = resampledMono.data();
            finalCount = resampledMono.size();
        }

        {
            std::lock_guard<std::mutex> queueLock(queue->queueMutex);
            std::size_t overflowDropped = 0;
            while (queue->pcm.size() + finalCount > kMaxParticipantQueueSamples && !queue->pcm.empty()) {
                queue->pcm.pop_front();
                ++overflowDropped;
            }
            if (overflowDropped > 0) {
                overflowDroppedSamples_.fetch_add(
                    static_cast<std::int64_t>(overflowDropped), std::memory_order_relaxed);
                const auto nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::system_clock::now().time_since_epoch()).count();
                auto lastWarnMs = lastOverflowWarnMs_.load(std::memory_order_relaxed);
                if (nowMs - lastWarnMs >= 1000 &&
                    lastOverflowWarnMs_.compare_exchange_strong(lastWarnMs, nowMs)) {
                    std::cout << "WARN [OrbitLiveKitAudio] remote audio queue overflow for "
                              << queue->participantIdentity
                              << " trackSid=" << queue->trackSid
                              << " droppedSamples=" << overflowDropped << std::endl;
                }
            }
            queue->pcm.insert(queue->pcm.end(), finalSamples, finalSamples + finalCount);
            if (queue->pcm.size() >= kTargetParticipantQueueSamples) {
                queue->startupPrimed = true;
            }
            queue->lastFrameNs = timestampNs;
        }
    }

private:
    /**
     * Deepest active queue right now, for MIXSTAT.
     *
     * This is the measurement that distinguishes "the mixer is behind" from
     * "the mixer is fine and something upstream is wrong". Before the catch-up
     * fix, `qDepth` would have sat pinned at 160 ms with `drops` climbing, which
     * is the signature of a drain rate that can never exceed the arrival rate.
     * Reading each queue's depth takes that queue's own mutex briefly; it runs
     * once per 500 frames (~5 s), so the cost is irrelevant next to the
     * diagnosis it buys.
     */
    std::size_t maxQueueDepthSamples() {
        const auto queues = snapshotQueues();
        std::size_t deepest = 0;
        for (const auto& queue : queues) {
            if (queue == nullptr) {
                continue;
            }
            std::lock_guard<std::mutex> queueLock(queue->queueMutex);
            deepest = std::max(deepest, queue->pcm.size());
        }
        return deepest;
    }

    std::vector<std::shared_ptr<ParticipantPcmQueue>> snapshotQueues() {
        std::vector<std::shared_ptr<ParticipantPcmQueue>> queues;
        std::lock_guard<std::mutex> lock(mutex_);
        queues.reserve(participants_.size());
        for (const auto& entry : participants_) {
            if (entry.second != nullptr && entry.second->active) {
                queues.push_back(entry.second);
            }
        }
        return queues;
    }

    void mixOneFrame() {
        const auto queues = snapshotQueues();
        if (queues.empty()) {
            return;
        }

        std::vector<std::int32_t> accumulator(kSamplesPerChannel, 0);
        int activeParticipants = 0;
        std::vector<std::string> speakers;
        const auto steadyNowNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();

        for (const auto& queue : queues) {
            if (queue == nullptr || !queue->active) {
                continue;
            }

            std::vector<std::int16_t> frame(kSamplesPerChannel, 0);
            {
                std::lock_guard<std::mutex> queueLock(queue->queueMutex);
                if (!queue->startupPrimed && queue->pcm.size() >= kTargetParticipantQueueSamples) {
                    queue->startupPrimed = true;
                }
                // Startup waits once for the full jitter target. After a real
                // drain only a short top-up gate applies: re-arming the whole
                // 80 ms buffer turned every network-jitter pause on bursty
                // server paths into an >=80 ms silent hole (robotic chop).
                const std::size_t requiredDepth = queue->startupPrimed
                    ? kStarvationReprimeSamples
                    : kTargetParticipantQueueSamples;
                const bool canRead = !queue->starved || queue->pcm.size() >= requiredDepth;
                const auto samplesToRead = canRead
                    ? std::min<std::size_t>(queue->pcm.size(), kSamplesPerChannel)
                    : 0;
                for (std::size_t i = 0; i < samplesToRead; ++i) {
                    frame[i] = queue->pcm.front();
                    queue->pcm.pop_front();
                }
                if (samplesToRead == 0) {
                    queue->starved = true;
                    starvationEvents_.fetch_add(1, std::memory_order_relaxed);
                } else {
                    queue->starved = false;
                }

                // Active-speaker metering on the RAW pre-gain audio: a locally
                // muted participant still glows while talking.
                if (samplesToRead > 0) {
                    double squareSum = 0.0;
                    for (std::size_t i = 0; i < samplesToRead; ++i) {
                        const double v = static_cast<double>(frame[i]);
                        squareSum += v * v;
                    }
                    const float rms = static_cast<float>(
                        std::sqrt(squareSum / static_cast<double>(samplesToRead)));
                    // Fast attack / slow release so word gaps don't flicker.
                    queue->levelEma = rms >= queue->levelEma
                        ? rms
                        : 0.85f * queue->levelEma + 0.15f * rms;
                    if (queue->levelEma >= kSpeakStartLevel) {
                        queue->lastVoiceNs = steadyNowNs;
                    }
                    const bool aboveStop = queue->levelEma >= kSpeakStopLevel;
                    const bool held = queue->lastVoiceNs != 0 &&
                        steadyNowNs - queue->lastVoiceNs < kSpeakHoldNs;
                    queue->speaking = queue->levelEma >= kSpeakStartLevel ||
                        (queue->speaking && aboveStop) ||
                        (queue->speaking && held);
                    if (queue->speaking) {
                        speakers.push_back(queue->participantIdentity);
                    }
                } else {
                    // No fresh audio: let the hold window expire naturally.
                    const bool held = queue->lastVoiceNs != 0 &&
                        steadyNowNs - queue->lastVoiceNs < kSpeakHoldNs;
                    if (!held) {
                        queue->speaking = false;
                    } else if (queue->speaking) {
                        speakers.push_back(queue->participantIdentity);
                    }
                }
            }

            activeParticipants += 1;
            // Per-participant gain (0.0 = individually muted). The frames were
            // already popped above so queue-depth/starvation bookkeeping is
            // untouched by muting; a muted participant simply contributes
            // silence to the sum. The gain is snapshotted INSIDE the queue lock
            // because setParticipantGainEverywhere writes it from the UI thread
            // while holding the same mutex.
            float participantGain = 1.0f;
            {
                std::lock_guard<std::mutex> queueLock(queue->queueMutex);
                participantGain = queue->gain;
            }
            for (int i = 0; i < kSamplesPerChannel; ++i) {
                accumulator[static_cast<std::size_t>(i)] += static_cast<std::int32_t>(
                    std::lround(static_cast<double>(frame[static_cast<std::size_t>(i)]) * participantGain));
            }
        }

        // Emit speaking-set changes, throttled, only on membership change.
        {
            std::sort(speakers.begin(), speakers.end());
            bool shouldEmit = false;
            // Read the epoch from the atomic mirror, NOT from g_sessionMutex.
            //
            // This is the exact case the comment on g_rawEpochForCallbacks
            // (line ~251) warns about, and it was doing it anyway. That comment
            // exists because remote-reader helpers run on threads which teardown
            // JOINS while holding g_sessionMutex -- so a helper that takes the
            // mutex can block, and the teardown that is waiting for it can never
            // complete. mixOneFrame runs on the mixer thread, which
            // NativeAudioMixer::stop() joins from under that same mutex, so this
            // was a live deadlock risk on hangup, not merely a 200-acquisitions-
            // per-second contention cost.
            //
            // It also fired unconditionally, even when the speaker set was empty
            // and no event was going to be emitted.
            const jlong epoch = g_rawEpochForCallbacks.load(std::memory_order_acquire);
            const auto nowNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
            {
                std::lock_guard<std::mutex> emitLock(g_speakersEmitMutex);
                if (speakers != g_lastSpeakers && nowNs - g_lastSpeakerEmitNs >= kSpeakerEmitMinIntervalNs) {
                    g_lastSpeakers = speakers;
                    g_lastSpeakerEmitNs = nowNs;
                    shouldEmit = true;
                }
            }
            if (shouldEmit) {
                JNIEnv* env = nullptr;
                bool attached = false;
                if (getJniEnv(&env, &attached, "active-speakers") && env != nullptr) {
                    dispatchActiveSpeakersChanged(env, kRawSessionId, epoch, speakers);
                    releaseJniEnv(attached);
                }
            }
        }

        if (activeParticipants <= 0) {
            return;
        }

        // Full-loudness downmix: every participant contributes at unity gain. Sums that would
        // clip run through a soft-knee limiter instead of the old sqrt(N) attenuation, which
        // made each added speaker ~3 dB quieter even when nobody was actually overlapping.
        constexpr double kLimiterKnee = 24576.0; // -2.5 dBFS
        constexpr double kLimiterCeiling = 32767.0;
        auto limitSample = [kLimiterKnee, kLimiterCeiling](std::int32_t value) -> std::int16_t {
            const double x = static_cast<double>(value);
            if (x < kLimiterKnee && x > -kLimiterKnee) {
                return static_cast<std::int16_t>(x);
            }
            const double sign = x < 0 ? -1.0 : 1.0;
            const double over = std::abs(x) - kLimiterKnee;
            const double limited = kLimiterKnee +
                (kLimiterCeiling - kLimiterKnee) * std::tanh(over / (kLimiterCeiling - kLimiterKnee));
            return static_cast<std::int16_t>(sign * limited);
        };

        std::vector<std::int16_t> mixed(kSamplesPerChannel, 0);
        for (int i = 0; i < kSamplesPerChannel; ++i) {
            mixed[static_cast<std::size_t>(i)] = limitSample(accumulator[static_cast<std::size_t>(i)]);
        }

        if (!mixedCallbackStartedLogged_) {
            mixedCallbackStartedLogged_ = true;
            std::cout << "DEBUG [OrbitLiveKitAudio] mixed audio callback started" << std::endl;
        }

        // Periodic energy metering: proves whether decoded remote audio with
        // real signal reaches the mixer and leaves it non-silent.
        mixStatFrames_ += 1;
        {
            double inAcc = 0.0;
            double outAcc = 0.0;
            for (int i = 0; i < kSamplesPerChannel; ++i) {
                const double in = static_cast<double>(accumulator[static_cast<std::size_t>(i)]);
                const double out = static_cast<double>(mixed[static_cast<std::size_t>(i)]);
                inAcc += in * in;
                outAcc += out * out;
            }
            mixStatInEma_ = 0.9 * mixStatInEma_ + 0.1 * std::sqrt(inAcc / kSamplesPerChannel);
            mixStatOutEma_ = 0.9 * mixStatOutEma_ + 0.1 * std::sqrt(outAcc / kSamplesPerChannel);
            if (mixStatFrames_ % 500 == 0) {
                const auto nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::system_clock::now().time_since_epoch()).count();
                std::cout << "MIXSTAT ts=" << nowMs
                          << " participants=" << activeParticipants
                          << " inRms=" << static_cast<long long>(mixStatInEma_)
                          << " outRms=" << static_cast<long long>(mixStatOutEma_)
                          << " starved=" << starvationEvents_.load(std::memory_order_relaxed)
                          << " drops=" << overflowDroppedSamples_.load(std::memory_order_relaxed)
                          // Catch-up frames, and the queue depth that resulted.
                          // `drops` climbing with `catchUp` flat means the mixer
                          // was never given the chance to catch up (the old
                          // loop); `catchUp` climbing with `drops` flat means it
                          // is working and the lateness is upstream.
                          << " catchUp=" << catchUpFrames_.load(std::memory_order_relaxed)
                          << " qDepth=" << maxQueueDepthSamples()
                          << std::endl;
            }
        }

        const auto timestampNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        dispatchMixedAudioFrame(mixed, static_cast<std::int64_t>(timestampNs));
    }

void mixLoop() {
        using clock = std::chrono::steady_clock;
        auto nextTick = clock::now();

        // An exception escaping a std::thread body calls std::terminate() and
        // kills the JVM, so the whole loop is guarded: the mixer must survive a
        // bad frame and keep draining participant queues.
        //
        // CATCH-UP, and why this loop was a guaranteed audio defect.
        //
        // It used to mix exactly one 480-sample frame per 10 ms tick, forever.
        // One frame per tick is real-time and nothing else, so the drain rate was
        // pinned to exactly the arrival rate with no way to exceed it. The mixer
        // does real work between ticks -- `mixOneFrame` ends with
        // `dispatchMixedAudioFrame`, a JNI upcall that attaches the thread and
        // crosses into the JVM -- and the OS will also simply deschedule it.
        // Every millisecond lost there was a millisecond of backlog that could
        // never be recovered, because the loop could not run faster than
        // real-time.
        //
        // So the consequence was not a transient. Each participant queue filled
        // to `kMaxParticipantQueueSamples` (160 ms) and STAYED there: the
        // enqueue side evicts the oldest samples to make room (`remote audio
        // queue overflow ... droppedSamples=480`), which means a permanently
        // full queue is a permanently climbing `drops` counter. Runs showed
        // `drops=4320`, then `11040`, then `19680`, still climbing at hangup --
        // which is why remote audio had been arriving as `silentConcealed` all
        // day. The buffer was saturated with evicted audio, not glitching.
        //
        // The fix is to emit as many frames as wall-clock says we are behind, so
        // the backlog is actually worked off. The playback sink consumes at
        // real-time either way; handing it two frames after a 20 ms stall just
        // gives its buffer two frames early and removes 20 ms of latency, which
        // is what the sink is built to absorb.
        //
        // Capped at `kMaxCatchUpFramesPerTick` so a long stall (laptop suspend, a
        // multi-second GC) cannot become a burst dumping seconds of stale audio
        // into the output. Whatever exceeds the cap is left to the queue's own
        // overflow policy, which drops OLD audio -- the right trade, because
        // stale audio is worse than missing audio.
        try {
            while (running_) {
                nextTick += std::chrono::milliseconds(kFrameMs);
                mixOneFrame();

                const auto now = clock::now();
                if (now <= nextTick) {
                    std::this_thread::sleep_until(nextTick);
                    continue;
                }

                // Behind schedule: emit the frames the elapsed time is worth.
                auto behindMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                    now - nextTick).count();
                int extraFrames = static_cast<int>(behindMs / kFrameMs);
                if (extraFrames > kMaxCatchUpFramesPerTick) {
                    extraFrames = kMaxCatchUpFramesPerTick;
                }
                if (extraFrames > 0) {
                    catchUpFrames_.fetch_add(extraFrames, std::memory_order_relaxed);
                    for (int i = 0; i < extraFrames; ++i) {
                        if (!running_) break;
                        mixOneFrame();
                    }
                }

                // Still behind after the cap: drop the debt rather than
                // accumulating an unbounded one.
                if (clock::now() > nextTick + std::chrono::milliseconds(kCatchUpResyncMs)) {
                    nextTick = clock::now();
                }
            }
        } catch (const std::exception& error) {
            std::cerr << "ERROR [OrbitLiveKitAudio] mixer thread threw: "
                      << error.what() << std::endl;
        } catch (...) {
            std::cerr << "ERROR [OrbitLiveKitAudio] mixer thread threw: unknown" << std::endl;
        }
        std::cout << "DEBUG [OrbitLiveKitAudio] mixer thread exited" << std::endl;
    }

    std::atomic_bool running_{false};
    std::atomic_bool mixedCallbackStartedLogged_{false};
    std::int64_t mixStatFrames_{0};
    double mixStatInEma_{0.0};
    double mixStatOutEma_{0.0};
    std::atomic<std::int64_t> starvationEvents_{0};
    std::atomic<std::int64_t> overflowDroppedSamples_{0};
    std::atomic<std::int64_t> lastOverflowWarnMs_{0};
    // Extra frames emitted to repay scheduling backlog. Reported in MIXSTAT so
    // the catch-up is observable: a run where this climbs steadily means the
    // mixer thread is being descheduled and the drops counter is the symptom.
    std::atomic<std::int64_t> catchUpFrames_{0};
    std::thread worker_;
    std::mutex mutex_;
    std::unordered_map<std::string, std::shared_ptr<ParticipantPcmQueue>> participants_;
};

NativeAudioMixer g_audioMixer;

void setParticipantGainEverywhere(const std::string& identity, float gain) {
    if (identity.empty()) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(g_participantGainsMutex);
        g_participantGains[identity] = gain;
    }
    // The direct applyParticipantGain(identity, ...) that used to sit here was
    // dead against a track-SID-keyed map and was removed. It silently did nothing,
    // and its presence was actively misleading: the fan-out 30-odd lines below
    // already walks every reader belonging to this identity and applies the gain
    // to each of their track SIDs, which is the only thing that can work when a
    // participant has more than one audio track.
    //
    // Leaving both in meant the visible line (setParticipantGainEverywhere ...,
    // logging matchedTracks) reported success while the queue actually being played
    // was never touched.

    std::vector<std::string> trackSidsToUpdate;
    {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        for (const auto& entry : g_remoteAudioReaders) {
            if (entry.second != nullptr) {
                const auto& participantId = entry.second->participantIdentity;
                // Prefix match on a DELIMITER, not a bare substring.
                //
                // This used to be five tests including two `find(...) != npos`,
                // i.e. "does this string appear anywhere inside that one". LiveKit
                // identities are `${userId}:${deviceId}`, and device ids are
                // routinely a prefix of one another -- `@alice:h:ABC123` is a
                // substring of `@alice:h:ABC1234`. So muting one device's tile
                // muted the other, and with it every other participant whose id
                // happened to contain the first one's.
                //
                // The identity pair is only matched when the strings are equal, or
                // when one ends exactly at the other's `:` separator -- which is
                // what "same user, unknown device" and "same device, user omitted"
                // both look like. Anything looser than that is a bug waiting for
                // a long device id.
                const bool sameParticipant =
                    participantId == identity ||
                    participantId.rfind(identity + ":", 0) == 0 ||
                    identity.rfind(participantId + ":", 0) == 0;
                if (sameParticipant) {
                    trackSidsToUpdate.push_back(entry.second->trackSid);
                }
            }
        }
    }
    for (const auto& trackSid : trackSidsToUpdate) {
        g_audioMixer.applyParticipantGain(trackSid, gain);
    }
    std::cout << "DEBUG [OrbitLiveKitAudio] setParticipantGainEverywhere identity="
              << identity << " gain=" << gain << " matchedTracks=" << trackSidsToUpdate.size() << std::endl;
}

void initializeSdkOnce() {
    std::call_once(g_initializeOnce, [] {
        livekit::initialize(livekit::LogLevel::Info, livekit::LogSink::kConsole);
        std::cout << "DEBUG [OrbitLiveKitAudio] LiveKit C++ SDK initialized" << std::endl;
    });
}

std::string jStringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }

    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }

    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

void logCallbackSkipOnce(const char* callbackType, const char* reason) {
    const std::string type = callbackType == nullptr ? "callback" : callbackType;
    const std::string reasonText = reason == nullptr ? "unknown" : reason;
    std::lock_guard<std::mutex> lock(g_jniAttachLogMutex);
    const std::string key = type + ":" + reasonText;
    if (g_loggedCallbackSkips.insert(key).second) {
        std::cout << "DEBUG [OrbitLiveKitAudio] " << type
                  << " callback skipped because " << reasonText << std::endl;
    }
}

void flushDeferredDispatches() {
    // No mutex: the queue is thread_local, so only this thread ever wrote it and
    // only this thread drains it.
    std::vector<std::function<void()>> pending;
    pending.swap(g_deferredDispatches);
    for (const auto& dispatch : pending) {
        try {
            dispatch();
        } catch (const std::exception& error) {
            std::cerr << "ERROR [OrbitLiveKitAudio] deferred native event dispatch threw: "
                      << error.what() << std::endl;
        } catch (...) {
            std::cerr << "ERROR [OrbitLiveKitAudio] deferred native event dispatch threw unknown error" << std::endl;
        }
    }
}

// Drop-in replacement for std::lock_guard<std::timed_mutex> on g_sessionMutex in
// every scope that can raise a JNI upcall. Releases the mutex and then drains
// the deferred upcalls, so Java code is never re-entered with the lock held.
class SessionLock {
public:
    SessionLock() : lock_(g_sessionMutex) {
        ++g_sessionLockDepth;
    }
    ~SessionLock() {
        --g_sessionLockDepth;
        lock_.unlock();
        if (g_sessionLockDepth == 0) {
            flushDeferredDispatches();
        }
    }
    SessionLock(const SessionLock&) = delete;
    SessionLock& operator=(const SessionLock&) = delete;

private:
    std::unique_lock<std::timed_mutex> lock_;
};

void deferDispatchIfSessionLockHeld(const std::function<void()>& dispatch) {
    if (g_sessionLockDepth == 0) {
        dispatch();
        return;
    }
    // thread_local queue: no lock needed, and it guarantees a queued lambda's
    // captured JNIEnv* is only ever run on the thread that created it.
    g_deferredDispatches.push_back(dispatch);
}

bool callbacksStopped(const char* callbackType) {
    if (g_sessionDestroyed.load() || g_callbackShutdown.load()) {
        logCallbackSkipOnce(callbackType, "session destroyed");
        return true;
    }
    return false;
}

bool getJniEnv(JNIEnv** env, bool* attached, const char* callbackType) {
    *env = nullptr;
    *attached = false;
    if (callbacksStopped(callbackType)) {
        return false;
    }
    if (g_javaVm == nullptr) {
        logCallbackSkipOnce(callbackType, "JavaVM missing");
        return false;
    }

    const jint getEnvResult = g_javaVm->GetEnv(reinterpret_cast<void**>(env), JNI_VERSION_1_8);
    if (getEnvResult == JNI_OK) {
        return true;
    }
    if (getEnvResult == JNI_EVERSION) {
        setRawErrorThreadSafe("NATIVE_EXCEPTION", "Unsupported JNI version for callback thread", "JNI_ATTACH", false);
        std::cerr << "ERROR [OrbitLiveKitAudio] Unsupported JNI version for callback thread" << std::endl;
        return false;
    }
    if (getEnvResult != JNI_EDETACHED) {
        setRawErrorThreadSafe("NATIVE_EXCEPTION", "JNI GetEnv failed with code " + std::to_string(getEnvResult), "JNI_ATTACH", true);
        std::cerr << "ERROR [OrbitLiveKitAudio] JNI GetEnv failed with code "
                  << getEnvResult << std::endl;
        return false;
    }

    JavaVMAttachArgs args{};
    args.version = JNI_VERSION_1_8;
    args.name = const_cast<char*>("OrbitLiveKitAudio");
    args.group = nullptr;
    const jint attachResult = g_javaVm->AttachCurrentThread(reinterpret_cast<void**>(env), &args);
    if (attachResult != JNI_OK) {
        setRawErrorThreadSafe("NATIVE_EXCEPTION", "JNI AttachCurrentThread failed with code " + std::to_string(attachResult), "JNI_ATTACH", true);
        std::cerr << "ERROR [OrbitLiveKitAudio] JNI AttachCurrentThread failed with code "
                  << attachResult << std::endl;
        return false;
    }
    *attached = true;
    return true;
}

void releaseJniEnv(bool attached) {
    if (attached && g_javaVm != nullptr) {
        g_javaVm->DetachCurrentThread();
    }
}

void logJniAttachSuccess(const char* callbackType, bool attached) {
    if (!attached || callbackType == nullptr) {
        return;
    }

    std::lock_guard<std::mutex> lock(g_jniAttachLogMutex);
    if (g_loggedJniAttachSuccess.insert(callbackType).second) {
        std::cout << "DEBUG [OrbitLiveKitAudio] JNI thread attach succeeded for "
                  << callbackType << std::endl;
    }
}

void dispatchActiveSpeakersChanged(
    JNIEnv* env,
    jlong sessionId,
    jlong epoch,
    const std::vector<std::string>& speakers) {
    if (env == nullptr) {
        return;
    }

    jclass eventClass = env->FindClass("livekit/NativeMediaEvent$ActiveSpeakersChanged");
    if (eventClass == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        return;
    }

    jmethodID ctor = env->GetMethodID(eventClass, "<init>", "(JJLjava/util/List;)V");
    jclass listClass = env->FindClass("java/util/ArrayList");
    jmethodID listCtor = listClass != nullptr ? env->GetMethodID(listClass, "<init>", "(I)V") : nullptr;
    jmethodID addMethod = listClass != nullptr ? env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z") : nullptr;
    if (ctor == nullptr || listClass == nullptr || listCtor == nullptr || addMethod == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (eventClass != nullptr) env->DeleteLocalRef(eventClass);
        if (listClass != nullptr) env->DeleteLocalRef(listClass);
        return;
    }

    jobject list = env->NewObject(listClass, listCtor, static_cast<jint>(speakers.size()));
    if (list != nullptr) {
        for (const auto& identity : speakers) {
            jstring jIdentity = env->NewStringUTF(identity.c_str());
            if (jIdentity != nullptr) {
                env->CallBooleanMethod(list, addMethod, jIdentity);
                env->DeleteLocalRef(jIdentity);
            }
        }

        jobject event = env->NewObject(eventClass, ctor, sessionId, epoch, list);
        if (event != nullptr) {
            dispatchNativeEvent(env, event);
            env->DeleteLocalRef(event);
        }
        env->DeleteLocalRef(list);
    }
    env->DeleteLocalRef(listClass);
    env->DeleteLocalRef(eventClass);
}

void dispatchMixedAudioFrame(const std::vector<std::int16_t>& samples, std::int64_t timestampNs) {
    if (samples.empty()) {
        return;
    }
    if (callbacksStopped("mixed-audio")) {
        return;
    }

    {
        std::lock_guard<std::mutex> lock(g_mixedAudioSinkMutex);
        if (g_mixedAudioSink == nullptr || g_mixedAudioSinkMethod == nullptr) {
            logCallbackSkipOnce("mixed-audio", "sink missing");
            return;
        }
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    if (!getJniEnv(&env, &attached, "mixed-audio") || env == nullptr) {
        return;
    }
    logJniAttachSuccess("mixed-audio", attached);

    jobject sink = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mixedAudioSinkMutex);
        if (g_mixedAudioSink != nullptr && g_mixedAudioSinkMethod != nullptr) {
            sink = env->NewLocalRef(g_mixedAudioSink);
            method = g_mixedAudioSinkMethod;
        } else {
            logCallbackSkipOnce("mixed-audio", "sink missing");
        }
    }

    if (sink != nullptr && method != nullptr) {
        jlong epoch = 0;
        bool shouldDispatch = false;
        {
            // DELIBERATELY STILL TAKES g_sessionMutex.
            //
            // Unlike the epoch read in mixOneFrame (now fixed to use the
            // g_rawEpochForCallbacks mirror), this one also needs `g_connected`
            // and `g_rawDisconnecting`, and neither has an atomic mirror. Making
            // them readable here would mean mirroring two more pieces of state at
            // every one of their ~10 write sites, and a single missed writer is a
            // silent data race -- a worse outcome than the contention this costs.
            //
            // This is 100 calls/sec and holds the mutex for a bool read and a
            // long copy, so it is contention, not a deadlock: unlike the mixer
            // loop, this dispatch is not inside a join-under-mutex teardown path.
            // Revisit with the other sites if the render thread is ever moved.
            std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
            epoch = g_rawEpoch;
            shouldDispatch = g_connected && !g_rawDisconnecting;
        }

        if (shouldDispatch) {
            const auto byteLength = static_cast<jsize>(samples.size() * sizeof(std::int16_t));
            jbyteArray pcm = env->NewByteArray(byteLength);
            if (pcm != nullptr) {                env->SetByteArrayRegion(
                    pcm,
                    0,
                    byteLength,
                    reinterpret_cast<const jbyte*>(samples.data()));
                env->CallVoidMethod(
                    sink,
                    method,
                    kRawSessionId,
                    epoch,
                    pcm,
                    static_cast<jint>(kSampleRate),
                    static_cast<jint>(kChannels),
                    static_cast<jint>(kSamplesPerChannel),
                    static_cast<jlong>(timestampNs));
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                }
                env->DeleteLocalRef(pcm);
            }
        }
        env->DeleteLocalRef(sink);
    }

    releaseJniEnv(attached);
}

void dispatchRemoteAudioFrame(const livekit::AudioFrame& frame) {
    if (callbacksStopped("remote-audio")) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(g_sinkMutex);
        if (g_remoteAudioSink == nullptr || g_remoteAudioSinkMethod == nullptr) {
            logCallbackSkipOnce("remote-audio", "sink missing");
            return;
        }
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    if (!getJniEnv(&env, &attached, "remote-audio") || env == nullptr) {
        return;
    }
    logJniAttachSuccess("remote-audio", attached);

    jobject sink = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_sinkMutex);
        if (g_remoteAudioSink != nullptr && g_remoteAudioSinkMethod != nullptr) {
            sink = env->NewLocalRef(g_remoteAudioSink);
            method = g_remoteAudioSinkMethod;
        } else {
            logCallbackSkipOnce("remote-audio", "sink missing");
        }
    }

    if (sink != nullptr && method != nullptr) {
        const auto& samples = frame.data();
        const auto byteLength = static_cast<jsize>(samples.size() * sizeof(std::int16_t));
        jbyteArray pcm = env->NewByteArray(byteLength);
        if (pcm != nullptr) {
            env->SetByteArrayRegion(
                pcm,
                0,
                byteLength,
                reinterpret_cast<const jbyte*>(samples.data()));
            env->CallVoidMethod(
                sink,
                method,
                pcm,
                static_cast<jint>(frame.sampleRate()),
                static_cast<jint>(frame.numChannels()),
                static_cast<jint>(frame.samplesPerChannel()));
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            env->DeleteLocalRef(pcm);
        }
        env->DeleteLocalRef(sink);
    }

    releaseJniEnv(attached);
}

void stopReader(const std::shared_ptr<RemoteAudioReader>& reader) {
    if (reader == nullptr) {
        return;
    }

    reader->running = false;
    if (reader->stream != nullptr) {
        reader->stream->close();
    }
    if (reader->thread.joinable()) {
        if (reader->thread.get_id() == std::this_thread::get_id()) {
            reader->thread.detach();
        } else {
            reader->thread.join();
        }
    }
}

// Joining a reader while holding the session mutex is the deadlock behind
// "the app is stuck" on rejoin. Teardown called join() under the lock; the reader
// being joined needs that same lock in its exit path, so neither side could make
// progress, and the next call touching the mutex -- the rejoin's configureE2EE --
// blocked forever with no diagnostic. The log showed exactly that: the join printed
// its last pre-connect line and then nothing for the rest of the call.
//
// Signalling is what actually unblocks a reader (running=false plus closing the
// stream is what makes read() return), so signal under the lock and join after
// releasing it.
//
// Readers removed from the map but not yet joined. Populated under
// g_sessionMutex, drained by the reaper thread below with no lock held.
std::vector<std::shared_ptr<RemoteAudioReader>> g_pendingReaderJoins;
// Defined further down; the reaper below drains both queues and must be able to
// call it.
void stopVideoReader(const std::shared_ptr<RemoteVideoReader>& reader, const std::string& reason);
// Defined further down; see the KDoc there for why the join cannot happen inline.
void joinTxStatsThread();
// Video readers pending the same treatment, with their stop reason carried along
// because stopVideoReader publishes a track-removed notification to Java and
// that must not run with g_sessionMutex held.
std::vector<std::pair<std::shared_ptr<RemoteVideoReader>, std::string>> g_pendingVideoReaderJoins;
std::atomic<bool> g_readerReaperRunning{false};
// Set when a TXSTAT thread has been signalled but not yet joined, because the
// callers hold g_sessionMutex. Drained by the reaper.
std::atomic<bool> g_txStatsJoinPending{false};
std::mutex g_txStatsRetiredMutex;
// Holds the handle once the thread has been signalled. Kept separate from
// g_txStatsThread so a caller can stop the old thread and start a new one
// immediately, which it must be able to do -- see retireTxStatsThread().
std::thread g_txStatsRetiredThread;

// Joins readers that were already signalled and removed from the map.
//
// This exists because stopAllRemoteReadersLocked used to join() inline, while its
// caller held g_sessionMutex. That is a deadlock rather than a slow path: the
// reader being joined takes the same mutex in its exit path, so teardown waits
// for a thread that is waiting for the lock teardown is holding, and the next
// caller that touches the mutex -- a rejoin's configureE2EE -- waits forever.
//
// Removing the entry BEFORE joining is what makes this safe, and it is the
// invariant the reader's own exit path already relies on: it detaches its thread
// only when its map erase succeeds, reasoning that a successful erase proves the
// map held the last other owner. Here the erase fails by construction, so the
// reader never detaches and this join is well-defined.
//
// A shared_ptr per reader keeps each alive across the wait, so nothing is
// destroyed while its own thread is still running.
void drainPendingReaderJoins() {
    std::vector<std::shared_ptr<RemoteAudioReader>> readers;
    std::vector<std::pair<std::shared_ptr<RemoteVideoReader>, std::string>> videoReaders;
    // Joined FIRST, and with no lock held -- see joinTxStatsThread(). The TXSTAT
    // thread blocks on g_sessionMutex at the top of every iteration, so it is the
    // one reader most likely to be mid-iteration when teardown starts.
    joinTxStatsThread();
    {
        SessionLock lock;
        readers.swap(g_pendingReaderJoins);
        videoReaders.swap(g_pendingVideoReaderJoins);
    }
    for (const auto& reader : readers) {
        if (reader != nullptr) {
            g_audioMixer.removeParticipant(reader->trackSid);
        }
        stopReader(reader);
    }
    // Joined/detached here, with no lock held.
    for (const auto& entry : videoReaders) {
        stopVideoReader(entry.first, entry.second);
    }
}

void ensureReaderReaper() {
    bool expected = false;
    if (!g_readerReaperRunning.compare_exchange_strong(expected, true)) {
        return;
    }
    // Detached, so JNI_OnUnload cannot join it; it exits on the flag below within
    // one 50ms sleep instead. Detaching is deliberate and older than the unload
    // handling: the reaper's whole job is to make joins safe from threads that
    // hold g_sessionMutex, and making it joinable would reintroduce exactly that
    // requirement at process exit.
    std::thread([] {
        try {
            while (true) {
                // Checked FIRST, before draining. JNI_OnUnload clears this to stop
                // the reaper before the VM tears down; without the check the reaper
                // ignored the request and kept running, which is one of the threads
                // that reaches a destroying VM in run 83's 0xC0000409.
                if (!g_readerReaperRunning.load(std::memory_order_acquire)) {
                    return;
                }
                drainPendingReaderJoins();
                if (g_pendingReaderJoins.empty() && g_pendingVideoReaderJoins.empty() &&
                    !g_txStatsJoinPending.load(std::memory_order_acquire)) {
                    // Nothing left to do. Sleep briefly rather than spinning:
                    // this thread exists only for the rare teardown path.
                    std::this_thread::sleep_for(std::chrono::milliseconds(50));
                    {
                        SessionLock lock;
                        if (g_pendingReaderJoins.empty() && g_pendingVideoReaderJoins.empty() &&
                            !g_txStatsJoinPending.load(std::memory_order_acquire)) {
                            g_readerReaperRunning.store(false, std::memory_order_release);
                            return;
                        }
                    }
                }
            }
        } catch (const std::exception& error) {
            std::cerr << "ERROR [OrbitLiveKitAudio] reader reaper threw: "
                      << error.what() << std::endl;
            g_readerReaperRunning.store(false, std::memory_order_release);
        }
    }).detach();
}

// Signal every remote reader and hand it to the reaper. Call with
// g_sessionMutex held; the JOINING happens later with no lock held.
void stopAllRemoteReadersLocked() {
    for (const auto& entry : g_remoteAudioReaders) {
        if (entry.second == nullptr) {
            continue;
        }
        // Signalling is what actually unblocks the thread: running=false plus
        // closing the stream is what makes read() return.
        entry.second->running = false;
        if (entry.second->stream != nullptr) {
            entry.second->stream->close();
        }
        g_pendingReaderJoins.push_back(entry.second);
    }
    g_remoteAudioReaders.clear();
    ensureReaderReaper();
}

// H6 recovery: rebuild one remote audio receiver by toggling its publication
// subscription. Runs on a detached worker because setSubscribed can emit
// Track(Un)Subscribed events synchronously into the room delegate, and those
// handlers take g_sessionMutex — this worker must never hold that mutex
// across an SDK call. The unsubscribe path stops the old reader and the
// resubscribe path starts a fresh one through the EXISTING delegate plumbing,
// so no new reader lifecycle code is involved.
void requestAudioTrackResubscribe(const std::string& participantIdentity, const std::string& trackSid, long long jbTargetMs) {
    std::thread([participantIdentity, trackSid, jbTargetMs]() {
        const auto steadyMs = [] {
            return std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
        };
        try {
            std::shared_ptr<livekit::RemoteTrackPublication> publication;
            for (int attempt = 0; attempt < 20 && publication == nullptr; ++attempt) {
                if (callbacksStopped("jb-resub")) {
                    return;
                }
                {
                    // Snapshot the publication pointer only; release before any SDK call.
                    std::unique_lock<std::timed_mutex> lock(g_sessionMutex, std::try_to_lock);
                    if (lock.owns_lock() && g_room != nullptr && g_connected) {
                        for (const auto& participant : g_room->remoteParticipants()) {
                            if (participant == nullptr || participant->identity() != participantIdentity) {
                                continue;
                            }
                            for (const auto& entry : participant->trackPublications()) {
                                if (entry.second != nullptr && normalizedSid(entry.second->sid()) == trackSid) {
                                    publication = entry.second;
                                    break;
                                }
                            }
                            break;
                        }
                    }
                }
                if (publication == nullptr) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(100));
                }
            }
            if (publication == nullptr) {
                std::cout << "WARN [OrbitLiveKitAudio] JB-RESUB gave up: publication not found sid=" << trackSid << std::endl;
                return;
            }

            std::cout << "JB-RESUB unsubscribing audio track sid=" << trackSid
                      << " identity=" << participantIdentity
                      << " reason=jbTargetMs=" << jbTargetMs << std::endl;
            publication->setSubscribed(false);

            bool unsubscribed = false;
            for (int attempt = 0; attempt < 40; ++attempt) {
                if (callbacksStopped("jb-resub")) {
                    return;
                }
                if (!publication->subscribed()) {
                    unsubscribed = true;
                    break;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
            }
            if (!unsubscribed) {
                std::cout << "WARN [OrbitLiveKitAudio] JB-RESUB unsubscribe did not take effect sid=" << trackSid << std::endl;
                return;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(300));

            {
                std::unique_lock<std::timed_mutex> lock(g_sessionMutex, std::try_to_lock);
                const bool sessionAlive = lock.owns_lock() && g_room != nullptr && g_connected;
                if (!sessionAlive) {
                    std::cout << "WARN [OrbitLiveKitAudio] JB-RESUB aborted before resubscribe: session gone sid=" << trackSid << std::endl;
                    return;
                }
            }
            std::cout << "JB-RESUB resubscribing audio track sid=" << trackSid << std::endl;
            publication->setSubscribed(true);
            std::cout << "JB-RESUB done sid=" << trackSid
                      << " freshReceiver=true expectFreshJitterBuffer=true" << std::endl;
        } catch (const std::exception& error) {
            std::cout << "WARN [OrbitLiveKitAudio] JB-RESUB failed sid=" << trackSid
                      << ": " << error.what() << std::endl;
        } catch (...) {
            std::cout << "WARN [OrbitLiveKitAudio] JB-RESUB failed sid=" << trackSid << ": unknown error" << std::endl;
        }
    }).detach();
}

void startRemoteReaderLocked(const std::shared_ptr<livekit::Track>& track, const std::string& participantIdentity) {
    if (track == nullptr || track->kind() != livekit::TrackKind::KIND_AUDIO) {
        return;
    }

    try {
        const std::string rawTrackSid = track->sid();
        const std::string trackSid = normalizedSid(rawTrackSid);
        if (trackSid != rawTrackSid) {
            std::cout << "WARN [OrbitLiveKitAudio] remote audio track sid had trailing padding: len="
                      << rawTrackSid.size() << " -> " << trackSid.size() << std::endl;
        }
        {
            auto it = g_remoteAudioReaders.find(trackSid);
            if (it != g_remoteAudioReaders.end()) {
                const auto& existing = it->second;
                if (existing != nullptr && existing->running.load()) {
                    std::cout << "DEBUG [OrbitLiveKitAudio] Remote audio reader already active for track "
                              << trackSid << std::endl;
                    return;
                }
                // Entry left behind by a reader whose thread already exited: drop
                // it so this track can be re-subscribed instead of staying silent
                // for the rest of the call. stopReader joins the dead thread; it
                // only waits on g_sessionMutex for a bounded 500 ms, so holding
                // the session mutex here cannot deadlock.
                std::cout << "DEBUG [OrbitLiveKitAudio] replacing dead audio reader entry trackSid="
                          << trackSid << std::endl;
                stopRemoteReaderLocked(trackSid);
            }
        }

        livekit::AudioStream::Options options;
        options.capacity = 20;

        auto reader = std::make_shared<RemoteAudioReader>();
        reader->trackSid = trackSid;
        // The identity is used for REPORTING (speaking set, gain fan-out, tile
        // matching) while `trackSid` keys the queue and the reader map. Those are
        // deliberately different -- see the long note in addParticipant about
        // run 64's split voice and why two queues per participant are correct.
        //
        // It used to fall back to `track->sid()` here, which is the origin of the
        // one-person-two-names bug: a participant with two audio tracks got the
        // real identity on one reader and a `TR_...` string on the other, so the
        // mixer reported the same human twice under different keys. Fixed at both
        // ends -- the reader and the queue -- but the reader is the one that
        // feeds every identity-keyed lookup, so it is fixed here first.
        //
        // An empty identity is left empty rather than invented. The queue's
        // addParticipant logs a WARN for that case, because a nameless queue is
        // a real gap in the data rather than something to paper over with a SID
        // that no identity matcher will ever match.
        reader->participantIdentity = participantIdentity;
        // Mixer queues are keyed by TRACK SID, not participant identity: an
        // Element user sharing screen-with-audio has TWO audio tracks, and
        // both feeding one identity-keyed queue doubles its inflow (garbled
        // playback) and lets either track's teardown rip the shared queue
        // away from the other.
        g_audioMixer.addParticipant(reader->trackSid, reader->participantIdentity);
        reader->stream = livekit::AudioStream::fromTrack(track, options);
        reader->track = track;
        // Announce the subscribe-time mute state so late joiners see the badge
        // for peers who were already muted (no TrackMuted event will fire).
        // Deferred when the session lock is held so the Java sink is not
        // re-entered with g_sessionMutex still taken.
        const bool initialMuted = track->muted();
        deferDispatchIfSessionLockHeld(
            [reader, initialMuted] { dispatchRemoteAudioMuteChanged(reader->participantIdentity, initialMuted); });
        reader->thread = std::thread([reader] {
            // An exception escaping a std::thread body calls std::terminate()
            // and takes the whole JVM with it, so the read loop is guarded and
            // the self-removal below is reached on every exit path.
            try {
                livekit::AudioFrameEvent event;
                auto lastRxStat = std::chrono::steady_clock::now();
                while (reader->running && reader->stream != nullptr && reader->stream->read(event)) {
                    if (callbacksStopped("remote-audio-reader")) {
                        break;
                    }
                    const auto& samples = event.frame.data();
                    if (samples.empty()) {
                        continue;
                    }
                    reader->lastFrameSteadyMs.store(
                        std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now().time_since_epoch()).count(),
                        std::memory_order_relaxed);
                    const auto timestampNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
                        std::chrono::steady_clock::now().time_since_epoch()).count();
                    g_audioMixer.pushParticipantFrame(
                        // reader->trackSid -- the map key, and the SAME value
                        // addParticipant created this queue under. Both must agree or
                        // a second queue appears for one track and the mixer drains
                        // them independently (480 samples each per 10ms tick),
                        // splitting one voice across two buffers too thin to clear
                        // kStarvationReprimeSamples.
                        //
                        // An earlier commit put participantIdentity here while
                        // addParticipant was identity-keyed. Reverting the map to
                        // track-SID keying WITHOUT reverting this left one enqueue
                        // site passing identity into a track-SID-keyed map, which
                        // minted an unremovable second queue -- run 65 named itself:
                        //
                        //   WARN remote audio queue overflow for
                        //        @hellishsaa... trackSid=@hellishsaa...
                        //
                        // The identity appearing in the trackSid field is the proof
                        // the two disagreed.
                        reader->trackSid,
                        samples.data(),
                        event.frame.sampleRate(),
                        event.frame.samplesPerChannel(),
                        event.frame.numChannels(),
                        static_cast<std::int64_t>(timestampNs));

                    // Receiver-side loss/concealment telemetry: proves whether
                    // garble originates as RTP loss (decoder concealment /
                    // NetEQ speed-up) before it ever reaches our mixer.
                    const auto nowSteady = std::chrono::steady_clock::now();
                    if (nowSteady - lastRxStat >= std::chrono::seconds(5) && reader->track != nullptr) {
                        lastRxStat = nowSteady;
                        try {
                            auto statsFuture = reader->track->getStats();
                            if (statsFuture.wait_for(std::chrono::milliseconds(50)) == std::future_status::ready) {
                                double rttSec = -1.0;
                                double remoteFractionLost = -1.0;
                                long long jitterMs = -1;
                                long long packetsReceived = -1;
                                long long packetsLost = -1;
                                for (const auto& stat : statsFuture.get()) {
                                    const auto* inbound = std::get_if<livekit::RtcInboundRtpStats>(&stat.stats);
                                    if (inbound != nullptr) {
                                        // jitter_buffer_target_delay / jitter_buffer_delay are
                                        // CUMULATIVE sums over every emitted frame (live proof:
                                        // raw target read 2342400 ms = 39 min while the real
                                        // per-frame delay averaged 68 ms). The instantaneous
                                        // value is the per-frame average; -1 until frames flow.
                                        const double emittedCount =
                                            static_cast<double>(inbound->inbound.jitter_buffer_emitted_count);
                                        const long long jbTargetMs = emittedCount > 0.0
                                            ? static_cast<long long>(
                                                  inbound->inbound.jitter_buffer_target_delay * 1000.0 / emittedCount)
                                            : -1LL;
                                        const long long jbTargetSumMs = static_cast<long long>(
                                            inbound->inbound.jitter_buffer_target_delay * 1000.0);
                                        jitterMs = static_cast<long long>(inbound->received.jitter * 1000.0);
                                        packetsReceived = static_cast<long long>(inbound->received.packets_received);
                                        packetsLost = static_cast<long long>(inbound->received.packets_lost);
                                        std::cout << "RXSTAT sid=" << reader->trackSid
                                                  << " recv=" << inbound->received.packets_received
                                                  << " lost=" << inbound->received.packets_lost
                                                  << " jitterMs=" << static_cast<long long>(inbound->received.jitter * 1000.0)
                                                  << " jbTargetMs=" << jbTargetMs
                                                  << " jbAvgMs=" << (inbound->inbound.jitter_buffer_emitted_count > 0
                                                        ? static_cast<long long>(inbound->inbound.jitter_buffer_delay * 1000.0 /
                                                            static_cast<double>(inbound->inbound.jitter_buffer_emitted_count))
                                                        : -1LL)
                                                  // `jitter_buffer_minimum_delay` is a CUMULATIVE sum over
                                                  // every emitted frame, exactly like
                                                  // `jitter_buffer_target_delay` two lines up -- and the
                                                  // run logs prove it: `jbMinMs` was byte-identical to
                                                  // `jbTargetSumMs` on every sample and grew by
                                                  // 4,569,600 each time, reading 38,438,400 ms
                                                  // (10.7 hours) for a call with `jbTargetMs=20`.
                                                  // The jitter-buffer watchdog keys off `jbTargetMs`, so
                                                  // this was never costing audio; it was a number in
                                                  // the log that would mislead whoever read it next.
                                                  << " jbMinMs=" << (inbound->inbound.jitter_buffer_emitted_count > 0
                                                        ? static_cast<long long>(inbound->inbound.jitter_buffer_minimum_delay * 1000.0 /
                                                            static_cast<double>(inbound->inbound.jitter_buffer_emitted_count))
                                                        : -1LL)
                                                  << " jbTargetSumMs=" << jbTargetSumMs
                                                  << " concealedSamples=" << inbound->inbound.concealed_samples
                                                  << " silentConcealed=" << inbound->inbound.silent_concealed_samples
                                                  << " concealEvents=" << inbound->inbound.concealment_events
                                                  << " acceleratedSamples=" << inbound->inbound.removed_samples_for_acceleration
                                                  << " decelInsertedSamples=" << inbound->inbound.inserted_samples_for_deceleration
                                                  << " discarded=" << inbound->inbound.packets_discarded
                                                  << " retransmitted=" << inbound->inbound.retransmitted_packets_received
                                                  << std::endl;
                                        // H6 watchdog: a sustained inflated jitter-buffer target means the
                                        // receiver is playing stale audio (NetEQ peak-mode decays it over
                                        // minutes). Fire the guarded resubscribe once the streak, warmup,
                                        // cooldown and lifetime caps all pass.
                                        {
                                            const long long nowSteadyMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                                                std::chrono::steady_clock::now().time_since_epoch()).count();
                                            const long long ageMs = nowSteadyMs - std::chrono::duration_cast<std::chrono::milliseconds>(
                                                reader->createdSteady.time_since_epoch()).count();
                                            const long long sinceFrameMs = nowSteadyMs - reader->lastFrameSteadyMs.load(std::memory_order_relaxed);
                                            const bool active = sinceFrameMs <= kJitterBufferActiveWindowMs;
                                            const bool jbTargetSane =
                                                jbTargetMs >= 0 && jbTargetMs <= kJitterBufferGarbageCapMs;
                                            if (jbTargetSane && jbTargetMs > kJitterBufferRecoverTargetMs && active) {
                                                reader->jbHighStreak += 1;
                                                if (reader->jbHighStreak >= 2) {
                                                    std::cout << "JBWATCH sid=" << reader->trackSid
                                                              << " jbTargetMs=" << jbTargetMs
                                                              << " streak=" << reader->jbHighStreak << std::endl;
                                                }
                                            } else {
                                                reader->jbHighStreak = 0;
                                            }
                                            const long long lastResubMs = reader->lastResubSteadyMs.load(std::memory_order_relaxed);
                                            const long long sinceResubMs = lastResubMs < 0
                                                ? std::numeric_limits<long long>::max()
                                                : nowSteadyMs - lastResubMs;
                                            if (reader->jbHighStreak >= kJitterBufferRecoverStreak &&
                                                ageMs >= kJitterBufferWarmupMs &&
                                                active &&
                                                sinceResubMs >= kJitterBufferCooldownMs &&
                                                reader->resubCount.load(std::memory_order_relaxed) < kJitterBufferMaxResubPerReader) {
                                                reader->jbHighStreak = 0;
                                                reader->resubCount.fetch_add(1, std::memory_order_relaxed);
                                                reader->lastResubSteadyMs.store(nowSteadyMs, std::memory_order_relaxed);
                                                std::cout << "JBWATCH firing resubscribe sid=" << reader->trackSid
                                                          << " jbTargetMs=" << jbTargetMs
                                                          << " attempt=" << reader->resubCount.load(std::memory_order_relaxed) << std::endl;
                                                requestAudioTrackResubscribe(reader->participantIdentity, reader->trackSid, jbTargetMs);
                                            }
                                        }
                                        continue;
                                    }
                                    const auto* remoteInbound = std::get_if<livekit::RtcRemoteInboundRtpStats>(&stat.stats);
                                    if (remoteInbound != nullptr) {
                                        rttSec = remoteInbound->remote_inbound.round_trip_time;
                                        remoteFractionLost = remoteInbound->remote_inbound.fraction_lost;
                                    }
                                }
                                // Connection-quality bars: per-interval loss from the
                                // RTP counter deltas, plus jitter and RTT captured
                                // above. -1 = unknown (no sample yet).
                                double lossPct = -1.0;
                                if (packetsReceived >= 0 && packetsLost >= 0) {
                                    const long long prevRecv =
                                        reader->prevPacketsReceived.exchange(packetsReceived, std::memory_order_relaxed);
                                    const long long prevLost =
                                        reader->prevPacketsLost.exchange(packetsLost, std::memory_order_relaxed);
                                    const double deltaRecv = static_cast<double>(packetsReceived - prevRecv);
                                    const double deltaLost = static_cast<double>(packetsLost - prevLost);
                                    if (prevRecv >= 0 && deltaRecv >= 0.0 && deltaLost >= 0.0) {
                                        lossPct = deltaRecv + deltaLost > 0.0
                                            ? deltaLost * 100.0 / (deltaRecv + deltaLost)
                                            : 0.0;
                                    }
                                }
                                const long long rttMs =
                                    rttSec >= 0.0 ? static_cast<long long>(rttSec * 1000.0 + 0.5) : -1LL;
                                dispatchRemoteConnectionQuality(
                                    reader->participantIdentity, jitterMs, rttMs, lossPct);
                                if (rttSec >= 0.0) {
                                    std::cout << "RXRTT sid=" << reader->trackSid
                                              << " rttMs=" << rttMs
                                              << " remoteFractionLostPct="
                                              << static_cast<long long>(remoteFractionLost * 100.0)
                                              << std::endl;
                                }
                            }
                        } catch (const std::exception& e) {
                            std::cout << "DEBUG [OrbitLiveKitAudio] getStats failed track="
                                      << reader->trackSid << ": " << e.what() << std::endl;
                        }
                    }
                }
            } catch (const std::exception& error) {
                std::cerr << "ERROR [OrbitLiveKitAudio] Remote audio reader threw track="
                          << reader->trackSid << " message=" << error.what() << std::endl;
            } catch (...) {
                std::cerr << "ERROR [OrbitLiveKitAudio] Remote audio reader threw track="
                          << reader->trackSid << " message=unknown" << std::endl;
            }
            // Clear the liveness flag FIRST and unconditionally. Every
            // recovery path keys on it -- startRemoteReaderLocked's
            // "replace a dead entry" branch and nativeRescanRemoteVideoTracks'
            // hasLiveReader check -- so leaving it true after we exit makes this
            // track permanently un-restartable, which is the exact bug the
            // self-erase below exists to cure.
            reader->running.store(false);
            {
                // try_to_lock, NOT a bounded timed wait.
                //
                // stopRemoteReaderLocked / stopRemoteReadersForParticipantLocked
                // erase us from the map and then join this thread WHILE holding
                // g_sessionMutex. So whenever we are exiting during a teardown,
                // the mutex is held by the thread that is blocked waiting on US.
                // Waiting for it is waiting for ourselves: the old 50 ms timeout
                // could not succeed in that case, it could only add 50 ms to
                // every hangup.
                //
                // A single non-blocking attempt is the whole of it. Losing the
                // race is recoverable because `running` is already false, and
                // both recovery paths key on exactly that -- the rescan's
                // "replace a dead entry" branch and its hasLiveReader check.
                std::unique_lock<std::timed_mutex> lock(g_sessionMutex, std::try_to_lock);
                if (lock.owns_lock()) {
                    auto it = g_remoteAudioReaders.find(reader->trackSid);
                    if (it != g_remoteAudioReaders.end() && it->second == reader) {
                        g_audioMixer.removeParticipant(reader->trackSid);
                        g_remoteAudioReaders.erase(it);
                        // A successful erase proves the map held our only other
                        // owner (every teardown path removes the entry before it
                        // joins), so nothing can join this thread any more.
                        // Detach, otherwise ~RemoteAudioReader runs on this very
                        // thread with a still-joinable std::thread and calls
                        // std::terminate().
                        if (reader->thread.joinable()) {
                            reader->thread.detach();
                        }
                        std::cout << "DEBUG [OrbitLiveKitAudio] Remote audio reader exited for track "
                                  << reader->trackSid << std::endl;
                    }
                } else {
                    // `g_sessionMutex` is held by whoever is tearing the session
                    // down, and that holder is blocked joining this very thread.
                    // We cannot take the lock, and we must not wait for it: this
                    // IS the thread the joiner is waiting on, so any further
                    // timeout here is paid directly as hangup latency.
                    //
                    // The old 50 ms was the wrong trade. It bought nothing -- the
                    // erase cannot succeed while teardown holds the mutex, which
                    // is precisely when this branch runs -- and it delayed every
                    // hangup by 50 ms to reach a conclusion it had already made.
                    //
                    // So: try once, and if it is busy, hand the work to the teardown
                    // thread that IS holding the lock. `running` is already false,
                    // which is what the rescan and the "replace a dead entry"
                    // branch both key on, so the entry is reachable and
                    // replaceable without us erasing it.
                    std::cerr << "WARN [OrbitLiveKitAudio] remote audio reader self-cleanup deferred to teardown/rescan "
                              << "(g_sessionMutex held by joiner) track=" << reader->trackSid << std::endl;
                }
            }
            std::cout << "DEBUG [OrbitLiveKitAudio] reader thread exited track="
                      << reader->trackSid << std::endl;
        });
        g_remoteAudioReaders[trackSid] = reader;
        std::cout << "DEBUG [OrbitLiveKitAudio] Started remote audio stream for track "
                  << reader->trackSid << std::endl;
    } catch (const std::exception& error) {
        std::cerr << "ERROR [OrbitLiveKitAudio] Failed to start remote audio stream: "
                  << error.what() << std::endl;
    }
}

void stopRemoteReaderLocked(const std::string& rawTrackSid) {
    const std::string trackSid = normalizedSid(rawTrackSid);
    auto it = g_remoteAudioReaders.find(trackSid);
    if (it == g_remoteAudioReaders.end()) {
        return;
    }

    auto reader = it->second;
    g_remoteAudioReaders.erase(it);
    if (reader != nullptr) {
        g_audioMixer.removeParticipant(reader->trackSid);
        stopReader(reader);
    }
}

void stopRemoteReadersForParticipantLocked(const std::string& participantIdentity) {
    if (participantIdentity.empty()) {
        return;
    }

    std::vector<std::shared_ptr<RemoteAudioReader>> readers;
    for (auto it = g_remoteAudioReaders.begin(); it != g_remoteAudioReaders.end();) {
        const auto& reader = it->second;
        if (reader != nullptr && reader->participantIdentity == participantIdentity) {
            readers.push_back(reader);
            it = g_remoteAudioReaders.erase(it);
        } else {
            ++it;
        }
    }

    for (const auto& reader : readers) {
        g_audioMixer.removeParticipant(reader->trackSid);
        stopReader(reader);
    }

    // g_participantMediaKeys is deliberately NOT erased here. The previous
    // comment claimed "a rejoining peer is re-sent its key by the distributor",
    // but that is exactly the assumption H8-V3 disproves: a peer whose
    // DeliveredKeyIndex already recorded the delivery will NOT resend, and
    // OutdatedKeyFilter drops a same-index resend. So erasing on every
    // onParticipantDisconnected -- and that fires on transient roster flickers
    // the doc names explicitly -- meant the NEXT connectRoom built a fresh
    // E2EEManager from the cache alone, found the identity absent, and nobody
    // would ever fill it in again: permanent two-way deafness for the rest of the
    // call, surviving a token-refresh reconnect.
    //
    // The map is bounded by distinct identities within one call, and is cleared
    // in nativeDestroySession and nativeDisconnect.

    // g_participantGains is deliberately NOT erased either: a local volume/mute
    // the user set is meant to survive that participant dropping and rejoining
    // mid-call, and the track SIDs are session-scoped anyway so the map stays
    // bounded by distinct identities in a single call. Both maps are cleared on
    // disconnect/destroy.
}

std::string trackSourceLabel(livekit::TrackSource source);

// ---------------------------------------------------------------------------
// TXSTAT -- the sender's half of the story.
//
// RXSTAT has existed all along and is what exposed the silentConcealed problem:
// it proved packets were ARRIVING and the jitter buffer was filling them with
// silence. That is a receive-side statement, though, and it says nothing about
// whether the sender ever produced audio worth receiving.
//
// So every "he can't hear me" in this project has been debugged from the wrong
// side of the wire. The sender's own logs showed frames captured, submitted and
// accepted -- all true, all pre-encoder -- and NOT ONE of those counters proves
// anything left the machine. There is no outbound stat, no bytesSent, no
// packetsSent anywhere in the codebase:
//
//     bytesSent    0 occurrences
//     packetsSent  0 occurrences
//     outbound     0 occurrences
//
// That gap is why an Orbit peer's audio could be unfalsifiable from their side:
// their log would look identical whether they published perfect audio or none at
// all. This closes it. Whatever a peer's sender-side numbers say, they can send
// them, and the question stops being a guess.
//
// The chain that has to be proven end to end, and where each link shows up:
//   capture   peak/rms   -- is the mic hearing anything
//   frames    this line  -- did the encoder actually emit RTP
//   decrypt   RXSTAT     -- did the far end turn it back into audio
// ---------------------------------------------------------------------------

std::atomic<bool> g_txStatsRunning{false};
std::thread g_txStatsThread;

const char* qualityLimitationLabel(livekit::QualityLimitationReason reason) {
    switch (reason) {
    case livekit::QualityLimitationReason::None:
        return "none";
    case livekit::QualityLimitationReason::Cpu:
        return "cpu";
    case livekit::QualityLimitationReason::Bandwidth:
        return "bandwidth";
    case livekit::QualityLimitationReason::Other:
        return "other";
    }
    return "unknown";
}

void resolveStreamDiagCallback(JNIEnv* env) {
    if (env == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(g_streamDiagMutex);
        if (g_streamDiagClass != nullptr && g_streamDiagOnLineMethod != nullptr) return;
    }
    jclass localClass = env->FindClass("livekit/NativeStreamDiag");
    if (localClass == nullptr) {
        env->ExceptionClear();
        return;
    }
    jmethodID method = env->GetStaticMethodID(localClass, "onNativeLine", "(Ljava/lang/String;)V");
    if (method == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(localClass);
        return;
    }
    jclass globalClass = static_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);
    if (globalClass == nullptr) return;
    bool installed = false;
    {
        std::lock_guard<std::mutex> lock(g_streamDiagMutex);
        if (g_streamDiagClass == nullptr) {
            g_streamDiagClass = globalClass;
            g_streamDiagOnLineMethod = method;
            installed = true;
        }
    }
    if (!installed) {
        env->DeleteGlobalRef(globalClass);
    }
}

void emitNativeStatLine(const std::string& line) {
    std::cout << line << std::endl;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (!getJniEnv(&env, &attached, "stream-diag") || env == nullptr) {
        return;
    }
    resolveStreamDiagCallback(env);
    jclass diagClass = nullptr;
    jmethodID onLineMethod = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_streamDiagMutex);
        diagClass = g_streamDiagClass;
        onLineMethod = g_streamDiagOnLineMethod;
    }
    if (diagClass != nullptr && onLineMethod != nullptr) {
        jstring payload = env->NewStringUTF(line.c_str());
        if (payload != nullptr) {
            env->CallStaticVoidMethod(diagClass, onLineMethod, payload);
            if (env->ExceptionCheck() == JNI_TRUE) {
                env->ExceptionClear();
            }
            env->DeleteLocalRef(payload);
        }
    }
    releaseJniEnv(attached);
}

// SHARESTAT -- the screen share's half of the same question TXSTAT answers for
// audio: are frames actually leaving the machine, and if not, why not?
//
// The Java side can prove capture and submission (`STREAMDIAG phase=capture`
// and `phase=sink`), and CallPathStats can prove the Java track sink was called.
// Neither can see
// the encoder. This line reports the native outbound RTP stats, so a share that
// looks frozen on the far side can be classified without guessing:
//
//   framesEncoded rising + fps near target  -> encoder is producing; look at
//                                              the wire/remote decoder
//   quality=cpu                            -> the encoder cannot keep up
//   quality=bandwidth + targetBitrate low  -> congestion control throttled it
//   no-outbound / stalled                  -> nothing reached RTP at all
void logShareStatsIfDue() {
    std::shared_ptr<livekit::LocalVideoTrack> track;
    bool accepting = false;
    int fpsTarget = 0;
    {
        SessionLock lock;
        track = g_screenShareTrack;
        accepting = g_acceptingScreenShareFrames;
        fpsTarget = g_screenShareFps;
    }
    if (track == nullptr) {
        return;
    }

    try {
        auto statsFuture = track->getStats();
        if (statsFuture.wait_for(std::chrono::milliseconds(50)) != std::future_status::ready) {
            std::ostringstream out;
            out << "DEBUG [OrbitLiveKitAudio] SHARESTAT verdict=stats-not-ready"
                << " accepting=" << (accepting ? "true" : "false")
                << " fpsTarget=" << fpsTarget;
            emitNativeStatLine(out.str());
            return;
        }

        long long packetsSent = -1;
        long long bytesSent = -1;
        long long framesEncoded = -1;
        long long framesSent = -1;
        long long keyFramesEncoded = -1;
        long long headerBytesSent = -1;
        long long retransmittedPackets = -1;
        long long retransmittedBytes = -1;
        long long nackCount = -1;
        long long firCount = -1;
        long long pliCount = -1;
        double fps = -1.0;
        double targetBitrate = -1.0;
        double totalEncodeTime = -1.0;
        double totalPacketSendDelay = -1.0;
        int width = -1;
        int height = -1;
        const char* quality = "unknown";
        std::string encoder = "unknown";
        std::string scalabilityMode = "unknown";
        bool active = false;
        bool found = false;
        double availableOutgoingBitrate = -1.0;

        for (const auto& stat : statsFuture.get()) {
            if (const auto* pair = std::get_if<livekit::RtcCandidatePairStats>(&stat.stats)) {
                if (pair->candidate_pair.nominated ||
                    pair->candidate_pair.state == livekit::IceCandidatePairState::Succeeded) {
                    if (pair->candidate_pair.available_outgoing_bitrate > availableOutgoingBitrate) {
                        availableOutgoingBitrate = pair->candidate_pair.available_outgoing_bitrate;
                    }
                }
                if (pair->candidate_pair.nominated) {
                    std::ostringstream out;
                    out << "DEBUG [OrbitLiveKitAudio] SHARETRANSPORT"
                        << " state="
                        << (pair->candidate_pair.state.has_value()
                                ? static_cast<int>(*pair->candidate_pair.state)
                                : -1)
                        << " nominated=true"
                        << " availableOutgoingBitrate="
                        << pair->candidate_pair.available_outgoing_bitrate
                        << " currentRttMs=" << (pair->candidate_pair.current_round_trip_time * 1000.0)
                        << " totalRttMs=" << (pair->candidate_pair.total_round_trip_time * 1000.0)
                        << " packetsSent=" << pair->candidate_pair.packets_sent
                        << " bytesSent=" << pair->candidate_pair.bytes_sent
                        << " packetsReceived=" << pair->candidate_pair.packets_received
                        << " packetsDiscardedOnSend=" << pair->candidate_pair.packets_discarded_on_send
                        << " bytesDiscardedOnSend=" << pair->candidate_pair.bytes_discarded_on_send;
                    emitNativeStatLine(out.str());
                }
            }

            if (const auto* remote = std::get_if<livekit::RtcRemoteInboundRtpStats>(&stat.stats)) {
                if (remote->stream.kind == "video") {
                    std::ostringstream out;
                    out << "DEBUG [OrbitLiveKitAudio] SHAREREMOTE"
                        << " localId=" << remote->remote_inbound.local_id
                        << " fractionLost=" << remote->remote_inbound.fraction_lost
                        << " rttMs=" << (remote->remote_inbound.round_trip_time * 1000.0)
                        << " totalRttMs=" << (remote->remote_inbound.total_round_trip_time * 1000.0)
                        << " rttSamples=" << remote->remote_inbound.round_trip_time_measurements;
                    emitNativeStatLine(out.str());
                }
            }

            const auto* outbound = std::get_if<livekit::RtcOutboundRtpStats>(&stat.stats);
            if (outbound == nullptr) {
                continue;
            }
            if (outbound->stream.kind == "video") {
                std::ostringstream out;
                out << "DEBUG [OrbitLiveKitAudio] SHARELAYER"
                    << " ssrc=" << outbound->stream.ssrc
                    << " mid=" << outbound->outbound.mid
                    << " active=" << (outbound->outbound.active ? "true" : "false")
                    << " fps=" << outbound->outbound.frames_per_second
                    << " framesEncoded=" << outbound->outbound.frames_encoded
                    << " framesSent=" << outbound->outbound.frames_sent
                    << " keyFramesEncoded=" << outbound->outbound.key_frames_encoded
                    << " targetBitrate=" << outbound->outbound.target_bitrate
                    << " width=" << outbound->outbound.frame_width
                    << " height=" << outbound->outbound.frame_height
                    << " quality=" << qualityLimitationLabel(outbound->outbound.quality_limitation_reason)
                    << " encoder=" << outbound->outbound.encoder_implementation
                    << " powerEfficient=" << (outbound->outbound.power_efficient_encoder ? "true" : "false")
                    << " nackCount=" << outbound->outbound.nack_count
                    << " firCount=" << outbound->outbound.fir_count
                    << " pliCount=" << outbound->outbound.pli_count
                    << " headerBytesSent=" << outbound->outbound.header_bytes_sent
                    << " retransmittedPackets=" << outbound->outbound.retransmitted_packets_sent
                    << " retransmittedBytes=" << outbound->outbound.retransmitted_bytes_sent
                    << " totalEncodeTime=" << outbound->outbound.total_encode_time
                    << " totalPacketSendDelay=" << outbound->outbound.total_packet_send_delay
                    << " bytesSent=" << outbound->sent.bytes_sent
                    << " scalabilityMode=" << outbound->outbound.scalability_mode;
                emitNativeStatLine(out.str());
            }
            // An outbound video sender can expose RTX/FEC streams alongside the
            // primary one. Keep the stream that has actually carried the most
            // bytes; the small retransmit streams would otherwise overwrite the
            // numbers that matter.
            if (found && static_cast<long long>(outbound->sent.bytes_sent) < bytesSent) {
                continue;
            }
            found = true;
            packetsSent = static_cast<long long>(outbound->sent.packets_sent);
            bytesSent = static_cast<long long>(outbound->sent.bytes_sent);
            framesEncoded = static_cast<long long>(outbound->outbound.frames_encoded);
            framesSent = static_cast<long long>(outbound->outbound.frames_sent);
            keyFramesEncoded = static_cast<long long>(outbound->outbound.key_frames_encoded);
            headerBytesSent = static_cast<long long>(outbound->outbound.header_bytes_sent);
            retransmittedPackets = static_cast<long long>(outbound->outbound.retransmitted_packets_sent);
            retransmittedBytes = static_cast<long long>(outbound->outbound.retransmitted_bytes_sent);
            nackCount = static_cast<long long>(outbound->outbound.nack_count);
            firCount = static_cast<long long>(outbound->outbound.fir_count);
            pliCount = static_cast<long long>(outbound->outbound.pli_count);
            fps = outbound->outbound.frames_per_second;
            targetBitrate = outbound->outbound.target_bitrate;
            totalEncodeTime = outbound->outbound.total_encode_time;
            totalPacketSendDelay = outbound->outbound.total_packet_send_delay;
            width = static_cast<int>(outbound->outbound.frame_width);
            height = static_cast<int>(outbound->outbound.frame_height);
            quality = qualityLimitationLabel(outbound->outbound.quality_limitation_reason);
            encoder = outbound->outbound.encoder_implementation;
            scalabilityMode = outbound->outbound.scalability_mode;
            active = outbound->outbound.active;
        }

        const char* verdict = "unknown";
        if (!accepting) {
            verdict = "not-accepting";
        } else if (!found) {
            verdict = "no-outbound";
        } else if (framesEncoded <= 0) {
            verdict = "stalled";
        } else {
            verdict = "sending";
        }

        std::ostringstream out;
        out << "DEBUG [OrbitLiveKitAudio] SHARESTAT verdict=" << verdict
            << " accepting=" << (accepting ? "true" : "false")
            << " active=" << (active ? "true" : "false")
            << " fpsTarget=" << fpsTarget
            << " fps=" << fps
            << " framesEncoded=" << framesEncoded
            << " framesSent=" << framesSent
            << " keyFramesEncoded=" << keyFramesEncoded
            << " targetBitrate=" << targetBitrate
            << " availableOutgoingBitrate=" << availableOutgoingBitrate
            << " quality=" << quality
            << " width=" << width
            << " height=" << height
            << " packetsSent=" << packetsSent
            << " bytesSent=" << bytesSent
            << " headerBytesSent=" << headerBytesSent
            << " retransmittedPackets=" << retransmittedPackets
            << " retransmittedBytes=" << retransmittedBytes
            << " nackCount=" << nackCount
            << " firCount=" << firCount
            << " pliCount=" << pliCount
            << " totalEncodeTime=" << totalEncodeTime
            << " totalPacketSendDelay=" << totalPacketSendDelay
            << " encoder=" << encoder
            << " scalabilityMode=" << scalabilityMode;
        emitNativeStatLine(out.str());
    } catch (const std::exception& error) {
        std::ostringstream out;
        out << "DEBUG [OrbitLiveKitAudio] SHARESTAT verdict=stats-threw what="
            << error.what();
        emitNativeStatLine(out.str());
    } catch (...) {
        emitNativeStatLine("DEBUG [OrbitLiveKitAudio] SHARESTAT verdict=stats-threw unknown");
    }
}

void logTxStatsIfDue() {
    while (g_txStatsRunning.load(std::memory_order_relaxed)) {
        std::this_thread::sleep_for(std::chrono::seconds(5));

        logShareStatsIfDue();

        std::shared_ptr<livekit::LocalAudioTrack> track;
        bool muted = false;
        bool accepting = false;
        bool e2eeRequested = false;
        int ownKeyIndex = -1;
        std::uint32_t ownKeyFingerprint = 0;
        long long captureFrames = 0;
        {
            // g_sessionMutex is a timed_mutex, and the rest of this file takes it
            // with SessionLock. A plain lock_guard<std::mutex> does not bind to it.
            SessionLock lock;
            track = g_audioTrack;
            accepting = g_acceptingFrames;
            e2eeRequested = g_e2eeRequested.load(std::memory_order_relaxed);
            ownKeyIndex = g_ownKeyIndex;
            ownKeyFingerprint = g_ownKeyFingerprint;
            // workerFramesCaptured_ is the counter the existing `audio source stats` line
                    // already reports, so TXSTAT and that line agree by
                    // construction. captureCount_ is private and has no accessor.
                    captureFrames = g_audioSourceWorker.workerFramesCaptured();
        }
        if (track == nullptr) {
            std::cout << "DEBUG [OrbitLiveKitAudio] TXSTAT verdict=no-local-track accepting="
                      << (accepting ? "true" : "false")
                      << " captured=" << captureFrames << std::endl;
            continue;
        }
        muted = track->muted();

        try {
            auto statsFuture = track->getStats();
            if (statsFuture.wait_for(std::chrono::milliseconds(50)) != std::future_status::ready) {
                // NOT an error worth a WARN: the stats future can simply not be
                // ready inside 50ms on a busy call, and a warning here would train
                // everyone to ignore the line that matters.
                std::cout << "DEBUG [OrbitLiveKitAudio] TXSTAT verdict=stats-not-ready"
                          << " muted=" << (muted ? "true" : "false")
                          << " accepting=" << (accepting ? "true" : "false")
                          << " captured=" << captureFrames << std::endl;
                continue;
            }
            long long packetsSent = -1;
            long long bytesSent = -1;
            long long retransmitted = -1;
            for (const auto& stat : statsFuture.get()) {
                const auto* outbound = std::get_if<livekit::RtcOutboundRtpStats>(&stat.stats);
                if (outbound != nullptr) {
                    // packets_sent/bytes_sent live in SentRtpStreamStats (`.sent`),
                    // not OutboundRtpStreamStats -- the outbound struct carries
                    // frames_encoded, encoder_implementation, quality_limitation_*
                    // and the retransmit counters instead.
                    packetsSent = static_cast<long long>(outbound->sent.packets_sent);
                    bytesSent = static_cast<long long>(outbound->sent.bytes_sent);
                    retransmitted = static_cast<long long>(outbound->outbound.retransmitted_packets_sent);
                }
            }

            // The verdict is the point. Every field above can look healthy while a
            // peer hears nothing, so this names the outcome in one word instead of
            // leaving four numbers to be interpreted:
            //   muted            -- we are transmitting silence by intent
            //   not-accepting    -- frames are being captured but refused downstream
            //   no-outbound      -- track exists but no outbound-rtp entry yet
            //   sending          -- packets and bytes are actually on the wire
            //   stalled          -- was sending, packets frozen (encoder wedged)
            const char* verdict = "unknown";
            if (muted) {
                verdict = "muted";
            } else if (!accepting) {
                verdict = "not-accepting";
            } else if (packetsSent < 0) {
                verdict = "no-outbound";
            } else if (packetsSent == 0) {
                verdict = "stalled";
            } else if (e2eeRequested && ownKeyIndex < 0) {
                // Packets are on the wire and this is an encrypted room, but no
                // local encryption key is installed. Every other counter reads
                // healthy here -- capture tone, encoder RTP, rising bytesSent,
                // publicationEncryption=gcm -- and the peer hears nothing, because
                // what is leaving the machine is not decryptable. Previously this
                // state was indistinguishable from `sending`.
                verdict = "sending-unkeyed";
            } else {
                verdict = "sending";
            }

            std::cout << "DEBUG [OrbitLiveKitAudio] TXSTAT verdict=" << verdict
                      << " packetsSent=" << packetsSent
                      << " bytesSent=" << bytesSent
                      << " retransmitted=" << retransmitted
                      << " muted=" << (muted ? "true" : "false")
                      << " accepting=" << (accepting ? "true" : "false")
                      << " captured=" << captureFrames
                      << " trackSid=" << track->sid()
                      << " outKeyIndex=" << ownKeyIndex
                      << std::hex << " outKeyFp=" << ownKeyFingerprint << std::dec
                      << std::endl;
        } catch (const std::exception& error) {
            std::cout << "DEBUG [OrbitLiveKitAudio] TXSTAT verdict=stats-threw what="
                      << error.what() << std::endl;
        } catch (...) {
            std::cout << "DEBUG [OrbitLiveKitAudio] TXSTAT verdict=stats-threw unknown" << std::endl;
        }
    }
}

// Moves a finished-or-finishing TXSTAT thread out of g_txStatsThread and into the
// reaper's queue, so the join happens on a thread that is not holding
// g_sessionMutex.
//
// Why the thread has to be MOVED rather than joined in place: two callers want
// to stop the old thread and immediately start a new one, and both of them hold
// g_sessionMutex. Assigning a fresh std::thread to g_txStatsThread while the old
// one is still joinable is undefined behaviour, and joining first is a deadlock.
// So the handle is relocated, the slot is free, and the reaper joins later.
//
// std::thread's move constructor leaves the source non-joinable, which is what
// makes the swap safe to do without a lock beyond this one.
void retireTxStatsThread() {
    if (!g_txStatsThread.joinable()) {
        return;
    }
    if (g_txStatsThread.get_id() == std::this_thread::get_id()) {
        // The TXSTAT thread is stopping itself. It cannot join itself, and it is
        // about to exit, so detaching is the only correct option -- the reaper has
        // nothing to do and must not be asked to.
        g_txStatsThread.detach();
        return;
    }
    g_txStatsJoinPending.store(true, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lock(g_txStatsRetiredMutex);
        g_txStatsRetiredThread = std::move(g_txStatsThread);
    }
    ensureReaderReaper();
}

void stopTxStats() {
    g_txStatsRunning.store(false, std::memory_order_relaxed);
    // Signal and hand the join to the reaper. running=false is what makes the
    // thread leave its 5s sleep; the reaper does the join with no lock held,
    // which is the whole point.
    //
    // Both callers (resetRoomLocked and the connect path) hold g_sessionMutex and
    // the TXSTAT body opens with SessionLock, so joining here waited for a lock
    // the joining thread already held:
    //
    //     disconnect step=bridge.disconnect atMs=10   (never returns)
    //
    // That was the fourth instance of the rule this file states twice already --
    // never join a thread while holding g_sessionMutex. The audio readers, the
    // video readers and ~Room were the other three. Each fix was correct and each
    // moved the stall to the next waiter, which is why the hangup produced the
    // same symptom eleven times.
    retireTxStatsThread();
}

// Performs the deferred TXSTAT join. Called from the reaper with g_sessionMutex
// released, which is the whole point: the thread being joined needs that mutex
// to finish its current iteration.
void joinTxStatsThread() {
    if (!g_txStatsJoinPending.exchange(false, std::memory_order_acq_rel)) {
        return;
    }
    // The handle lives in g_txStatsRetiredThread, not g_txStatsThread: by the time
    // this runs the caller may already have installed a fresh TXSTAT thread, and
    // joining THAT one would block the reaper for the whole next call.
    std::thread retired;
    {
        std::lock_guard<std::mutex> lock(g_txStatsRetiredMutex);
        if (!g_txStatsRetiredThread.joinable()) {
            return;
        }
        retired = std::move(g_txStatsRetiredThread);
    }
    // Joined with g_txStatsRetiredMutex released AND g_sessionMutex never held:
    // the thread being joined needs the session mutex to finish its iteration,
    // which is the entire reason this join is deferred.
    retired.join();
}
std::string optionalTrackSourceLabel(const std::optional<livekit::TrackSource>& source);
std::string trackKindLabel(livekit::TrackKind kind);
std::string streamStateLabel(livekit::StreamState state);
std::string audioFeaturesLabel(const std::vector<livekit::AudioTrackFeature>& features);
std::string encryptionTypeLabel(livekit::EncryptionType type);

bool hasRemoteVideoSink() {
    std::lock_guard<std::mutex> lock(g_remoteVideoSinkMutex);
    return g_remoteVideoSink != nullptr &&
        g_remoteVideoTrackAddedMethod != nullptr &&
        g_remoteVideoFrameMethod != nullptr &&
        g_remoteVideoTrackRemovedMethod != nullptr;
}

void dispatchRemoteParticipantConnected(const std::string& participantIdentity, const std::string& participantSid) {
    if (participantIdentity.empty() || callbacksStopped("remote-participant-connected")) {
        return;
    }

    // Exactly-once announcement, enforced HERE rather than at any one call site.
    //
    // Six sites dispatch a participant-connected: onParticipantConnected,
    // onTrackPublished, onTrackSubscribed, the connect-time roster walk, the
    // rescan, and the rescan's track loop. Only the rescan used to consult
    // g_announcedParticipantSids, so a single peer was announced once per track
    // per source. Run 82 measured it: one peer, three tracks, EIGHT connects and
    // zero disconnects, which is what made PARTLEDGER report UNBALANCED.
    //
    // A dedup gate at the call site cannot work when there are six call sites and
    // no shared funnel -- and the rescan's own check was a fifth opinion, not the
    // rule. The invariant belongs at the dispatch.
    //
    // Check-and-reserve is done in one critical section rather than check-then-
    // insert, because the six sites run on the LiveKit FFI event thread and a
    // tick thread. Check-then-insert would let both observe "not announced" and
    // both call up. The reservation is rolled back below if the JNI upcall turns
    // out to be undeliverable, so a missing sink still gets retried by the rescan
    // rather than being silently marked announced forever.
    const std::string rawSid = normalizedSid(participantSid);
    // A participant with no sid cannot be keyed by one, so fall back to the
    // identity. Worse than a real sid as a key, but it still collapses the
    // repeated announcements instead of letting them all through.
    const std::string announceKey = rawSid.empty()
        ? std::string("identity:") + participantIdentity
        : rawSid;
    {
        std::lock_guard<std::mutex> announcedLock(g_announcedParticipantsMutex);
        if (!g_announcedParticipantSids.insert(announceKey).second) {
            // Already announced and not yet departed. Suppressed, not an error:
            // this is the ordinary case for a peer with several tracks.
            return;
        }
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    if (!getJniEnv(&env, &attached, "remote-participant-connected") || env == nullptr) {
        unreserveParticipantAnnouncement(announceKey);
        return;
    }
    logJniAttachSuccess("remote-participant-connected", attached);

    jobject sink = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_remoteVideoSinkMutex);
        if (g_remoteVideoSink != nullptr && g_remoteParticipantConnectedMethod != nullptr) {
            sink = env->NewLocalRef(g_remoteVideoSink);
            method = g_remoteParticipantConnectedMethod;
        }
    }

    if (sink != nullptr && method != nullptr) {
        jstring jIdentity = env->NewStringUTF(participantIdentity.c_str());
        jstring jSid = env->NewStringUTF(rawSid.c_str());
        env->CallVoidMethod(
            sink,
            method,
            kRawSessionId,
            g_rawEpochForCallbacks.load(std::memory_order_acquire),
            jIdentity,
            jSid);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (jIdentity != nullptr) env->DeleteLocalRef(jIdentity);
        if (jSid != nullptr) env->DeleteLocalRef(jSid);
        env->DeleteLocalRef(sink);
    } else {
        // No sink registered, so nothing was actually told. Hand the reservation
        // back, otherwise the rescan would see the participant as "already
        // announced" and never deliver it -- a participant who silently never
        // appears, which is worse than a duplicate.
        unreserveParticipantAnnouncement(announceKey);
    }

    releaseJniEnv(attached);
}

void dispatchRemoteParticipantDisconnected(const std::string& participantIdentity, const std::string& participantSid) {
    if (participantIdentity.empty() || callbacksStopped("remote-participant-disconnected")) {
        return;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    if (!getJniEnv(&env, &attached, "remote-participant-disconnected") || env == nullptr) {
        return;
    }
    logJniAttachSuccess("remote-participant-disconnected", attached);

    jobject sink = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_remoteVideoSinkMutex);
        if (g_remoteVideoSink != nullptr && g_remoteParticipantDisconnectedMethod != nullptr) {
            sink = env->NewLocalRef(g_remoteVideoSink);
            method = g_remoteParticipantDisconnectedMethod;
        }
    }

    if (sink != nullptr && method != nullptr) {
        jstring jIdentity = env->NewStringUTF(participantIdentity.c_str());
        jstring jSid = env->NewStringUTF(participantSid.c_str());
        env->CallVoidMethod(
            sink,
            method,
            kRawSessionId,
            g_rawEpochForCallbacks.load(std::memory_order_acquire),
            jIdentity,
            jSid);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (jIdentity != nullptr) env->DeleteLocalRef(jIdentity);
        if (jSid != nullptr) env->DeleteLocalRef(jSid);
        env->DeleteLocalRef(sink);
        {
            // Same key derivation as the connect path, so the identity fallback
            // for a sid-less participant is actually cleared on departure. Using
            // normalizedSid() alone here would leave "identity:..." entries
            // stranded forever, and a stranded key suppresses every later
            // announcement for that peer -- a rejoin would never be announced.
            std::lock_guard<std::mutex> announcedLock(g_announcedParticipantsMutex);
            const std::string rawSid = normalizedSid(participantSid);
            g_announcedParticipantSids.erase(
                rawSid.empty() ? std::string("identity:") + participantIdentity : rawSid);
        }
    }

    releaseJniEnv(attached);
}

void dispatchRemoteAudioMuteChanged(const std::string& participantIdentity, bool muted) {
    if (participantIdentity.empty() || callbacksStopped("remote-audio-mute-changed")) {
        return;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    if (!getJniEnv(&env, &attached, "remote-audio-mute-changed") || env == nullptr) {
        return;
    }
    logJniAttachSuccess("remote-audio-mute-changed", attached);

    jobject sink = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_remoteVideoSinkMutex);
        if (g_remoteVideoSink != nullptr && g_remoteAudioMuteChangedMethod != nullptr) {
            sink = env->NewLocalRef(g_remoteVideoSink);
            method = g_remoteAudioMuteChangedMethod;
        }
    }

    if (sink != nullptr && method != nullptr) {
        jstring jIdentity = env->NewStringUTF(participantIdentity.c_str());
        env->CallVoidMethod(
            sink,
            method,
            kRawSessionId,
            g_rawEpochForCallbacks.load(std::memory_order_acquire),
            jIdentity,
            muted ? JNI_TRUE : JNI_FALSE);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (jIdentity != nullptr) env->DeleteLocalRef(jIdentity);
        env->DeleteLocalRef(sink);
    }

    releaseJniEnv(attached);
}

// Per-participant connection quality (jitter/RTT/loss sampled every ~5s by
// each remote audio reader's stats pass). Drives the Discord-style signal
// bars on call tiles; -1 values mean "no sample yet".
void dispatchRemoteConnectionQuality(const std::string& participantIdentity, long long jitterMs, long long rttMs, double lossPct) {
    if (participantIdentity.empty() || callbacksStopped("remote-connection-quality")) {
        return;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    if (!getJniEnv(&env, &attached, "remote-connection-quality") || env == nullptr) {
        return;
    }
    logJniAttachSuccess("remote-connection-quality", attached);

    jobject sink = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_remoteVideoSinkMutex);
        if (g_remoteVideoSink != nullptr && g_remoteConnectionQualityMethod != nullptr) {
            sink = env->NewLocalRef(g_remoteVideoSink);
            method = g_remoteConnectionQualityMethod;
        }
    }

    if (sink != nullptr && method != nullptr) {
        jstring jIdentity = env->NewStringUTF(participantIdentity.c_str());
        env->CallVoidMethod(
            sink,
            method,
            kRawSessionId,
            g_rawEpochForCallbacks.load(std::memory_order_acquire),
            jIdentity,
            static_cast<jlong>(jitterMs),
            static_cast<jlong>(rttMs),
            static_cast<jdouble>(lossPct));
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (jIdentity != nullptr) env->DeleteLocalRef(jIdentity);
        env->DeleteLocalRef(sink);
    }

    releaseJniEnv(attached);
}

std::string publicationTrackSid(const std::shared_ptr<livekit::TrackPublication>& publication) {
    if (publication == nullptr) {
        return {};
    }
    const auto track = publication->track();
    if (track != nullptr && !track->sid().empty()) {
        return normalizedSid(track->sid());
    }
    return normalizedSid(publication->sid());
}

// Announce that a remote screen share is published, named as such, and still has
// no track after the whole retry ladder.
//
// Deliberately NOT a teardown: the track does not exist, so there is no reader and
// nothing to stop. The point is to reach the UI, which is the only layer that can
// offer the one recovery that works -- rebuilding the SFU session, which picks the
// track up on the fresh connection.
// Defined below; announced from the rescan, which runs long before any reader
// exists for the track.
void dispatchRemoteVideoAnnounced(
    jlong sessionId,
    jlong epoch,
    const std::string& participantIdentity,
    const std::string& trackSid,
    const std::string& trackSource);

// Tell the UI a remote video publication exists, at most once per publication.
//
// Called from the rescan for EVERY video publication, with or without a track,
// because the rescan is the only thing that runs while a share is still waiting
// to attach. The set is what makes it once-per-publication: the rescan walks every
// publication on every 2 s tick.
void announceRemoteVideoOnce(
    const livekit::Participant* participant,
    const std::shared_ptr<livekit::TrackPublication>& publication,
    const std::shared_ptr<livekit::Track>& track) {
    if (participant == nullptr || publication == nullptr) {
        return;
    }
    if (publication->kind() != livekit::TrackKind::KIND_VIDEO) {
        return;
    }
    const std::string publicationSid = publication->sid();
    if (publicationSid.empty()) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(g_announcedVideoMutex);
        if (!g_announcedVideoPublications.insert(publicationSid).second) {
            return;
        }
    }
    const std::string identity = participant->identity();
    const std::string source = trackSourceLabel(publication->source());
    std::cout << "DEBUG [OrbitLiveKitAudio] announcing remote video track " << source
              << " publicationSid=" << publicationSid
              << " trackPresent=" << (track != nullptr ? "true" : "false")
              << " participantIdentity=" << identity << std::endl;
    deferDispatchIfSessionLockHeld([identity, publicationSid, source] {
        dispatchRemoteVideoAnnounced(
            kRawSessionId,
            g_rawEpochForCallbacks.load(std::memory_order_acquire),
            identity,
            publicationSid,
            source);
    });
}

// Announce a remote video publication to the UI, once per publication, whether or
// not a track object exists behind it.
//
// This exists because the tile is built from `activeTracks`, and a track only
// entered that map when its first FRAME arrived. A share that took 18 s to attach
// therefore had no tile for those 18 s -- there was nothing to hang a spinner on,
// and the tile appeared at the same moment as the picture, which made the spinner
// unreachable rather than merely unused.
void dispatchRemoteVideoAnnounced(
    jlong sessionId,
    jlong epoch,
    const std::string& participantIdentity,
    const std::string& trackSid,
    const std::string& trackSource) {
    if (callbacksStopped("remote-video-announced")) {
        return;
    }
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_remoteVideoSinkMutex);
        method = g_remoteVideoAnnouncedMethod;
    }
    if (method == nullptr) {
        logCallbackSkipOnce("remote-video-announced", "sink missing");
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    if (!getJniEnv(&env, &attached, "remote-video-announced") || env == nullptr) {
        return;
    }
    jobject sink = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_remoteVideoSinkMutex);
        if (g_remoteVideoSink != nullptr) {
            sink = env->NewLocalRef(g_remoteVideoSink);
        }
    }
    if (sink != nullptr) {
        jstring identityString = env->NewStringUTF(participantIdentity.c_str());
        jstring trackSidString = env->NewStringUTF(trackSid.c_str());
        jstring sourceString = env->NewStringUTF(trackSource.c_str());
        env->CallVoidMethod(
            sink, method, sessionId, epoch, identityString, trackSidString, sourceString);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (identityString != nullptr) env->DeleteLocalRef(identityString);
        if (trackSidString != nullptr) env->DeleteLocalRef(trackSidString);
        if (sourceString != nullptr) env->DeleteLocalRef(sourceString);
        env->DeleteLocalRef(sink);
    }
    releaseJniEnv(attached);
}

void dispatchRemoteVideoTrackStuck(
    jlong sessionId,
    jlong epoch,
    const std::string& participantIdentity,
    const std::string& trackSid) {
    if (callbacksStopped("remote-video-track-stuck")) {
        return;
    }
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_remoteVideoSinkMutex);
        method = g_remoteVideoTrackStuckMethod;
    }
    if (method == nullptr) {
        logCallbackSkipOnce("remote-video-track-stuck", "sink missing");
        return;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    if (!getJniEnv(&env, &attached, "remote-video-track-stuck") || env == nullptr) {
        return;
    }
    logJniAttachSuccess("remote-video-track-stuck", attached);

    jobject sink = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_remoteVideoSinkMutex);
        if (g_remoteVideoSink != nullptr) {
            sink = env->NewLocalRef(g_remoteVideoSink);
        }
    }
    if (sink != nullptr) {
        jstring identityString = env->NewStringUTF(participantIdentity.c_str());
        jstring trackSidString = env->NewStringUTF(trackSid.c_str());
        env->CallVoidMethod(
            sink,
            method,
            sessionId,
            epoch,
            identityString,
            trackSidString);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (identityString != nullptr) env->DeleteLocalRef(identityString);
        if (trackSidString != nullptr) env->DeleteLocalRef(trackSidString);
        env->DeleteLocalRef(sink);
    }
    releaseJniEnv(attached);
}

void dispatchRemoteVideoTrackAdded(const std::shared_ptr<RemoteVideoReader>& reader) {
    if (reader == nullptr || callbacksStopped("remote-video-track-added")) {
        return;
    }
    if (!hasRemoteVideoSink()) {
        logCallbackSkipOnce("remote-video-track-added", "sink missing");
        return;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    if (!getJniEnv(&env, &attached, "remote-video-track-added") || env == nullptr) {
        return;
    }
    logJniAttachSuccess("remote-video-track-added", attached);

    jobject sink = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_remoteVideoSinkMutex);
        if (g_remoteVideoSink != nullptr && g_remoteVideoTrackAddedMethod != nullptr) {
            sink = env->NewLocalRef(g_remoteVideoSink);
            method = g_remoteVideoTrackAddedMethod;
        }
    }

    if (sink != nullptr && method != nullptr) {
        jstring participantIdentity = env->NewStringUTF(reader->participantIdentity.c_str());
        jstring participantSid = env->NewStringUTF(reader->participantSid.c_str());
        jstring publicationSid = env->NewStringUTF(reader->publicationSid.c_str());
        jstring trackSid = env->NewStringUTF(reader->trackSid.c_str());
        jstring trackName = env->NewStringUTF(reader->trackName.c_str());
        jstring trackSource = env->NewStringUTF(reader->trackSource.c_str());
        env->CallVoidMethod(
            sink,
            method,
            reader->sessionId,
            reader->epoch,
            participantIdentity,
            participantSid,
            publicationSid,
            trackSid,
            trackName,
            trackSource,
            reader->muted ? JNI_TRUE : JNI_FALSE);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (participantIdentity != nullptr) env->DeleteLocalRef(participantIdentity);
        if (participantSid != nullptr) env->DeleteLocalRef(participantSid);
        if (publicationSid != nullptr) env->DeleteLocalRef(publicationSid);
        if (trackSid != nullptr) env->DeleteLocalRef(trackSid);
        if (trackName != nullptr) env->DeleteLocalRef(trackName);
        if (trackSource != nullptr) env->DeleteLocalRef(trackSource);
        env->DeleteLocalRef(sink);
    }

    releaseJniEnv(attached);
}

void dispatchRemoteVideoTrackRemoved(const std::shared_ptr<RemoteVideoReader>& reader) {
    if (reader == nullptr || callbacksStopped("remote-video-track-removed")) {
        return;
    }
    if (!hasRemoteVideoSink()) {
        logCallbackSkipOnce("remote-video-track-removed", "sink missing");
        return;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    if (!getJniEnv(&env, &attached, "remote-video-track-removed") || env == nullptr) {
        return;
    }
    logJniAttachSuccess("remote-video-track-removed", attached);

    jobject sink = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_remoteVideoSinkMutex);
        if (g_remoteVideoSink != nullptr && g_remoteVideoTrackRemovedMethod != nullptr) {
            sink = env->NewLocalRef(g_remoteVideoSink);
            method = g_remoteVideoTrackRemovedMethod;
        }
    }

    if (sink != nullptr && method != nullptr) {
        jstring participantIdentity = env->NewStringUTF(reader->participantIdentity.c_str());
        jstring trackSid = env->NewStringUTF(reader->trackSid.c_str());
        env->CallVoidMethod(
            sink,
            method,
            reader->sessionId,
            reader->epoch,
            participantIdentity,
            trackSid);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (participantIdentity != nullptr) env->DeleteLocalRef(participantIdentity);
        if (trackSid != nullptr) env->DeleteLocalRef(trackSid);

        env->DeleteLocalRef(sink);
    }

    releaseJniEnv(attached);
}

void notifyRemoteVideoTrackRemoved(const std::shared_ptr<RemoteVideoReader>& reader, const std::string& reason) {
    if (reader == nullptr || reader->removedDispatched.exchange(true)) {
        return;
    }
    std::cout << "DEBUG [RemoteVideo] render stopped "
              << "reason=" << reason
              << " participantIdentity=" << reader->participantIdentity
              << " trackSid=" << reader->trackSid
              << std::endl;
    // Teardown reaches here while g_sessionMutex is held; dispatching straight
    // into Java from there can re-enter the native entry points this teardown is
    // itself calling into. Hand it to SessionLock instead.
    deferDispatchIfSessionLockHeld([reader] { dispatchRemoteVideoTrackRemoved(reader); });
}

// Box-filter RGBA downscale. Returns false (leaving `out` untouched) when the
// frame is already small enough, so the caller can forward the SFU's own buffer
// untouched and there is zero cost on the common small-frame path.
//
// Exact area averaging rather than nearest-neighbour: a shared window is mostly
// text, and point-sampling it drops half the glyph strokes into a visibly
// noisier, harder-to-read image. Averaging touches each source pixel once
// (1920x1080 -> 1280x720 is ~2.1M reads) which is comfortably under a
// millisecond, and it happens before the single JNI copy rather than after four
// allocations' worth of them.
bool downscaleRgbaBox(
    const std::uint8_t* src,
    int srcWidth,
    int srcHeight,
    int maxLongEdge,
    std::vector<std::uint8_t>& out,
    int& outWidth,
    int& outHeight) {
    if (src == nullptr || srcWidth <= 1 || srcHeight <= 1) {
        return false;
    }
    const long long longEdge = std::max(srcWidth, srcHeight);
    if (longEdge <= maxLongEdge) {
        return false;
    }
    const double scale = static_cast<double>(maxLongEdge) / static_cast<double>(longEdge);
    // floor, not round: rounding can push a dimension back up to the source size
    // on an exact ratio, and this must never be an upscale.
    outWidth = std::clamp(static_cast<int>(srcWidth * scale), 1, srcWidth - 1);
    outHeight = std::clamp(static_cast<int>(srcHeight * scale), 1, srcHeight - 1);
    if (outWidth <= 0 || outHeight <= 0) {
        return false;
    }

    out.resize(static_cast<std::size_t>(outWidth) * static_cast<std::size_t>(outHeight) * 4u);
    const std::size_t srcStride = static_cast<std::size_t>(srcWidth) * 4u;
    for (int dy = 0; dy < outHeight; ++dy) {
        // Integer source bounds per destination row, computed once per row.
        const int y0 = static_cast<int>(static_cast<long long>(dy) * srcHeight / outHeight);
        int y1 = static_cast<int>(static_cast<long long>(dy + 1) * srcHeight / outHeight);
        if (y1 <= y0) {
            y1 = y0 + 1;
        }
        if (y1 > srcHeight) {
            y1 = srcHeight;
        }
        auto* dstRow = out.data() + static_cast<std::size_t>(dy) * static_cast<std::size_t>(outWidth) * 4u;
        for (int dx = 0; dx < outWidth; ++dx) {
            const int x0 = static_cast<int>(static_cast<long long>(dx) * srcWidth / outWidth);
            int x1 = static_cast<int>(static_cast<long long>(dx + 1) * srcWidth / outWidth);
            if (x1 <= x0) {
                x1 = x0 + 1;
            }
            if (x1 > srcWidth) {
                x1 = srcWidth;
            }
            std::uint32_t sumR = 0;
            std::uint32_t sumG = 0;
            std::uint32_t sumB = 0;
            std::uint32_t sumA = 0;
            std::uint32_t count = 0;
            for (int y = y0; y < y1; ++y) {
                const auto* srcRow = src + static_cast<std::size_t>(y) * srcStride;
                for (int x = x0; x < x1; ++x) {
                    const std::size_t offset = static_cast<std::size_t>(x) * 4u;
                    sumR += srcRow[offset];
                    sumG += srcRow[offset + 1];
                    sumB += srcRow[offset + 2];
                    sumA += srcRow[offset + 3];
                    count += 1;
                }
            }
            if (count == 0) {
                count = 1;
            }
            const std::size_t dst = static_cast<std::size_t>(dx) * 4u;
            dstRow[dst] = static_cast<std::uint8_t>(sumR / count);
            dstRow[dst + 1] = static_cast<std::uint8_t>(sumG / count);
            dstRow[dst + 2] = static_cast<std::uint8_t>(sumB / count);
            dstRow[dst + 3] = static_cast<std::uint8_t>(sumA / count);
        }
    }
    return true;
}

void dispatchRemoteVideoFrame(
    const std::shared_ptr<RemoteVideoReader>& reader,
    const livekit::VideoFrameEvent& event,
    std::uint64_t sequence,
    const std::vector<std::uint8_t>* downscale = nullptr,
    int downscaleWidth = 0,
    int downscaleHeight = 0) {
    if (reader == nullptr || callbacksStopped("remote-video-frame")) {
        return;
    }
    if (!hasRemoteVideoSink()) {
        logCallbackSkipOnce("remote-video-frame", "sink missing");
        return;
    }

    const auto& frame = event.frame;
    // Normally the SFU's own buffer is forwarded verbatim. When the reader loop
    // has averaged the frame down (see downscaleRgbaBox) that replacement is used
    // instead, with the SAME buffer type, so the JNI strideY below stays correct.
    const std::uint8_t* pixels = downscale != nullptr ? downscale->data() : frame.data();
    const std::size_t byteLength = downscale != nullptr ? downscale->size() : frame.dataSize();
    const int width = downscale != nullptr ? downscaleWidth : static_cast<int>(frame.width());
    const int height = downscale != nullptr ? downscaleHeight : static_cast<int>(frame.height());
    const auto bufferType = frame.type();
    // dataSize() can be non-zero while the buffer pointer is null, which would
    // hand SetByteArrayRegion a null source and crash the VM.
    if (pixels == nullptr || width <= 0 || height <= 0 || byteLength == 0 ||
        byteLength > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    if (!getJniEnv(&env, &attached, "remote-video-frame") || env == nullptr) {
        return;
    }
    logJniAttachSuccess("remote-video-frame", attached);

    jobject sink = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_remoteVideoSinkMutex);
        if (g_remoteVideoSink != nullptr && g_remoteVideoFrameMethod != nullptr) {
            sink = env->NewLocalRef(g_remoteVideoSink);
            method = g_remoteVideoFrameMethod;
        }
    }

    if (sink != nullptr && method != nullptr) {
        const auto jByteLength = static_cast<jsize>(byteLength);
        jbyteArray bytes = env->NewByteArray(jByteLength);
        jstring participantIdentity = env->NewStringUTF(reader->participantIdentity.c_str());
        jstring trackSid = env->NewStringUTF(reader->trackSid.c_str());
        jstring trackSource = env->NewStringUTF(reader->trackSource.c_str());
        if (bytes != nullptr) {
            env->SetByteArrayRegion(
                bytes,
                0,
                jByteLength,
                reinterpret_cast<const jbyte*>(pixels));
            const auto timestampNs = event.timestamp_us > 0
                ? static_cast<jlong>(event.timestamp_us * 1000LL)
                : static_cast<jlong>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                      std::chrono::steady_clock::now().time_since_epoch()).count());
            const jint strideY = bufferType == livekit::VideoBufferType::RGBA
                ? static_cast<jint>(width * 4)
                : static_cast<jint>(0);
            env->CallVoidMethod(
                sink,
                method,
                reader->sessionId,
                reader->epoch,
                participantIdentity,
                trackSid,
                trackSource,
                bytes,
                static_cast<jint>(width),
                static_cast<jint>(height),
                static_cast<jint>(bufferType),
                strideY,
                static_cast<jint>(0),
                static_cast<jint>(0),
                timestampNs,
                static_cast<jint>(event.rotation));
        }
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (bytes != nullptr) env->DeleteLocalRef(bytes);
        if (participantIdentity != nullptr) env->DeleteLocalRef(participantIdentity);
        if (trackSid != nullptr) env->DeleteLocalRef(trackSid);
        if (trackSource != nullptr) env->DeleteLocalRef(trackSource);
        env->DeleteLocalRef(sink);
    }

    releaseJniEnv(attached);
    (void)sequence;
}

void stopVideoReader(const std::shared_ptr<RemoteVideoReader>& reader, const std::string& reason) {
    if (reader == nullptr) {
        return;
    }

    reader->running = false;
    if (reader->stream != nullptr) {
        reader->stream->close();
    }
    if (reader->thread.joinable()) {
        if (reader->thread.get_id() == std::this_thread::get_id()) {
            reader->thread.detach();
        } else {
            // JOINED, and this is the right place for it.
            //
            // This function is reached from drainPendingReaderJoins, which runs on
            // the reaper with g_sessionMutex NOT held -- the reaper takes the mutex
            // only to swap the pending queue out and releases it before calling in
            // here. So the join cannot deadlock, and it must not be skipped.
            //
            // Detaching here instead is what wedged the UI when Element stopped
            // sharing. Detach returns while the reader is still parked inside
            // VideoStream::read() holding a shared_ptr to a stream owned by the
            // Room, and the reader's own exit path (which takes g_sessionMutex)
            // never runs. The room is then destroyed underneath that thread, and
            // every later native entry point that takes g_sessionMutex -- including
            // the ones the Compose thread makes -- contends with a thread the SDK
            // will never release. The window stops repainting and stops taking
            // input, which is the "Orbit froze, I had to terminate it" report.
            //
            // read() is already unblocked by the close() above, so this returns
            // as soon as the in-flight frame finishes being delivered.
            reader->thread.join();
        }
    }
    notifyRemoteVideoTrackRemoved(reader, reason);
}

// Signal every remote VIDEO reader and hand it to the reaper. Call with
// g_sessionMutex held; the joining and the track-removed notification happen
// later with no lock held.
//
// This is the same fix as stopAllRemoteReadersLocked, and it was missed there for
// five rounds of debugging while the audio path was fixed and the video path was
// left alone. The consequence was that disconnect() never completed on a rejoin,
// because a video reader blocked on a full frame callback cannot reach the mutex
// that teardown is holding:
//
//     WARN [LiveKitNativeMediaSession] teardown still running after 5000ms
//     ERROR [CallManager] Group-call join stalled at Joined
//
// Video is the worse case: a frame callback takes far longer than a 10ms audio
// read, so a video reader is far more likely to be mid-callback at the moment
// teardown starts.
//
// notifyRemoteVideoTrackRemoved must also run without the lock -- it publishes
// into Java, and a reentrant call back into this file would deadlock on a
// non-recursive mutex. SessionLock defers such callbacks until unlock; moving the
// call to the reaper achieves the same thing without depending on it.
void stopAllRemoteVideoReadersLocked(const std::string& reason = "all") {
    for (const auto& entry : g_remoteVideoReaders) {
        if (entry.second == nullptr) {
            continue;
        }
        entry.second->running = false;
        if (entry.second->stream != nullptr) {
            entry.second->stream->close();
        }
        notifyRemoteVideoTrackRemoved(entry.second, reason);
        g_pendingVideoReaderJoins.emplace_back(entry.second, reason);
    }
    g_remoteVideoReaders.clear();
    {
        std::lock_guard<std::mutex> lock(g_announcedVideoMutex);
        g_announcedVideoPublications.clear();
    }
    ensureReaderReaper();
}

void stopRemoteVideoReaderLocked(const std::string& rawTrackSid, const std::string& reason) {
    const std::string trackSid = normalizedSid(rawTrackSid);
    if (trackSid.empty()) {
        return;
    }
    auto it = g_remoteVideoReaders.find(trackSid);
    if (it == g_remoteVideoReaders.end()) {
        // Also check if any reader matches publicationSid
        for (auto readerIt = g_remoteVideoReaders.begin(); readerIt != g_remoteVideoReaders.end(); ++readerIt) {
            if (readerIt->second != nullptr && normalizedSid(readerIt->second->publicationSid) == trackSid) {
                it = readerIt;
                break;
            }
        }
        if (it == g_remoteVideoReaders.end()) {
            return;
        }
    }

    auto reader = it->second;
    g_remoteVideoReaders.erase(it);
    {
        std::lock_guard<std::mutex> lock(g_announcedVideoMutex);
        g_announcedVideoPublications.erase(reader->publicationSid);
        g_announcedVideoPublications.erase(reader->trackSid);
    }
    // Same reasoning as stopAllRemoteVideoReadersLocked: the entry is already
    // out of the map, so the reader cannot self-detach, and joining it here
    // while the caller holds g_sessionMutex would wait on a thread that needs
    // this mutex. Signal and hand it to the reaper instead.
    reader->running = false;
    if (reader->stream != nullptr) {
        reader->stream->close();
    }
    // Publish track removal immediately into Java so the UI tile is cleaned up right away.
    notifyRemoteVideoTrackRemoved(reader, reason);
    g_pendingVideoReaderJoins.emplace_back(reader, reason);
    ensureReaderReaper();
}

void stopRemoteVideoReaderForPublicationLocked(
    const std::shared_ptr<livekit::TrackPublication>& publication,
    const std::string& reason) {
    if (publication == nullptr) {
        return;
    }
    const auto trackSid = publicationTrackSid(publication);
    stopRemoteVideoReaderLocked(trackSid, reason);
    const auto publicationSid = normalizedSid(publication->sid());
    if (trackSid != publicationSid) {
        stopRemoteVideoReaderLocked(publicationSid, reason);
    }
}

void stopRemoteVideoReadersForParticipantLocked(const std::string& participantIdentity, const std::string& reason) {
    if (participantIdentity.empty()) {
        return;
    }

    std::vector<std::shared_ptr<RemoteVideoReader>> readers;
    for (auto it = g_remoteVideoReaders.begin(); it != g_remoteVideoReaders.end();) {
        const auto& reader = it->second;
        if (reader != nullptr && reader->participantIdentity == participantIdentity) {
            readers.push_back(reader);
            it = g_remoteVideoReaders.erase(it);
        } else {
            ++it;
        }
    }

    // Queue to reaper and notify immediately, NEVER join inline while holding g_sessionMutex!
    for (const auto& reader : readers) {
        reader->running = false;
        if (reader->stream != nullptr) {
            reader->stream->close();
        }
        notifyRemoteVideoTrackRemoved(reader, reason);
        g_pendingVideoReaderJoins.emplace_back(reader, reason);
    }
    if (!readers.empty()) {
        ensureReaderReaper();
    }
}

void logRemoteVideoPublicationDetected(
    const livekit::Participant* participant,
    const std::shared_ptr<livekit::TrackPublication>& publication,
    const std::shared_ptr<livekit::Track>& track,
    const std::string& sourceLabel) {
    if (publication == nullptr || publication->kind() != livekit::TrackKind::KIND_VIDEO) {
        return;
    }

    std::cout << "DEBUG [RemoteVideo] RemoteVideo publication detected: "
              << "source=" << sourceLabel
              << " participantIdentity=" << (participant != nullptr ? participant->identity() : std::string{"<none>"})
              << " participantSid=" << (participant != nullptr ? participant->sid() : std::string{"<none>"})
              << " publicationSid=" << publication->sid()
              << " trackSid=" << (track != nullptr ? track->sid() : std::string{"<not-subscribed>"})
              << " trackName=" << (track != nullptr ? track->name() : publication->name())
              << " trackSource=" << trackSourceLabel(publication->source())
              << " muted=" << (publication->muted() ? "true" : "false")
              << std::endl;
}

void startRemoteVideoReaderLocked(
    const std::shared_ptr<livekit::Track>& track,
    const livekit::Participant* participant,
    const std::shared_ptr<livekit::TrackPublication>& publication) {
    if (track == nullptr || track->kind() != livekit::TrackKind::KIND_VIDEO) {
        return;
    }
    if (!hasRemoteVideoSink()) {
        std::cout << "DEBUG [RemoteVideo] remote video sink missing; reader not started "
                  << "trackSid=" << track->sid()
                  << " participantIdentity=" << (participant != nullptr ? participant->identity() : std::string{"<none>"})
                  << std::endl;
        return;
    }

    const auto trackSid = normalizedSid(track->sid());
    if (trackSid.empty()) {
        return;
    }
    {
        auto it = g_remoteVideoReaders.find(trackSid);
        if (it != g_remoteVideoReaders.end()) {
            const auto& existing = it->second;
            if (existing != nullptr && existing->running.load()) {
                return;
            }
            // Entry left behind by a reader whose thread already exited: stop it
            // (this also publishes the track-removed event) so this track can be
            // subscribed again instead of staying frozen for the whole call.
            std::cout << "DEBUG [RemoteVideo] replacing dead reader entry trackSid=" << trackSid << std::endl;
            stopRemoteVideoReaderLocked(trackSid, "restart-dead-reader");
        }
    }

    try {
        livekit::VideoStream::Options options;
        options.capacity = 8;
        options.format = livekit::VideoBufferType::RGBA;

        auto reader = std::make_shared<RemoteVideoReader>();
        reader->sessionId = kRawSessionId;
        reader->epoch = g_rawEpoch;
        reader->participantIdentity = participant != nullptr ? participant->identity() : std::string{};
        reader->participantSid = participant != nullptr ? participant->sid() : std::string{};
        reader->publicationSid = publication != nullptr ? publication->sid() : std::string{};
        reader->trackSid = trackSid;
        reader->trackName = track->name();
        reader->trackSource = publication != nullptr ? trackSourceLabel(publication->source()) : optionalTrackSourceLabel(track->source());
        const std::string lowerTrackName = [&] {
            std::string name = track->name();
            std::transform(name.begin(), name.end(), name.begin(),
                [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
            return name;
        }();
        // The source enum is authoritative for Orbit and modern Element clients.
        // Older publishers leave it unset, so fall back to the conventional
        // track name instead of treating their share as a 20 fps camera.
        reader->isScreenShare =
            (publication != nullptr && publication->source() == livekit::TrackSource::SOURCE_SCREENSHARE) ||
            lowerTrackName.find("screen") != std::string::npos ||
            lowerTrackName.find("share") != std::string::npos ||
            lowerTrackName.find("desktop") != std::string::npos ||
            lowerTrackName.find("display") != std::string::npos;
        reader->muted = (publication != nullptr && publication->muted()) || track->muted();
        reader->stream = livekit::VideoStream::fromTrack(track, options);
        if (reader->stream == nullptr) {
            std::cerr << "ERROR [RemoteVideo] VideoStream::fromTrack returned null "
                      << "trackSid=" << trackSid << std::endl;
            return;
        }

        const auto width = track->width().has_value()
            ? static_cast<int>(track->width().value())
            : (publication != nullptr ? static_cast<int>(publication->width()) : 0);
        const auto height = track->height().has_value()
            ? static_cast<int>(track->height().value())
            : (publication != nullptr ? static_cast<int>(publication->height()) : 0);
        std::cout << "DEBUG [RemoteVideo] RemoteVideo track subscribed: "
                  << "participantIdentity=" << reader->participantIdentity
                  << " trackSid=" << reader->trackSid
                  << " width=" << width
                  << " height=" << height
                  << " trackName=" << reader->trackName
                  << " trackSource=" << reader->trackSource
                  << " streamState=" << streamStateLabel(track->streamState())
                  << std::endl;

        // Deferred when the session lock is held so the Java sink is not
        // re-entered with g_sessionMutex still taken.
        deferDispatchIfSessionLockHeld([reader] { dispatchRemoteVideoTrackAdded(reader); });
        reader->thread = std::thread([reader] {
            // An escaping exception on a std::thread body calls std::terminate()
            // and takes the whole JVM down, so the loop is guarded and the
            // teardown below is reached on every path.
            try {
                std::uint64_t sequence = 0;
                std::uint64_t delivered = 0;
                std::uint64_t rateSkipped = 0;
                std::uint64_t downscaled = 0;
                long long lastDeliveredNs = 0;
                std::vector<std::uint8_t> scratch;
                livekit::VideoFrameEvent event;
                while (reader->running.load() && reader->stream != nullptr && reader->stream->read(event)) {
                    if (!reader->running.load()) {
                        break;
                    }
                    sequence += 1;

                    // Rate cap for cameras. Screen shares keep the frame rate
                    // the sender chose (15/30/60) and are not downscaled below
                    // their selected resolution; a 1080p share must not arrive
                    // as 720p20. Camera frames still use the cheaper guard.
                    const int maxLongEdge = reader->isScreenShare
                        ? kRemoteScreenShareMaxLongEdge
                        : kRemoteVideoMaxLongEdge;
                    const long long minFrameIntervalNs = reader->isScreenShare
                        ? 0
                        : kRemoteVideoMinFrameIntervalNs;
                    const auto nowNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
                        std::chrono::steady_clock::now().time_since_epoch()).count();
                    if (minFrameIntervalNs > 0 && lastDeliveredNs != 0 &&
                        (nowNs - lastDeliveredNs) < minFrameIntervalNs) {
                        rateSkipped += 1;
                        continue;
                    }
                    lastDeliveredNs = nowNs;
                    delivered += 1;

                    const auto& frame = event.frame;
                    // Downscale before the copy, once, in native memory. See the
                    // kRemoteVideoMaxLongEdge comment for the measured numbers.
                    int outWidth = 0;
                    int outHeight = 0;
                    const bool didDownscale =
                        frame.type() == livekit::VideoBufferType::RGBA &&
                        downscaleRgbaBox(
                            reinterpret_cast<const std::uint8_t*>(frame.data()),
                            static_cast<int>(frame.width()),
                            static_cast<int>(frame.height()),
                            maxLongEdge,
                            scratch,
                            outWidth,
                            outHeight);
                    if (didDownscale) {
                        downscaled += 1;
                    }
                    const auto timestampNs = event.timestamp_us > 0
                        ? static_cast<long long>(event.timestamp_us * 1000LL)
                        : static_cast<long long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                              std::chrono::steady_clock::now().time_since_epoch()).count());
                    // One line per track per 120 delivered frames: the achieved
                    // delivery rate, how much was dropped for rate, and how much
                    // was shrunk. Without these, "the share is invisible" has no
                    // number attached and the next guess is another guess.
                    if (delivered <= 2 || delivered % 120 == 0) {
                        std::cout << "DEBUG [RemoteVideo] frame "
                                  << "participantIdentity=" << reader->participantIdentity
                                  << " trackSid=" << reader->trackSid
                                  << " seq=" << delivered
                                  << " read=" << sequence
                                  << " rateSkipped=" << rateSkipped
                                  << " width=" << (didDownscale ? outWidth : frame.width())
                                  << " height=" << (didDownscale ? outHeight : frame.height())
                                  << " srcWidth=" << frame.width()
                                  << " srcHeight=" << frame.height()
                                  << " screenShare=" << (reader->isScreenShare ? "true" : "false")
                                  << " downscaledFrames=" << downscaled
                                  << " format=" << static_cast<int>(frame.type())
                                  << " bytes=" << (didDownscale ? scratch.size() : frame.dataSize())
                                  << " timestampNs=" << timestampNs
                                  << std::endl;
                    }
                    dispatchRemoteVideoFrame(
                        reader,
                        event,
                        sequence,
                        didDownscale ? &scratch : nullptr,
                        outWidth,
                        outHeight);
                }
            } catch (const std::exception& error) {
                std::cerr << "ERROR [RemoteVideo] reader threw trackSid=" << reader->trackSid
                          << " participantIdentity=" << reader->participantIdentity
                          << " message=" << error.what() << std::endl;
            } catch (...) {
                std::cerr << "ERROR [RemoteVideo] reader threw trackSid=" << reader->trackSid
                          << " participantIdentity=" << reader->participantIdentity
                          << " message=unknown" << std::endl;
            }
            // Clear the liveness flag FIRST and unconditionally, for the same
            // reason as the audio reader: startRemoteVideoReaderLocked's
            // "replace a dead entry" branch and nativeRescanRemoteVideoTracks'
            // hasLiveReader check both key on it, and leaving it true after we
            // exit freezes this track for the rest of the call.
            reader->running.store(false);
            // Self-remove: startRemoteVideoReaderLocked and the rescan both skip
            // a track sid that already has a map entry, so a reader that exits
            // (stream->read() returned false) without erasing itself froze that
            // track for the rest of the call. Only erase when the entry is still
            // OURS so a later reader for the same sid is not dropped.
            // try_to_lock, for the same reason as the audio reader above: the
            // teardown thread that would let us erase is the one currently
            // blocked joining US, so a timed wait can only ever add latency to
            // hangup. `running == false` above is what makes the entry
            // replaceable by the rescan if we lose the race.
            bool selfRemoved = false;
            {
                std::unique_lock<std::timed_mutex> lock(g_sessionMutex, std::try_to_lock);
                if (lock.owns_lock()) {
                    auto it = g_remoteVideoReaders.find(reader->trackSid);
                    if (it != g_remoteVideoReaders.end() && it->second == reader) {
                        g_remoteVideoReaders.erase(it);
                        selfRemoved = true;
                    }
                } else {
                    // Expected during teardown; the rescan restarts the track.
                    std::cerr << "WARN [RemoteVideo] reader self-cleanup deferred to rescan: "
                              << "g_sessionMutex busy trackSid=" << reader->trackSid << std::endl;
                }
            }
            if (selfRemoved) {
                // A successful erase proves the map held our only other owner
                // (every teardown path removes the entry before it joins), so
                // nothing can join this thread any more. Detach, otherwise
                // ~RemoteVideoReader runs on this very thread with a
                // still-joinable std::thread and calls std::terminate().
                if (reader->thread.joinable()) {
                    reader->thread.detach();
                }
                // A replaced reader is already being torn down by whoever
                // replaced it; do not publish a removal that would delete the
                // tile the replacement just added.
                notifyRemoteVideoTrackRemoved(reader, "reader-ended");
            }
            std::cout << "DEBUG [RemoteVideo] reader thread exited "
                      << "participantIdentity=" << reader->participantIdentity
                      << " trackSid=" << reader->trackSid
                      << std::endl;
        });
        g_remoteVideoReaders[trackSid] = reader;
        std::cout << "DEBUG [RemoteVideo] Started remote video stream "
                  << "participantIdentity=" << reader->participantIdentity
                  << " trackSid=" << reader->trackSid
                  << std::endl;
    } catch (const std::exception& error) {
        std::cerr << "ERROR [RemoteVideo] Failed to start remote video stream: "
                  << error.what() << std::endl;
    } catch (...) {
        std::cerr << "ERROR [RemoteVideo] Failed to start remote video stream: unknown" << std::endl;
    }
}

class OrbitAudioRoomDelegate final : public livekit::RoomDelegate {
public:
    void onLocalTrackPublished(livekit::Room&, const livekit::LocalTrackPublishedEvent& event) override {
        // The LiveKit SDK invokes RoomDelegate from its own FFI event thread
        // with no handler installed, so ANY exception unwinding out of here
        // reaches std::terminate and takes the whole JVM down, not just the
        // call. Every body below allocates (std::string copies of the event
        // accessors, std::function in deferDispatchIfSessionLockHeld,
        // make_shared + std::thread in the reader starters, join() and
        // stream->close() in the stoppers), so the guard is not decorative.
        try {
        if (event.track == nullptr) {
            return;
        }
        const auto publication = event.publication;
        const auto trackSource = event.track->source();
        std::cout << "DEBUG [OrbitLiveKitAudio] local track publish event "
                  << "localTrackSid=" << event.track->sid()
                  << " localTrackName=" << event.track->name()
                  << " localTrackKind=" << static_cast<int>(event.track->kind())
                  << " localTrackSourceEnum=" << (trackSource.has_value() ? std::to_string(static_cast<int>(trackSource.value())) : "unset")
                  << " localTrackSourceLabel=" << optionalTrackSourceLabel(trackSource)
                  << " localTrackMuted=" << (event.track->muted() ? "true" : "false")
                  << " publicationSid=" << (publication != nullptr ? publication->sid() : std::string{"<none>"})
                  << " publicationName=" << (publication != nullptr ? publication->name() : std::string{"<none>"})
                  << " publicationSourceEnum=" << (publication != nullptr ? std::to_string(static_cast<int>(publication->source())) : std::string{"unset"})
                  << " publicationSourceLabel=" << (publication != nullptr ? trackSourceLabel(publication->source()) : std::string{"unset"})
                  << " publicationMuted=" << (publication != nullptr && publication->muted() ? "true" : "false")
                  << " publicationEncryption=" << (publication != nullptr ? encryptionTypeLabel(publication->encryptionType()) : std::string{"unset"})
                  << " publicationAudioFeatures=" << (publication != nullptr ? audioFeaturesLabel(publication->audioFeatures()) : std::string{"[]"})
                  << std::endl;
        } catch (const std::exception& error) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onLocalTrackPublished threw: "
                      << error.what() << std::endl;
        } catch (...) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onLocalTrackPublished threw: unknown" << std::endl;
        }
    }
    void onParticipantConnected(livekit::Room&, const livekit::ParticipantConnectedEvent& event) override {
        // The LiveKit SDK invokes RoomDelegate from its own FFI event thread
        // with no handler installed, so ANY exception unwinding out of here
        // reaches std::terminate and takes the whole JVM down, not just the
        // call. Every body below allocates (std::string copies of the event
        // accessors, std::function in deferDispatchIfSessionLockHeld,
        // make_shared + std::thread in the reader starters, join() and
        // stream->close() in the stoppers), so the guard is not decorative.
        try {
        if (callbacksStopped("room-delegate")) {
            return;
        }
        if (event.participant == nullptr) {
            return;
        }
        SessionLock lock;
        const std::string identity = event.participant->identity();
        const std::string sid = event.participant->sid();
        std::cout << "DEBUG [OrbitLiveKitAudio] remote participant connected identity="
                  << identity << " sid=" << sid << std::endl;
        // Deferred: Java's onRemoteParticipantConnected may call straight back
        // into a native entry point that takes g_sessionMutex.
        deferDispatchIfSessionLockHeld([identity, sid] { dispatchRemoteParticipantConnected(identity, sid); });

        for (const auto& publicationEntry : event.participant->trackPublications()) {
            const auto& publication = publicationEntry.second;
            if (publication == nullptr || !publication->subscribed()) {
                continue;
            }
            const auto track = publication->track();
            if (publication->kind() == livekit::TrackKind::KIND_VIDEO) {
                logRemoteVideoPublicationDetected(event.participant, publication, track, "participant-connected");
                startRemoteVideoReaderLocked(track, event.participant, publication);
            } else if (publication->kind() == livekit::TrackKind::KIND_AUDIO) {
                startRemoteReaderLocked(track, identity);
            }
        }
        } catch (const std::exception& error) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onParticipantConnected threw: "
                      << error.what() << std::endl;
        } catch (...) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onParticipantConnected threw: unknown" << std::endl;
        }
    }
    void onTrackPublished(livekit::Room&, const livekit::TrackPublishedEvent& event) override {
        // The LiveKit SDK invokes RoomDelegate from its own FFI event thread
        // with no handler installed, so ANY exception unwinding out of here
        // reaches std::terminate and takes the whole JVM down, not just the
        // call. Every body below allocates (std::string copies of the event
        // accessors, std::function in deferDispatchIfSessionLockHeld,
        // make_shared + std::thread in the reader starters, join() and
        // stream->close() in the stoppers), so the guard is not decorative.
        try {
        if (callbacksStopped("room-delegate")) {
            return;
        }
        SessionLock lock;
        // Counted before anything can filter it out. Run 22 lost a screen share
        // whose publication Orbit could see by polling but for which NO log line
        // anywhere claimed onTrackPublished had fired -- and that was
        // unfalsifiable, because logRemoteVideoPublicationDetected returns
        // immediately on a null publication, so "the event never arrived" and
        // "the event arrived carrying nothing" printed identically.
        //
        // The rescan sees the publication in participant->trackPublications()
        // either way, so the publication existing proves nothing about the
        // event. Only a counter distinguishes them.
        noteDelegateEvent("onTrackPublished", event.publication);
        if (event.participant != nullptr) {
            const std::string identity = event.participant->identity();
            const std::string sid = event.participant->sid();
            deferDispatchIfSessionLockHeld([identity, sid] { dispatchRemoteParticipantConnected(identity, sid); });
        }
        logRemoteVideoPublicationDetected(
            event.participant,
            event.publication,
            event.publication != nullptr ? event.publication->track() : nullptr,
            "published");
        if (event.publication != nullptr && event.publication->kind() == livekit::TrackKind::KIND_AUDIO) {
            // The audio half of this handler used to be silent. Video logged via
            // logRemoteVideoPublicationDetected; audio had no line at all, so the
            // only evidence an audio track ever existed was
            // `remote audio participant added`, which fires from the READER once
            // frames start flowing.
            //
            // That made one specific failure completely invisible. A participant
            // sharing a window WITH audio publishes a second track with
            // source SOURCE_SCREENSHARE_AUDIO. If that publication arrived already
            // subscribed, this branch started a reader and printed nothing; if no
            // frames ever arrived, nothing printed either. Both cases looked
            // exactly like "Element sent no screen audio at all", which is what
            // the log said for several runs while a share was in progress.
            std::cout << "DEBUG [OrbitLiveKitAudio] remote audio publication "
                      << "participantIdentity=" << (event.participant != nullptr ? event.participant->identity() : std::string{"<none>"})
                      << " publicationSid=" << event.publication->sid()
                      << " publicationSource=" << trackSourceLabel(event.publication->source())
                      << " publicationSourceEnum=" << static_cast<int>(event.publication->source())
                      << " subscribed=" << (event.publication->subscribed() ? "true" : "false")
                      << " muted=" << (event.publication->muted() ? "true" : "false")
                      << " trackPresent=" << (event.publication->track() != nullptr ? "true" : "false")
                      << std::endl;
        }
        if (event.publication != nullptr && event.publication->subscribed()) {
            if (event.publication->kind() == livekit::TrackKind::KIND_VIDEO) {
                startRemoteVideoReaderLocked(event.publication->track(), event.participant, event.publication);
            } else if (event.publication->kind() == livekit::TrackKind::KIND_AUDIO) {
                const std::string identity = event.participant != nullptr ? event.participant->identity() : std::string{};
                startRemoteReaderLocked(event.publication->track(), identity);
            }
        }
        } catch (const std::exception& error) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onTrackPublished threw: "
                      << error.what() << std::endl;
        } catch (...) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onTrackPublished threw: unknown" << std::endl;
        }
    }
    void onTrackUnpublished(livekit::Room&, const livekit::TrackUnpublishedEvent& event) override {
        // The LiveKit SDK invokes RoomDelegate from its own FFI event thread
        // with no handler installed, so ANY exception unwinding out of here
        // reaches std::terminate and takes the whole JVM down, not just the
        // call. Every body below allocates (std::string copies of the event
        // accessors, std::function in deferDispatchIfSessionLockHeld,
        // make_shared + std::thread in the reader starters, join() and
        // stream->close() in the stoppers), so the guard is not decorative.
        try {
        if (callbacksStopped("room-delegate")) {
            return;
        }
        SessionLock lock;
        noteDelegateEvent("onTrackUnpublished", event.publication);
        if (event.publication != nullptr) {
            // CORRECTION. The previous version of this check asked
            // `publication->track() == nullptr` here and logged "withdrawn before
            // carrying a track". Run 23 showed that is a FALSE POSITIVE on every
            // withdrawal: it fired for TR_VSw2s4NVU6Jwiy, a screen share that
            // demonstrably worked (1920x1080, 120+ frames, visible). The SDK
            // detaches the track from the publication as part of tearing it down,
            // so at unpublish time the track is always already null and the
            // condition is always true.
            //
            // Two rounds of diagnosis were spent reading that line as evidence
            // about a share that had never worked. The question it was meant to
            // answer -- "did this publication EVER have a track?" -- can only be
            // answered from the rescan, which observes publications while they are
            // alive, so that is where the record is kept.
            bool everHadTrack = false;
            {
                std::lock_guard<std::mutex> lock(g_everHadTrackMutex);
                everHadTrack = g_publicationsEverSeenWithTrack.count(event.publication->sid()) > 0;
            }
            if (!everHadTrack) {
                std::cout << "DEBUG [OrbitLiveKitAudio] publication withdrawn and NEVER seen with a track "
                          << "source=" << trackSourceLabel(event.publication->source())
                          << " publicationSid=" << event.publication->sid()
                          << " kind=" << (event.publication->kind() == livekit::TrackKind::KIND_VIDEO ? "video" : "audio")
                          << " subscribed=" << (event.publication->subscribed() ? "true" : "false")
                          << " participantIdentity=" << (event.participant != nullptr ? event.participant->identity() : std::string{"<none>"})
                          << std::endl;
            }
        }
        if (event.publication != nullptr && event.publication->kind() == livekit::TrackKind::KIND_VIDEO) {
            std::cout << "DEBUG [RemoteVideo] remote video track unpublished "
                      << "participantIdentity=" << (event.participant != nullptr ? event.participant->identity() : std::string{"<none>"})
                      << " publicationSid=" << event.publication->sid()
                      << " trackSid=" << publicationTrackSid(event.publication)
                      << std::endl;
            stopRemoteVideoReaderForPublicationLocked(event.publication, "unpublished");
        } else if (event.publication != nullptr && event.publication->kind() == livekit::TrackKind::KIND_AUDIO) {
            stopRemoteReaderLocked(publicationTrackSid(event.publication));
        }
        } catch (const std::exception& error) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onTrackUnpublished threw: "
                      << error.what() << std::endl;
        } catch (...) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onTrackUnpublished threw: unknown" << std::endl;
        }
    }
    void onTrackSubscribed(livekit::Room&, const livekit::TrackSubscribedEvent& event) override {
        // The LiveKit SDK invokes RoomDelegate from its own FFI event thread
        // with no handler installed, so ANY exception unwinding out of here
        // reaches std::terminate and takes the whole JVM down, not just the
        // call. Every body below allocates (std::string copies of the event
        // accessors, std::function in deferDispatchIfSessionLockHeld,
        // make_shared + std::thread in the reader starters, join() and
        // stream->close() in the stoppers), so the guard is not decorative.
        try {
        if (callbacksStopped("room-delegate")) {
            return;
        }
        SessionLock lock;
        noteDelegateEvent("onTrackSubscribed", event.publication);
        if (event.participant != nullptr) {
            const std::string identity = event.participant->identity();
            const std::string sid = event.participant->sid();
            deferDispatchIfSessionLockHeld([identity, sid] { dispatchRemoteParticipantConnected(identity, sid); });
        }
        if (event.track != nullptr && event.track->kind() == livekit::TrackKind::KIND_VIDEO) {
            logRemoteVideoPublicationDetected(event.participant, event.publication, event.track, "subscribed");
            startRemoteVideoReaderLocked(event.track, event.participant, event.publication);
            return;
        }

        const std::string identity = event.participant != nullptr
            ? event.participant->identity()
            : std::string{};
        const auto trackSource = event.track != nullptr ? event.track->source() : std::optional<livekit::TrackSource>{};
        const auto publication = event.publication;
        std::cout << "DEBUG [OrbitLiveKitAudio] remote audio track subscribed "
                  << "participantIdentity=" << identity
                  << " participantName=" << (event.participant != nullptr ? event.participant->name() : std::string{"<none>"})
                  << " participantMetadata=" << (event.participant != nullptr ? event.participant->metadata() : std::string{"<none>"})
                  << " remoteTrackSid=" << (event.track != nullptr ? event.track->sid() : std::string{"<none>"})
                  << " remoteTrackName=" << (event.track != nullptr ? event.track->name() : std::string{"<none>"})
                  << " remoteTrackKind=" << (event.track != nullptr ? std::to_string(static_cast<int>(event.track->kind())) : std::string{"unset"})
                  << " remoteTrackSourceEnum=" << (trackSource.has_value() ? std::to_string(static_cast<int>(trackSource.value())) : "unset")
                  << " remoteTrackSourceLabel=" << optionalTrackSourceLabel(trackSource)
                  << " remoteTrackMuted=" << (event.track != nullptr && event.track->muted() ? "true" : "false")
                  << " remotePublicationSid=" << (publication != nullptr ? publication->sid() : std::string{"<none>"})
                  << " remotePublicationName=" << (publication != nullptr ? publication->name() : std::string{"<none>"})
                  << " remotePublicationSourceEnum=" << (publication != nullptr ? std::to_string(static_cast<int>(publication->source())) : std::string{"unset"})
                  << " remotePublicationSourceLabel=" << (publication != nullptr ? trackSourceLabel(publication->source()) : std::string{"unset"})
                  << " remotePublicationMuted=" << (publication != nullptr && publication->muted() ? "true" : "false")
                  << " remotePublicationEncryption=" << (publication != nullptr ? encryptionTypeLabel(publication->encryptionType()) : std::string{"unset"})
                  << " remotePublicationAudioFeatures=" << (publication != nullptr ? audioFeaturesLabel(publication->audioFeatures()) : std::string{"[]"})
                  << std::endl;
        startRemoteReaderLocked(event.track, identity);
        } catch (const std::exception& error) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onTrackSubscribed threw: "
                      << error.what() << std::endl;
        } catch (...) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onTrackSubscribed threw: unknown" << std::endl;
        }
    }
    void onTrackUnsubscribed(livekit::Room&, const livekit::TrackUnsubscribedEvent& event) override {
        // The LiveKit SDK invokes RoomDelegate from its own FFI event thread
        // with no handler installed, so ANY exception unwinding out of here
        // reaches std::terminate and takes the whole JVM down, not just the
        // call. Every body below allocates (std::string copies of the event
        // accessors, std::function in deferDispatchIfSessionLockHeld,
        // make_shared + std::thread in the reader starters, join() and
        // stream->close() in the stoppers), so the guard is not decorative.
        try {
        if (callbacksStopped("room-delegate")) {
            return;
        }
        if (event.track == nullptr) {
            return;
        }
        SessionLock lock;
        if (event.track->kind() == livekit::TrackKind::KIND_VIDEO) {
            std::cout << "DEBUG [RemoteVideo] remote video track unsubscribed "
                      << "participantIdentity=" << (event.participant != nullptr ? event.participant->identity() : std::string{"<none>"})
                      << " trackSid=" << event.track->sid()
                      << std::endl;
            stopRemoteVideoReaderLocked(event.track->sid(), "unsubscribed");
            stopRemoteVideoReaderForPublicationLocked(event.publication, "unsubscribed-publication");
        } else {
            stopRemoteReaderLocked(event.track->sid());
        }
        } catch (const std::exception& error) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onTrackUnsubscribed threw: "
                      << error.what() << std::endl;
        } catch (...) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onTrackUnsubscribed threw: unknown" << std::endl;
        }
    }
    void onTrackSubscriptionFailed(livekit::Room&, const livekit::TrackSubscriptionFailedEvent& event) override {
        // The LiveKit SDK invokes RoomDelegate from its own FFI event thread
        // with no handler installed, so ANY exception unwinding out of here
        // reaches std::terminate and takes the whole JVM down, not just the
        // call. Every body below allocates (std::string copies of the event
        // accessors, std::function in deferDispatchIfSessionLockHeld,
        // make_shared + std::thread in the reader starters, join() and
        // stream->close() in the stoppers), so the guard is not decorative.
        try {
        if (callbacksStopped("room-delegate")) {
            return;
        }
        std::cout << "WARN [RemoteVideo] track subscription failed "
                  << "participantIdentity=" << (event.participant != nullptr ? event.participant->identity() : std::string{"<none>"})
                  << " trackSid=" << event.track_sid
                  << " error=" << event.error
                  << std::endl;
        } catch (const std::exception& error) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onTrackSubscriptionFailed threw: "
                      << error.what() << std::endl;
        } catch (...) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onTrackSubscriptionFailed threw: unknown" << std::endl;
        }
    }
    void onTrackMuted(livekit::Room&, const livekit::TrackMutedEvent& event) override {
        // The LiveKit SDK invokes RoomDelegate from its own FFI event thread
        // with no handler installed, so ANY exception unwinding out of here
        // reaches std::terminate and takes the whole JVM down, not just the
        // call. Every body below allocates (std::string copies of the event
        // accessors, std::function in deferDispatchIfSessionLockHeld,
        // make_shared + std::thread in the reader starters, join() and
        // stream->close() in the stoppers), so the guard is not decorative.
        try {
        if (callbacksStopped("room-delegate")) {
            return;
        }
        SessionLock lock;
        if (event.publication != nullptr && event.publication->kind() == livekit::TrackKind::KIND_VIDEO) {
            std::cout << "DEBUG [RemoteVideo] remote video track muted "
                      << "participantIdentity=" << (event.participant != nullptr ? event.participant->identity() : std::string{"<none>"})
                      << " publicationSid=" << event.publication->sid()
                      << " trackSid=" << publicationTrackSid(event.publication)
                      << std::endl;
            stopRemoteVideoReaderForPublicationLocked(event.publication, "muted");
        } else {
            // Remote mic muted. The remote() guard keeps our OWN local-mute
            // events (whose track is not remote) from announcing us as a
            // muted remote participant. A null subscribed track means the
            // mute predates our subscription — that initial state is
            // announced by startRemoteReaderLocked instead.
            const auto track = event.publication != nullptr ? event.publication->track() : nullptr;
            if (event.participant != nullptr && track != nullptr && track->remote()) {
                const std::string identity = event.participant->identity();
                std::cout << "DEBUG [OrbitLiveKitAudio] remote mic muted identity="
                          << identity << std::endl;
                deferDispatchIfSessionLockHeld([identity] { dispatchRemoteAudioMuteChanged(identity, true); });
            }
        }
        } catch (const std::exception& error) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onTrackMuted threw: "
                      << error.what() << std::endl;
        } catch (...) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onTrackMuted threw: unknown" << std::endl;
        }
    }
    void onTrackUnmuted(livekit::Room&, const livekit::TrackUnmutedEvent& event) override {
        // The LiveKit SDK invokes RoomDelegate from its own FFI event thread
        // with no handler installed, so ANY exception unwinding out of here
        // reaches std::terminate and takes the whole JVM down, not just the
        // call. Every body below allocates (std::string copies of the event
        // accessors, std::function in deferDispatchIfSessionLockHeld,
        // make_shared + std::thread in the reader starters, join() and
        // stream->close() in the stoppers), so the guard is not decorative.
        try {
        if (callbacksStopped("room-delegate")) {
            return;
        }
        SessionLock lock;
        if (event.publication != nullptr && event.publication->kind() == livekit::TrackKind::KIND_VIDEO) {
            const auto track = event.publication->track();
            std::cout << "DEBUG [RemoteVideo] remote video track unmuted "
                      << "participantIdentity=" << (event.participant != nullptr ? event.participant->identity() : std::string{"<none>"})
                      << " publicationSid=" << event.publication->sid()
                      << " trackSid=" << publicationTrackSid(event.publication)
                      << std::endl;
            logRemoteVideoPublicationDetected(event.participant, event.publication, track, "unmuted");
            if (track != nullptr && track->remote()) {
                startRemoteVideoReaderLocked(track, event.participant, event.publication);
            }
        } else {
            const auto muteTrack = event.publication != nullptr ? event.publication->track() : nullptr;
            if (event.participant != nullptr && muteTrack != nullptr && muteTrack->remote()) {
                const std::string identity = event.participant->identity();
                std::cout << "DEBUG [OrbitLiveKitAudio] remote mic unmuted identity="
                          << identity << std::endl;
                deferDispatchIfSessionLockHeld([identity] { dispatchRemoteAudioMuteChanged(identity, false); });
            }
        }
        } catch (const std::exception& error) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onTrackUnmuted threw: "
                      << error.what() << std::endl;
        } catch (...) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onTrackUnmuted threw: unknown" << std::endl;
        }
    }
    void onParticipantDisconnected(livekit::Room&, const livekit::ParticipantDisconnectedEvent& event) override {
        // The LiveKit SDK invokes RoomDelegate from its own FFI event thread
        // with no handler installed, so ANY exception unwinding out of here
        // reaches std::terminate and takes the whole JVM down, not just the
        // call. Every body below allocates (std::string copies of the event
        // accessors, std::function in deferDispatchIfSessionLockHeld,
        // make_shared + std::thread in the reader starters, join() and
        // stream->close() in the stoppers), so the guard is not decorative.
        try {
        if (callbacksStopped("room-delegate")) {
            return;
        }
        if (event.participant == nullptr) {
            return;
        }
        SessionLock lock;
        const std::string identity = event.participant->identity();
        const std::string sid = event.participant->sid();
        std::cout << "DEBUG [OrbitLiveKitAudio] remote participant disconnected identity="
                  << identity << " sid=" << sid << std::endl;
        deferDispatchIfSessionLockHeld([identity, sid] { dispatchRemoteParticipantDisconnected(identity, sid); });
        stopRemoteReadersForParticipantLocked(identity);
        stopRemoteVideoReadersForParticipantLocked(identity, "participant-disconnected");
        } catch (const std::exception& error) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onParticipantDisconnected threw: "
                      << error.what() << std::endl;
        } catch (...) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onParticipantDisconnected threw: unknown" << std::endl;
        }
    }
    void onDisconnected(livekit::Room&, const livekit::DisconnectedEvent&) override {
        // The LiveKit SDK invokes RoomDelegate from its own FFI event thread
        // with no handler installed, so ANY exception unwinding out of here
        // reaches std::terminate and takes the whole JVM down, not just the
        // call. Every body below allocates (std::string copies of the event
        // accessors, std::function in deferDispatchIfSessionLockHeld,
        // make_shared + std::thread in the reader starters, join() and
        // stream->close() in the stoppers), so the guard is not decorative.
        try {
        if (callbacksStopped("room-delegate")) {
            return;
        }
        // SessionLock, not lock_guard: stopping the readers publishes
        // onRemoteVideoTrackRemoved, and Kotlin answers that by calling
        // nativeSetAcceptingFrames / setMixedAudioSink(null) /
        // disableRemoteVideoSink, every one of which takes g_sessionMutex. With a
        // plain lock_guard that is a self-deadlock on a non-recursive mutex. The
        // removals are deferred and flushed by ~SessionLock after the unlock.
        SessionLock lock;
        g_acceptingFrames = false;
        g_micPublished = false;
        stopAllRemoteReadersLocked();
        stopAllRemoteVideoReadersLocked("room-disconnected");
        g_connected = false;
        } catch (const std::exception& error) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onDisconnected threw: "
                      << error.what() << std::endl;
        } catch (...) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onDisconnected threw: unknown" << std::endl;
        }
    }

    // Logging-only on purpose: this fires from webrtc worker threads and the
    // session mutex may be held by teardown paths that join those threads, so
    // it takes no locks. Guarded anyway because it allocates a std::string copy
    // of the identity, and an exception here reaches std::terminate.
    void onE2eeStateChanged(livekit::Room&, const livekit::E2eeStateChangedEvent& event) override {
        try {
            const std::string identity = event.participant != nullptr ? event.participant->identity() : std::string{"<none>"};
            std::cout << "DEBUG [OrbitLiveKitAudio] e2ee state changed identity=" << identity
                      << " state=" << static_cast<int>(event.state) << std::endl;
        } catch (const std::exception& error) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onE2eeStateChanged threw: "
                      << error.what() << std::endl;
        } catch (...) {
            std::cerr << "ERROR [OrbitLiveKitAudio] onE2eeStateChanged threw: unknown" << std::endl;
        }
    }
};

// Dedicated system-audio capture for the group-call screen-share audio track.
// The fork ADM's loopback mixes into the mic PCM (P2P only); this thread feeds a
// separate SOURCE_SCREENSHARE_AUDIO track instead. Never takes g_sessionMutex —
// teardown joins this thread before touching the shared source.
void stopShareLoopbackThread() {
    g_shareAudioFeedTarget.store(nullptr, std::memory_order_release);
    // Cleared BEFORE the early return: the previous session's answer is worse
    // than no answer, because ShareAudioAec.Subtractor.checkExclusionOnce
    // latches on the first value it sees and a stale 1 disables the echo
    // canceller (so we re-transmit our own playback) for the whole new share.
    g_shareLoopbackFullDeviceFallback.store(false, std::memory_order_release);
    g_shareLoopbackModeResolved.store(false, std::memory_order_release);
    if (!g_shareLoopbackActive.load(std::memory_order_acquire)) return;
    g_shareLoopbackActive.store(false, std::memory_order_release);
    if (g_shareLoopbackThread.joinable()) {
        if (g_shareLoopbackThread.get_id() == std::this_thread::get_id()) {
            g_shareLoopbackThread.detach();
        } else {
            g_shareLoopbackThread.join();
        }
    }
}

// Completion handler for ActivateAudioInterfaceAsync. It MUST be a real,
// refcounted heap object: the OS keeps its own reference for the lifetime of the
// async operation and may call ActivateCompleted long after the calling thread
// gave up waiting. A stack object whose Release() never reached zero meant the
// OS wrote into a dead frame and SetEvent()'d a closed HANDLE.
class ShareAudioClientActivator : public IActivateAudioInterfaceCompletionHandler {
public:
    HANDLE event = NULL;
    IAudioClient* audioClient = nullptr;
    HRESULT result = E_FAIL;

    ShareAudioClientActivator() { event = CreateEvent(NULL, FALSE, FALSE, NULL); }
    ~ShareAudioClientActivator() {
        if (event) CloseHandle(event);
        if (audioClient) audioClient->Release();
    }

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (riid == __uuidof(IUnknown) || riid == __uuidof(IActivateAudioInterfaceCompletionHandler)) {
            *ppv = static_cast<IActivateAudioInterfaceCompletionHandler*>(this);
            return S_OK;
        }
        *ppv = NULL;
        return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() override {
        return static_cast<ULONG>(InterlockedIncrement(&refCount_));
    }
    STDMETHODIMP_(ULONG) Release() override {
        const LONG remaining = InterlockedDecrement(&refCount_);
        if (remaining == 0) {
            delete this;
        }
        return static_cast<ULONG>(remaining > 0 ? remaining : 0);
    }

    STDMETHODIMP ActivateCompleted(IActivateAudioInterfaceAsyncOperation* operation) override {
        HRESULT activateResult = E_FAIL;
        IUnknown* unknown = NULL;
        result = operation->GetActivateResult(&activateResult, &unknown);
        if (SUCCEEDED(result) && SUCCEEDED(activateResult) && unknown != NULL) {
            result = unknown->QueryInterface(__uuidof(IAudioClient), (void**)&audioClient);
            unknown->Release();
        } else {
            // GetActivateResult handed us a reference on every path that
            // produced an object, including the ones where the activation
            // itself failed; without this release the interface leaked.
            if (unknown != NULL) {
                unknown->Release();
            }
            result = activateResult;
        }
        SetEvent(event);
        return S_OK;
    }

private:
    // Starts at 1: the waiter owns the first reference and hands it straight to
    // ActivateAudioInterfaceAsync, which owns the second.
    volatile LONG refCount_{1};
};

void resolveShareAecCallback(JNIEnv* env) {
    if (env == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(g_shareAecMutex);
        if (g_shareAecRouterClass != nullptr && g_shareAecOnFrameMethod != nullptr) return;
    }
    jclass localClass = env->FindClass("livekit/ShareAudioAec");
    if (localClass == nullptr) {
        env->ExceptionClear();
        std::cerr << "WARN [ScreenShareAudioPublish] ShareAudioAec class not found; echo cancellation unavailable" << std::endl;
        return;
    }
    jmethodID method = env->GetStaticMethodID(localClass, "onLoopbackFrame", "([B)V");
    if (method == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(localClass);
        std::cerr << "WARN [ScreenShareAudioPublish] ShareAudioAec.onLoopbackFrame not found; echo cancellation unavailable" << std::endl;
        return;
    }
    jclass globalClass = static_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);
    bool installed = false;
    {
        // Two threads can race to resolve this (the publishing JVM thread and
        // the loopback worker); the loser drops the global ref it just made
        // instead of leaking it.
        std::lock_guard<std::mutex> lock(g_shareAecMutex);
        if (g_shareAecRouterClass == nullptr) {
            g_shareAecRouterClass = globalClass;
            g_shareAecOnFrameMethod = method;
            installed = true;
        }
    }
    if (!installed) {
        if (globalClass != nullptr) env->DeleteGlobalRef(globalClass);
        return;
    }
    std::cout << "DEBUG [ScreenShareAudioPublish] share-audio AEC upcall resolved" << std::endl;
}

void dispatchShareLoopbackFrame(
    JNIEnv* env,
    std::vector<std::int16_t>&& frameSamples,
    const std::shared_ptr<livekit::AudioSource>& source) {
    jclass routerClass = nullptr;
    jmethodID onFrameMethod = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_shareAecMutex);
        routerClass = g_shareAecRouterClass;
        onFrameMethod = g_shareAecOnFrameMethod;
    }
    if (env != nullptr && routerClass != nullptr && onFrameMethod != nullptr) {
        jbyteArray pcm = env->NewByteArray(static_cast<jsize>(frameSamples.size() * 2));
        if (pcm == nullptr) {
            if (env->ExceptionCheck() == JNI_TRUE) env->ExceptionClear();
            return;
        }
        env->SetByteArrayRegion(
            pcm, 0, static_cast<jsize>(frameSamples.size() * 2),
            reinterpret_cast<const jbyte*>(frameSamples.data()));
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
            env->DeleteLocalRef(pcm);
            return;
        }
        env->CallStaticVoidMethod(routerClass, onFrameMethod, pcm);
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteLocalRef(pcm);
        return;
    }
    // No JVM/AEC upcall available: ship the raw captured frame like before.
    try {
        livekit::AudioFrame frame(std::move(frameSamples), kSampleRate, 2, kSamplesPerChannel);
        source->captureFrame(frame, kAudioSourceCaptureTimeoutMs);
    } catch (...) {
        // Consumer not ready or transient overflow; drop and continue.
    }
}

void shareAudioLoopbackMainImpl(std::shared_ptr<livekit::AudioSource> source) {
    // RPC_E_CHANGED_MODE means COM is already up on this thread under a
    // different apartment model: the apartment is usable, but this call did not
    // initialize it, so CoUninitialize() must NOT be balanced below.
    const HRESULT comHr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    const bool comInitializedHere = SUCCEEDED(comHr);
    if (!comInitializedHere && comHr != RPC_E_CHANGED_MODE) {
        std::cerr << "ERROR [ScreenShareAudioPublish] CoInitializeEx failed hr=0x"
                  << std::hex << comHr << std::dec << std::endl;
        return;
    }
    g_shareLoopbackFullDeviceFallback.store(false, std::memory_order_release);
    g_shareLoopbackModeResolved.store(false, std::memory_order_release);

    IMMDeviceEnumerator* enumerator = NULL;
    IMMDevice* device = NULL;
    IAudioClient* audioClient = NULL;
    IAudioCaptureClient* captureClient = NULL;
    WAVEFORMATEX* mixFormat = NULL;
    JNIEnv* aecEnv = nullptr;
    bool aecAttached = false;

    HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), NULL, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator), (void**)&enumerator);
    if (FAILED(hr)) goto cleanup;
    hr = enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device);
    if (FAILED(hr)) goto cleanup;

    {
        // Prefer process-loopback (excludes our own playback) on builds that support it.
        AUDIOCLIENT_ACTIVATION_PARAMS params = {};
        params.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK;
        params.ProcessLoopbackParams.ProcessLoopbackMode = PROCESS_LOOPBACK_MODE_EXCLUDE_TARGET_PROCESS_TREE;
        params.ProcessLoopbackParams.TargetProcessId = GetCurrentProcessId();

        PROPVARIANT activateParams = {};
        activateParams.vt = VT_BLOB;
        activateParams.blob.cbSize = sizeof(params);
        activateParams.blob.pBlobData = (BYTE*)&params;

        LPWSTR deviceId = NULL;
        hr = device->GetId(&deviceId);
        if (SUCCEEDED(hr)) {
            // Heap-allocated so the OS-held reference stays valid even if the
            // wait times out and this frame goes away.
            ShareAudioClientActivator* activator = new ShareAudioClientActivator();
            IActivateAudioInterfaceAsyncOperation* asyncOp = NULL;
            HRESULT asyncHr =
                ActivateAudioInterfaceAsync(deviceId, __uuidof(IAudioClient), &activateParams, activator, &asyncOp);
            if (SUCCEEDED(asyncHr) && asyncOp) {
                const DWORD waitResult = WaitForSingleObject(activator->event, 2000);
                if (waitResult != WAIT_OBJECT_0) {
                    // The OS still holds its reference and will invoke
                    // ActivateCompleted later, so the activator stays alive;
                    // we just stop waiting and take the full-device path.
                    g_shareLoopbackFullDeviceFallback.store(true, std::memory_order_release);
                    std::cerr << "WARN [ScreenShareAudioPublish] process-loopback activation timed out waitResult="
                              << waitResult
                              << "; falling back to FULL-DEVICE loopback which WILL capture Orbit's own playback" << std::endl;
                } else if (SUCCEEDED(activator->result) && activator->audioClient != NULL) {
                    audioClient = activator->audioClient;
                    audioClient->AddRef();
                    g_shareLoopbackFullDeviceFallback.store(false, std::memory_order_release);
                    std::cout << "DEBUG [ScreenShareAudioPublish] process-loopback activated; own-process tree EXCLUDED from capture" << std::endl;
                } else {
                    g_shareLoopbackFullDeviceFallback.store(true, std::memory_order_release);
                    std::cout << "WARN [ScreenShareAudioPublish] process-loopback activation failed hr=0x"
                              << std::hex << activator->result << std::dec
                              << " (EXCLUDE mode needs Windows 11 24H2+); falling back to FULL-DEVICE loopback which WILL capture Orbit's own playback" << std::endl;
                }
                g_shareLoopbackModeResolved.store(true, std::memory_order_release);
                asyncOp->Release();
            } else {
                g_shareLoopbackFullDeviceFallback.store(true, std::memory_order_release);
                g_shareLoopbackModeResolved.store(true, std::memory_order_release);
                std::cout << "WARN [ScreenShareAudioPublish] ActivateAudioInterfaceAsync failed hr=0x"
                          << std::hex << asyncHr << std::dec
                          << "; falling back to FULL-DEVICE loopback which WILL capture Orbit's own playback" << std::endl;
            }
            // Drop the waiter's reference on every path, including the timeout
            // path: the OS still owns the other one and will free the object
            // when the activation completes.
            activator->Release();
            CoTaskMemFree(deviceId);
        }
    }

    if (audioClient == NULL) {
        hr = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, NULL, (void**)&audioClient);
        if (FAILED(hr)) goto cleanup;
    }

    hr = audioClient->GetMixFormat(&mixFormat);
    if (FAILED(hr)) goto cleanup;
    hr = audioClient->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, 0, 0, mixFormat, NULL);
    if (FAILED(hr)) goto cleanup;
    hr = audioClient->GetService(__uuidof(IAudioCaptureClient), (void**)&captureClient);
    if (FAILED(hr)) goto cleanup;

    audioClient->Start();
    {
        // Attach once for the whole capture loop; the AEC round-trip happens
        // per frame but thread attach/detach must not.
        JNIEnv* aecEnvLocal = nullptr;
        bool aecAttachedLocal = false;
        if (getJniEnv(&aecEnvLocal, &aecAttachedLocal, "share-audio-aec")) {
            aecEnv = aecEnvLocal;
            aecAttached = aecAttachedLocal;
            resolveShareAecCallback(aecEnv);
        }

        const int captureRate = mixFormat->nSamplesPerSec;
        const int captureChannels = mixFormat->nChannels;
        std::deque<std::int16_t> accumulation;

        std::cout << "DEBUG [ScreenShareAudioPublish] loopback started sampleRate="
                  << captureRate << " channels=" << captureChannels << std::endl;

        // Absolute-deadline pacing, same shape mixLoop() uses: Sleep(10) after
        // the drain made the real period 10 ms + drain time, so frames were
        // delivered in ~20 ms bursts instead of evenly every 10 ms.
        auto nextTick = std::chrono::steady_clock::now();
        while (g_shareLoopbackActive.load(std::memory_order_acquire)) {
            nextTick += std::chrono::milliseconds(10);
            std::this_thread::sleep_until(nextTick);

            UINT32 packetLength = 0;
            if (FAILED(captureClient->GetNextPacketSize(&packetLength))) break;

            while (packetLength != 0) {
                BYTE* data = nullptr;
                UINT32 framesAvailable = 0;
                DWORD flags = 0;
                if (FAILED(captureClient->GetBuffer(&data, &framesAvailable, &flags, NULL, NULL))) break;

                std::vector<std::int16_t> pcm16;
                if (flags & AUDCLNT_BUFFERFLAGS_SILENT) {
                    pcm16.assign((std::size_t)framesAvailable * captureChannels, 0);
                } else if (mixFormat->wFormatTag == WAVE_FORMAT_EXTENSIBLE &&
                           ((WAVEFORMATEXTENSIBLE*)mixFormat)->SubFormat == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT) {
                    float* floatData = (float*)data;
                    pcm16.reserve((std::size_t)framesAvailable * captureChannels);
                    for (UINT32 i = 0; i < framesAvailable * (UINT32)captureChannels; i++) {
                        float sample = data ? floatData[i] : 0.0f;
                        if (sample > 1.0f) sample = 1.0f;
                        if (sample < -1.0f) sample = -1.0f;
                        pcm16.push_back((std::int16_t)(sample * 32767.0f));
                    }
                } else if (data != nullptr) {
                    const std::int16_t* int16Data = (const std::int16_t*)data;
                    pcm16.assign(int16Data, int16Data + (std::size_t)framesAvailable * captureChannels);
                }

                if (!pcm16.empty()) {
                    // Nearest-neighbor resample to 48 kHz stereo.
                    const int targetFrames = (int)((long long)framesAvailable * 48000 / captureRate);
                    std::vector<std::int16_t> resampled((std::size_t)targetFrames * 2, 0);
                    for (int i = 0; i < targetFrames; i++) {
                        int srcIdx = (int)((long long)i * captureRate / 48000);
                        if (srcIdx >= (int)framesAvailable) srcIdx = (int)framesAvailable - 1;
                        for (int c = 0; c < 2; c++) {
                            int srcChannel = captureChannels > c ? c : 0;
                            resampled[(std::size_t)i * 2 + c] = pcm16[(std::size_t)srcIdx * captureChannels + srcChannel];
                        }
                    }
                    accumulation.insert(accumulation.end(), resampled.begin(), resampled.end());

                    // Chunk to exact 10 ms LiveKit frames: 480 samples x 2 channels.
                    while (accumulation.size() >= (std::size_t)kSamplesPerChannel * 2) {
                        std::vector<std::int16_t> frameSamples(
                            accumulation.begin(),
                            accumulation.begin() + (std::ptrdiff_t)(kSamplesPerChannel * 2));
                        accumulation.erase(
                            accumulation.begin(),
                            accumulation.begin() + (std::ptrdiff_t)(kSamplesPerChannel * 2));
                        dispatchShareLoopbackFrame(aecEnv, std::move(frameSamples), source);
                    }

                    // Bound drift if the consumer stalls for a long time (~500 ms).
                    const std::size_t maxQueued = (std::size_t)kSampleRate * 2 / 2;
                    if (accumulation.size() > maxQueued) {
                        accumulation.erase(accumulation.begin(), accumulation.end() - maxQueued);
                    }
                }

                captureClient->ReleaseBuffer(framesAvailable);
                if (FAILED(captureClient->GetNextPacketSize(&packetLength))) break;
            }

            const auto now = std::chrono::steady_clock::now();
            if (now > nextTick + std::chrono::milliseconds(20)) {
                nextTick = now;
            }
        }
        audioClient->Stop();
        std::cout << "DEBUG [ScreenShareAudioPublish] loopback stopped" << std::endl;
    }

cleanup:
    releaseJniEnv(aecAttached);
    if (captureClient) captureClient->Release();
    if (audioClient) audioClient->Release();
    if (mixFormat) CoTaskMemFree(mixFormat);
    if (device) device->Release();
    if (enumerator) enumerator->Release();
    if (comInitializedHere) CoUninitialize();
}

// Thread entry point. An exception escaping a std::thread body calls
// std::terminate() and kills the JVM, and the WASAPI/COM plumbing below is
// exactly the sort of code that can throw, so keep the body in a helper and
// guard it here.
void shareAudioLoopbackMain(std::shared_ptr<livekit::AudioSource> source) {
    try {
        shareAudioLoopbackMainImpl(std::move(source));
    } catch (const std::exception& error) {
        std::cerr << "ERROR [ScreenShareAudioPublish] loopback thread threw: "
                  << error.what() << std::endl;
    } catch (...) {
        std::cerr << "ERROR [ScreenShareAudioPublish] loopback thread threw: unknown" << std::endl;
    }
}

void resetRoomLocked() {
    // The TXSTAT thread reads g_audioTrack, which this function clears. A stale
    // thread would keep printing a verdict for a track that is no longer published,
    // which is worse than no line at all -- it would look like live evidence.
    stopTxStats();
    g_acceptingFrames = false;
    // Per-room subscribe bookkeeping. Left set, a reconnect would refuse to retry
    // a publication whose SID the new room happens to reuse, and the
    // change-only inventory would print nothing because it still "matched" the
    // previous call's tracks.
    {
        std::lock_guard<std::mutex> forcedLock(g_forcedSubscribesMutex);
        g_forcedSubscribes.clear();
    }
    g_remotePublicationInventory.clear();
    g_lastDelegateCounts.clear();
    {
        std::lock_guard<std::mutex> lock(g_everHadTrackMutex);
        g_publicationsEverSeenWithTrack.clear();
    }
    {
        // A fresh SFU session is exactly the recovery for a stuck share, so a
        // reconnect must clear the tick counters -- otherwise the new session's
        // first screenshare inherits the old one's count and is declared stuck
        // within a second of appearing.
        std::lock_guard<std::mutex> lock(g_tracklessTicksMutex);
        g_tracklessTicks.clear();
    }
    {
        std::lock_guard<std::mutex> lock(g_announcedVideoMutex);
        g_announcedVideoPublications.clear();
    }
    {
        // Same reasoning as g_announcedVideoPublications directly above, and the
        // omission of it here is why a reconnect could leave the call with no
        // remote tiles at all.
        //
        // The dedup set survives a reconnect, so on the new room every
        // participant still hashes to an entry the dispatcher believes it has
        // already announced. `nativeRescanRemoteVideoTracks` then re-announces
        // VIDEO (that set was cleared) but silently skips every PARTICIPANT, so
        // Kotlin's `LiveKitRemoteVideoFrameStore.participantConnected` is never
        // called again. `clear()` had already emptied `_remoteParticipants`, and
        // nothing refills it -- the call surface builds every voice/avatar tile
        // from that set, so the grid collapses to the local "You" node while
        // audio, which never touches this store, keeps working. A user hears a
        // normal call and sees themselves alone. Only a full disconnect cleared
        // this set (the two disconnect paths both did), which is why hanging up
        // and rejoining was the only thing that restored the tiles.
        //
        // Clearing it here is what makes reconnect symmetric with disconnect: a
        // new SFU session re-announces from scratch. Within one session the
        // set still does its job -- that is the "announce each participant
        // exactly once" fix, and it is unaffected.
        //
        // Tight scope on purpose: this function dispatches removals into Java
        // further down, and the Java side calls back into the participant
        // dispatcher, which takes this same non-recursive mutex. Holding it
        // across that dispatch would deadlock.
        std::lock_guard<std::mutex> announcedLock(g_announcedParticipantsMutex);
        g_announcedParticipantSids.clear();
    }
    {
        std::lock_guard<std::mutex> lock(g_delegateEventMutex);
        g_delegatePublished = 0;
        g_delegateUnpublished = 0;
        g_delegateSubscribed = 0;
        g_delegateNullPublication = 0;
    }
    if (g_videoSource != nullptr || g_videoTrack != nullptr || g_videoPublished || g_acceptingVideoFrames) {
        std::cout << "DEBUG [VideoPublish] stop requested reason=room-reset" << std::endl;
    }
    g_acceptingVideoFrames = false;
    if (g_room != nullptr && g_room->localParticipant() != nullptr && !g_videoPublicationSid.empty()) {
        try {
            std::cout << "DEBUG [VideoPublish] unpublish started publicationSid="
                      << g_videoPublicationSid
                      << " localTrackSid=" << (g_videoLocalTrackSid.empty() ? std::string{"<none>"} : g_videoLocalTrackSid)
                      << std::endl;
            g_room->localParticipant()->unpublishTrack(g_videoPublicationSid);
            std::cout << "DEBUG [VideoPublish] unpublish success publicationSid="
                      << g_videoPublicationSid << std::endl;
        } catch (const std::exception& error) {
            std::cerr << "WARN [VideoPublish] unpublish failed publicationSid="
                      << g_videoPublicationSid
                      << " message=" << error.what()
                      << std::endl;
        } catch (...) {
            std::cerr << "WARN [VideoPublish] unpublish failed publicationSid="
                      << g_videoPublicationSid
                      << " message=unknown"
                      << std::endl;
        }
    }
    g_videoTrack.reset();
    g_videoSource.reset();
    g_videoPublished = false;
    g_cameraRequested = false;
    g_videoWidth = 0;
    g_videoHeight = 0;
    g_videoFps = 0;
    g_videoFormat = 0;
    g_videoPublicationSid.clear();
    g_videoLocalTrackSid.clear();
    g_videoFrameDroppedNotReadyLogged = false;
    g_videoPushDiagnosticsLogged = 0;
    g_lastVideoTimestampNs = 0;
    if (g_screenShareSource != nullptr || g_screenShareTrack != nullptr || g_screenSharePublished) {
        std::cout << "DEBUG [ScreenSharePublish] stop requested reason=room-reset" << std::endl;
    }
    g_acceptingScreenShareFrames = false;
    if (g_room != nullptr && g_room->localParticipant() != nullptr && !g_screenSharePublicationSid.empty()) {
        try {
            std::cout << "DEBUG [ScreenSharePublish] unpublish started publicationSid="
                      << g_screenSharePublicationSid
                      << " reason=room-reset"
                      << std::endl;
            g_room->localParticipant()->unpublishTrack(g_screenSharePublicationSid);
        } catch (const std::exception& error) {
            std::cerr << "WARN [ScreenSharePublish] unpublish failed publicationSid="
                      << g_screenSharePublicationSid
                      << " message=" << error.what()
                      << std::endl;
        } catch (...) {
            std::cerr << "WARN [ScreenSharePublish] unpublish failed publicationSid="
                      << g_screenSharePublicationSid
                      << " message=unknown"
                      << std::endl;
        }
    }
    g_screenShareTrack.reset();
    g_screenShareSource.reset();
    g_screenSharePublished = false;
    g_screenShareWidth = 0;
    g_screenShareHeight = 0;
    g_screenShareFps = 0;
    g_screenSharePublicationSid.clear();
    g_screenShareFrameDroppedNotReadyLogged = false;
    g_screenSharePushDiagnosticsLogged = 0;
    if (g_shareAudioPublished || g_shareAudioTrack != nullptr || !g_shareAudioPublicationSid.empty()) {
        std::cout << "DEBUG [ScreenShareAudioPublish] stop requested reason=room-reset" << std::endl;
    }
    stopShareLoopbackThread();
    if (g_room != nullptr && g_room->localParticipant() != nullptr && !g_shareAudioPublicationSid.empty()) {
        try {
            g_room->localParticipant()->unpublishTrack(g_shareAudioPublicationSid);
        } catch (const std::exception& error) {
            std::cerr << "WARN [ScreenShareAudioPublish] unpublish failed publicationSid="
                      << g_shareAudioPublicationSid
                      << " message=" << error.what()
                      << std::endl;
        } catch (...) {
            std::cerr << "WARN [ScreenShareAudioPublish] unpublish failed message=unknown" << std::endl;
        }
    }
    g_shareAudioTrack.reset();
    g_shareAudioSource.reset();
    g_shareAudioPublished = false;
    g_shareAudioPublicationSid.clear();
    g_audioSourceWorker.stop();
    if (g_room != nullptr) {
        g_room->setDelegate(nullptr);
    }
    stopAllRemoteReadersLocked();
    stopAllRemoteVideoReadersLocked("room-reset");
    // NOTE: g_participantMediaKeys is deliberately NOT cleared here.
    // connectRoom() calls resetRoomLocked() and then, once the E2EE manager is
    // up, re-applies every cached key to the provider (the "e2ee flushed cached
    // key" loop). Clearing here would wipe the pre-connect cache that loop
    // exists to consume, and peers that already consider us joined do not
    // re-send -- that is the whole H8-V3 latecomer-deafness class of bug.
    // Teardown paths (nativeDisconnect / nativeDestroySession) clear it.
    g_audioTrack.reset();
    g_audioSource.reset();
    g_delegate.reset();
    // Destroy the Room on a thread that does NOT hold g_sessionMutex.
    //
    // g_room is a unique_ptr<Room>. Resetting it inline ran ~Room() with
    // g_sessionMutex held, and ~Room tears down the LiveKit connection -- which
    // waits on SDK callbacks that dispatch back into this file, each of which
    // takes g_sessionMutex. The destructor was therefore waiting for a lock the
    // thread running it already held.
    //
    // That is the deadlock behind "disconnect from Orbit, connect again, and the
    // app stays on Connecting", and it is why ten rounds of fixes to CALLERS of
    // disconnect() never moved it:
    //
    //     disconnect step=bridge.disconnect atMs=3      (never returns)
    //
    // Every other fix removed a waiter -- the audio readers, the video readers --
    // and the Room destructor was waiting too.
    //
    // Moved out and destroyed on its own thread so the teardown's callbacks can
    // take the lock freely. g_room is nulled here, so no caller can reach the
    // half-destroyed object; only the destructor outlives this scope, and it
    // touches nothing else in this file.
    if (g_room != nullptr) {
        std::unique_ptr<livekit::Room> roomToDestroy = std::move(g_room);
        std::thread([room = std::move(roomToDestroy)]() mutable {
            try {
                room.reset();
            } catch (...) {
                // Nothing actionable: the room is going away regardless, and this
                // thread exists only so its destructor is not holding the mutex.
            }
        }).detach();
    }
    g_connected = false;
    g_micPublished = false;
    g_matrixRtcMemberId.clear();
    g_publishedAudioChannels = 0;
    g_expectedTestTone = false;
    g_firstAudioFrameAccepted = false;
    g_frameDroppedNotReadyLogged = false;
    g_audioPushDiagnosticsLogged = 0;
}

bool hasReadyMicLocked() {
    return g_connected &&
        g_micPublished &&
        g_acceptingFrames &&
        !g_rawDisconnecting &&
        g_room != nullptr &&
        g_audioSource != nullptr &&
        g_audioTrack != nullptr;
}

bool hasReadyVideoLocked() {
    return g_connected &&
        g_videoPublished &&
        g_acceptingVideoFrames &&
        !g_rawDisconnecting &&
        g_room != nullptr &&
        g_videoSource != nullptr &&
        g_videoTrack != nullptr;
}

void logFrameDroppedNotReadyLocked() {
    if (!g_frameDroppedNotReadyLogged) {
        g_frameDroppedNotReadyLogged = true;
        std::cout << "DEBUG [OrbitLiveKitAudio] frame dropped because native audio not ready" << std::endl;
    }
}

void logVideoFrameDroppedNotReadyLocked() {
    if (!g_videoFrameDroppedNotReadyLogged) {
        g_videoFrameDroppedNotReadyLogged = true;
        std::cout << "DEBUG [VideoPublish] frame dropped because native video not ready" << std::endl;
    }
}

constexpr jint kStatusOk = 0;
constexpr jint kStatusConnectFailed = 2;
constexpr jint kStatusPublishAudioFailed = 6;
constexpr jint kStatusPublishVideoFailed = 7;
constexpr jint kStatusAudioCaptureFailed = 8;
constexpr jint kStatusVideoCaptureFailed = 9;
constexpr jint kStatusNativeException = 22;
constexpr jint kStatusStaleEvent = 23;

bool g_rawHasLastError = false;
std::string g_rawLastErrorJson =
    R"({"code":"NONE","message":"","operation":"native","recoverable":false})";

std::string escapeJson(const std::string& input) {
    std::string output;
    output.reserve(input.size());
    for (const char c : input) {
        switch (c) {
            case '\\':
                output += "\\\\";
                break;
            case '"':
                output += "\\\"";
                break;
            case '\n':
                output += "\\n";
                break;
            case '\r':
                output += "\\r";
                break;
            case '\t':
                output += "\\t";
                break;
            default:
                // Raw control characters (including \0, \b, \f and 0x01-0x1F)
                // are illegal inside a JSON string, so the whole payload fails
                // to parse and the Kotlin side degrades to a generic UNKNOWN
                // error. Escape them as \u00XX.
                if (c < 0x20) {
                    static const char kHexDigits[] = "0123456789ABCDEF";
                    const auto byte = static_cast<unsigned char>(c);
                    output += "\\u00";
                    output += kHexDigits[(byte >> 4) & 0x0F];
                    output += kHexDigits[byte & 0x0F];
                } else {
                    output += c;
                }
                break;
        }
    }
    return output;
}

std::string trackSourceLabel(livekit::TrackSource source) {
    switch (source) {
        case livekit::TrackSource::SOURCE_CAMERA:
            return "camera";
        case livekit::TrackSource::SOURCE_MICROPHONE:
            return "microphone";
        case livekit::TrackSource::SOURCE_SCREENSHARE:
            return "screenshare";
        case livekit::TrackSource::SOURCE_SCREENSHARE_AUDIO:
            return "screenshare_audio";
        case livekit::TrackSource::SOURCE_UNKNOWN:
        default:
            return "unknown";
    }
}

std::string optionalTrackSourceLabel(const std::optional<livekit::TrackSource>& source) {
    return source.has_value() ? trackSourceLabel(source.value()) : "unset";
}

std::string trackKindLabel(livekit::TrackKind kind) {
    switch (kind) {
        case livekit::TrackKind::KIND_AUDIO:
            return "audio";
        case livekit::TrackKind::KIND_VIDEO:
            return "video";
        case livekit::TrackKind::KIND_UNKNOWN:
        default:
            return "unknown";
    }
}

std::string streamStateLabel(livekit::StreamState state) {
    switch (state) {
        case livekit::StreamState::STATE_ACTIVE:
            return "active";
        case livekit::StreamState::STATE_PAUSED:
            return "paused";
        case livekit::StreamState::STATE_UNKNOWN:
        default:
            return "unknown";
    }
}

void dumpLocalParticipantPublicationsLocked(const std::string& label) {
    if (g_room == nullptr || g_room->localParticipant() == nullptr) {
        std::cout << "DEBUG [VideoPublish] local publications dump label="
                  << label << " unavailable=no-local-participant" << std::endl;
        return;
    }

    const auto publications = g_room->localParticipant()->trackPublications();
    std::cout << "DEBUG [VideoPublish] local publications dump label="
              << label
              << " count=" << publications.size()
              << std::endl;
    for (const auto& entry : publications) {
        const auto& publication = entry.second;
        if (publication == nullptr) {
            std::cout << "DEBUG [VideoPublish] local publication sid="
                      << entry.first
                      << " null=true"
                      << std::endl;
            continue;
        }

        const auto track = publication->track();
        std::cout << "DEBUG [VideoPublish] local publication "
                  << "sid=" << publication->sid()
                  << " name=" << publication->name()
                  << " kind=" << trackKindLabel(publication->kind())
                  << " kindEnum=" << static_cast<int>(publication->kind())
                  << " source=" << trackSourceLabel(publication->source())
                  << " sourceEnum=" << static_cast<int>(publication->source())
                  << " muted=" << (publication->muted() ? "true" : "false")
                  << " width=" << publication->width()
                  << " height=" << publication->height()
                  << " trackSid=" << (track != nullptr ? track->sid() : std::string{"<none>"})
                  << " trackName=" << (track != nullptr ? track->name() : std::string{"<none>"})
                  << " trackKind=" << (track != nullptr ? trackKindLabel(track->kind()) : std::string{"unset"})
                  << " trackSource=" << (track != nullptr ? optionalTrackSourceLabel(track->source()) : std::string{"unset"})
                  << " trackMuted=" << (track != nullptr && track->muted() ? "true" : "false")
                  << " trackStreamState=" << (track != nullptr ? streamStateLabel(track->streamState()) : std::string{"unset"})
                  << " subscribed=<not exposed for local publication>"
                  << " enabled=<not exposed by LiveKit C++ SDK>"
                  << std::endl;
    }
}

std::string audioFeatureLabel(livekit::AudioTrackFeature feature) {
    switch (feature) {
        case livekit::AudioTrackFeature::TF_STEREO:
            return "stereo";
        case livekit::AudioTrackFeature::TF_NO_DTX:
            return "no_dtx";
        case livekit::AudioTrackFeature::TF_AUTO_GAIN_CONTROL:
            return "auto_gain_control";
        case livekit::AudioTrackFeature::TF_ECHO_CANCELLATION:
            return "echo_cancellation";
        case livekit::AudioTrackFeature::TF_NOISE_SUPPRESSION:
            return "noise_suppression";
        case livekit::AudioTrackFeature::TF_ENHANCED_NOISE_CANCELLATION:
            return "enhanced_noise_cancellation";
        case livekit::AudioTrackFeature::TF_PRECONNECT_BUFFER:
            return "preconnect_buffer";
        default:
            return "unknown";
    }
}

std::string audioFeaturesLabel(const std::vector<livekit::AudioTrackFeature>& features) {
    if (features.empty()) {
        return "[]";
    }
    std::string out = "[";
    for (std::size_t i = 0; i < features.size(); ++i) {
        if (i > 0) {
            out += ",";
        }
        out += audioFeatureLabel(features[i]);
    }
    out += "]";
    return out;
}

std::string encryptionTypeLabel(livekit::EncryptionType type) {
    switch (type) {
        case livekit::EncryptionType::NONE:
            return "none";
        case livekit::EncryptionType::GCM:
            return "gcm";
        case livekit::EncryptionType::CUSTOM:
            return "custom";
        default:
            return "unknown";
    }
}

std::string buildMatrixRtcParticipantMetadata(const std::string& identity) {
    std::string userId = identity;
    std::string deviceId;
    const auto lastColon = identity.rfind(':');
    if (lastColon != std::string::npos && lastColon + 1 < identity.size()) {
        userId = identity.substr(0, lastColon);
        deviceId = identity.substr(lastColon + 1);
    }

    return std::string("{\"user_id\":\"") + escapeJson(userId) +
        "\",\"device_id\":\"" + escapeJson(deviceId) +
        "\",\"member_id\":\"" + escapeJson(identity) + "\"}";
}

void clearRawErrorLocked() {
    g_rawHasLastError = false;
    g_rawLastErrorJson =
        R"({"code":"NONE","message":"","operation":"native","recoverable":false})";
}

void setRawErrorLocked(
    const std::string& code,
    const std::string& message,
    const std::string& operation,
    bool recoverable) {
    g_rawHasLastError = true;
    g_rawLastErrorJson =
        std::string("{\"code\":\"") + escapeJson(code) +
        "\",\"message\":\"" + escapeJson(message) +
        "\",\"operation\":\"" + escapeJson(operation) +
        "\",\"recoverable\":" + (recoverable ? "true" : "false") + "}";
}

void setRawErrorThreadSafe(
    const std::string& code,
    const std::string& message,
    const std::string& operation,
    bool recoverable) {
    std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
    setRawErrorLocked(code, message, operation, recoverable);
}

void dispatchNativeEvent(JNIEnv* env, jobject event) {
    if (env == nullptr || event == nullptr) {
        return;
    }

    jobject sink = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_eventSinkMutex);
        if (g_eventSink != nullptr && g_eventSinkMethod != nullptr) {
            sink = env->NewLocalRef(g_eventSink);
            method = g_eventSinkMethod;
        }
    }

    if (sink == nullptr || method == nullptr) {
        logCallbackSkipOnce("native-event", "sink missing");
        return;
    }

    env->CallVoidMethod(sink, method, event);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    env->DeleteLocalRef(sink);
}

void dispatchSimpleNativeEvent(JNIEnv* env, const char* className, jlong sessionId, jlong epoch) {
    if (env == nullptr) {
        return;
    }

    jclass eventClass = env->FindClass(className);
    if (eventClass == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        return;
    }

    jmethodID ctor = env->GetMethodID(eventClass, "<init>", "(JJ)V");
    if (ctor == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteLocalRef(eventClass);
        return;
    }

    jobject event = env->NewObject(eventClass, ctor, sessionId, epoch);
    if (event != nullptr) {
        dispatchNativeEvent(env, event);
        env->DeleteLocalRef(event);
    }
    env->DeleteLocalRef(eventClass);
}

jobject createNativeError(
    JNIEnv* env,
    const char* codeName,
    const std::string& message,
    const std::string& operation,
    bool recoverable) {
    if (env == nullptr) {
        return nullptr;
    }

    jclass errorCodeClass = env->FindClass("livekit/NativeErrorCode");
    jclass errorClass = env->FindClass("livekit/NativeError");
    if (errorCodeClass == nullptr || errorClass == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        return nullptr;
    }

    jfieldID codeField = env->GetStaticFieldID(errorCodeClass, codeName, "Llivekit/NativeErrorCode;");
    jmethodID ctor = env->GetMethodID(errorClass, "<init>", "(Llivekit/NativeErrorCode;Ljava/lang/String;Ljava/lang/String;Z)V");
    if (codeField == nullptr || ctor == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteLocalRef(errorCodeClass);
        env->DeleteLocalRef(errorClass);
        return nullptr;
    }

    jobject code = env->GetStaticObjectField(errorCodeClass, codeField);
    jstring jMessage = env->NewStringUTF(message.c_str());
    jstring jOperation = env->NewStringUTF(operation.c_str());
    jobject error = env->NewObject(errorClass, ctor, code, jMessage, jOperation, recoverable ? JNI_TRUE : JNI_FALSE);

    if (code != nullptr) env->DeleteLocalRef(code);
    if (jMessage != nullptr) env->DeleteLocalRef(jMessage);
    if (jOperation != nullptr) env->DeleteLocalRef(jOperation);
    env->DeleteLocalRef(errorCodeClass);
    env->DeleteLocalRef(errorClass);
    return error;
}

void dispatchErrorNativeEvent(
    JNIEnv* env,
    const char* eventClassName,
    const char* errorCodeName,
    jlong sessionId,
    jlong epoch,
    const std::string& message,
    const std::string& operation,
    bool recoverable) {
    if (env == nullptr) {
        return;
    }

    jobject error = createNativeError(env, errorCodeName, message, operation, recoverable);
    if (error == nullptr) {
        return;
    }

    jclass eventClass = env->FindClass(eventClassName);
    if (eventClass == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteLocalRef(error);
        return;
    }

    jmethodID ctor = env->GetMethodID(eventClass, "<init>", "(JJLlivekit/NativeError;)V");
    if (ctor == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteLocalRef(error);
        env->DeleteLocalRef(eventClass);
        return;
    }

    jobject event = env->NewObject(eventClass, ctor, sessionId, epoch, error);
    if (event != nullptr) {
        dispatchNativeEvent(env, event);
        env->DeleteLocalRef(event);
    }
    env->DeleteLocalRef(error);
    env->DeleteLocalRef(eventClass);
}

void dispatchDisconnectedEvent(
    JNIEnv* env,
    jlong sessionId,
    jlong epoch,
    const std::string& reason,
    bool recoverable) {
    if (env == nullptr) {
        return;
    }

    jclass eventClass = env->FindClass("livekit/NativeMediaEvent$Disconnected");
    if (eventClass == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        return;
    }

    jmethodID ctor = env->GetMethodID(eventClass, "<init>", "(JJLjava/lang/String;Z)V");
    if (ctor == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteLocalRef(eventClass);
        return;
    }

    jstring jReason = env->NewStringUTF(reason.c_str());
    jobject event = env->NewObject(eventClass, ctor, sessionId, epoch, jReason, recoverable ? JNI_TRUE : JNI_FALSE);
    if (event != nullptr) {
        dispatchNativeEvent(env, event);
        env->DeleteLocalRef(event);
    }
    if (jReason != nullptr) env->DeleteLocalRef(jReason);
    env->DeleteLocalRef(eventClass);
}

bool isRawSessionValid(jlong sessionId) {
    return sessionId == kRawSessionId;
}

std::string rawReadinessJsonLocked(jlong sessionId) {
    const bool validSession = isRawSessionValid(sessionId);
    const bool connected = validSession && g_connected;
    const bool audioPublished = validSession && g_micPublished;
    const bool videoPublished = validSession && g_videoPublished;
    const bool acceptingAudioFrames = validSession && hasReadyMicLocked();
    const bool acceptingVideoFrames = validSession && hasReadyVideoLocked();
    const char* state = "Idle";
    if (!validSession) {
        state = "Failed";
    } else if (connected && audioPublished && acceptingAudioFrames) {
        state = "MediaReady";
    } else if (connected && audioPublished) {
        state = "AudioPublished";
    } else if (connected) {
        state = "LiveKitConnected";
    }

    const char* cameraState = "NotRequested";
    if (videoPublished) {
        cameraState = "Published";
    } else if (g_cameraRequested) {
        cameraState = "Publishing";
    }

    return std::string("{\"sessionId\":") + std::to_string(sessionId) +
        ",\"epoch\":" + std::to_string(g_rawEpoch) +
        ",\"state\":\"" + state +
        "\",\"connected\":" + (connected ? "true" : "false") +
        ",\"audioPublished\":" + (audioPublished ? "true" : "false") +
        ",\"videoPublished\":" + (videoPublished ? "true" : "false") +
        ",\"acceptingAudioFrames\":" + (acceptingAudioFrames ? "true" : "false") +
        ",\"acceptingVideoFrames\":" + (acceptingVideoFrames ? "true" : "false") +
        ",\"cameraRequested\":" + (g_cameraRequested ? "true" : "false") +
        ",\"cameraRequired\":false" +
        ",\"cameraState\":\"" + cameraState + "\"" +
        ",\"joinedAsAudioOnlyFallback\":false" +
        ",\"tokenRefreshScheduled\":false" +
        ",\"lastError\":" + (g_rawHasLastError ? g_rawLastErrorJson : "null") +
        "}";
}

bool connectRoom(
    const std::string& urlString,
    const std::string& tokenString,
    const std::string& requestedIdentity,
    std::string* errorMessage) {
    if (urlString.empty() || tokenString.empty()) {
        if (errorMessage != nullptr) {
            *errorMessage = "Missing LiveKit URL or token";
        }
        std::cerr << "ERROR [OrbitLiveKitAudio] Missing LiveKit URL or token" << std::endl;
        return false;
    }

    try {
        initializeSdkOnce();

        // SessionLock, not lock_guard: this block starts readers and announces
        // participants, all of which cross into Java. The upcalls are deferred
        // and flushed by ~SessionLock once the mutex is free.
        SessionLock lock;
        bool hasMixedAudioSink = false;
        resetRoomLocked();
        g_matrixRtcMemberId = requestedIdentity;
        std::cout << "DEBUG [OrbitLiveKitAudio] native connect started" << std::endl;

        g_room = std::make_unique<livekit::Room>();
        g_delegate = std::make_unique<OrbitAudioRoomDelegate>();
        g_room->setDelegate(g_delegate.get());
        livekit::RoomOptions options;
        options.auto_subscribe = true;
        options.dynacast = false;
        if (g_e2eeRequested.load(std::memory_order_relaxed)) {
            livekit::E2EEOptions e2eeOptions;
            e2eeOptions.encryption_type = livekit::EncryptionType::GCM;
            // Element Call's JS worker always derives frame keys via HKDF-SHA256;
            // the native default is PBKDF2 which would make frames undecryptable.
            e2eeOptions.key_provider_options.key_derivation_function = livekit::KeyDerivationFunction::HKDF;
            e2eeOptions.key_provider_options.ratchet_window_size = 10;
            e2eeOptions.key_provider_options.key_ring_size = 256;
            options.encryption = e2eeOptions;
            std::cout << "DEBUG [OrbitLiveKitAudio] E2EE room options configured type=GCM kdf=HKDF" << std::endl;
        }

        g_connected = g_room->connect(urlString, tokenString, options);
        if (!g_connected) {
            resetRoomLocked();
            if (errorMessage != nullptr) {
                *errorMessage = "LiveKit C++ room connect failed";
            }
            std::cerr << "ERROR [OrbitLiveKitAudio] LiveKit C++ room connect failed" << std::endl;
            return false;
        }

        if (g_e2eeRequested.load(std::memory_order_relaxed)) {
            auto* e2eeManager = g_room->e2eeManager();
            if (e2eeManager == nullptr) {
                // SECURITY: fail closed. This branch used to log a warning and fall
                // through, so a connect that could not honour the E2EE request
                // proceeded and published unencrypted media at the SFU -- silently,
                // because the warning went to std::cerr, which the packaged GUI build
                // discards. The Kotlin side already refuses to join when it cannot
                // establish that the room is encrypted (RoomEncryptionVerdict), so
                // reaching here means we asked for E2EE and the SDK could not give
                // us one: the only safe move is to refuse the connect.
                //
                // resetRoomLocked() tears down g_room/g_delegate, so the session does
                // not linger half-connected.
                std::cerr << "ERROR [OrbitLiveKitAudio] E2EE requested but the room has no e2ee "
                             "manager; refusing to connect rather than publish unencrypted"
                          << std::endl;
                resetRoomLocked();
                if (errorMessage != nullptr) {
                    *errorMessage =
                        "This call needs end-to-end encryption but the media engine could not "
                        "provide it, so nothing was published.";
                }
                return false;
            }
            e2eeManager->setEnabled(true);
            std::cout << "DEBUG [OrbitLiveKitAudio] E2EE manager enabled enabled="
                      << (e2eeManager->enabled() ? "true" : "false")
                      << " keyProvider=" << (e2eeManager->keyProvider() != nullptr ? "present" : "MISSING")
                      << std::endl;
            if (e2eeManager->keyProvider() == nullptr) {
                // Same reasoning: enabled() without a key provider cannot encrypt
                // anything, so treat it as a failure to honour the request.
                std::cerr << "ERROR [OrbitLiveKitAudio] E2EE manager has no key provider; "
                             "refusing to connect rather than publish unencrypted"
                          << std::endl;
                resetRoomLocked();
                if (errorMessage != nullptr) {
                    *errorMessage =
                        "This call needs end-to-end encryption but no media keys could be "
                        "installed, so nothing was published.";
                }
                return false;
            }
            for (const auto& [identity, keyInfo] : g_participantMediaKeys) {
                e2eeManager->keyProvider()->setKey(identity, keyInfo.first, keyInfo.second);
                std::cout << "DEBUG [OrbitLiveKitAudio] e2ee flushed cached key identity=" << identity
                          << " keyIndex=" << keyInfo.second
                          << " keyLen=" << keyInfo.first.size() << std::endl;
            }
        }

        auto* localParticipant = g_room->localParticipant();
        if (localParticipant != nullptr) {
            std::cout << "DEBUG [OrbitLiveKitAudio] local participant connected "
                      << "requestedIdentity=" << (requestedIdentity.empty() ? std::string{"<none>"} : requestedIdentity)
                      << " identity=" << localParticipant->identity()
                      << " sid=" << localParticipant->sid()
                      << " name=" << localParticipant->name()
                      << " metadata=" << (localParticipant->metadata().empty() ? std::string{"<empty>"} : localParticipant->metadata())
                      << std::endl;
            std::cout << "DEBUG [MatrixRTCMapping] native connect tokenRequestMemberId="
                      << (requestedIdentity.empty() ? std::string{"<none>"} : requestedIdentity)
                      << " livekitLocalParticipantIdentity=" << localParticipant->identity()
                      << " livekitParticipantSid=" << localParticipant->sid()
                      << std::endl;

            const auto metadataMemberId = !requestedIdentity.empty()
                ? requestedIdentity
                : localParticipant->identity();
            if (localParticipant->metadata().empty() && !metadataMemberId.empty()) {
                const auto metadata = buildMatrixRtcParticipantMetadata(metadataMemberId);
                std::cout << "DEBUG [OrbitLiveKitAudio] setting local participant metadata: "
                          << metadata << std::endl;
                std::cout << "DEBUG [MatrixRTCMapping] native participant metadata member_id="
                          << metadataMemberId
                          << " livekitLocalParticipantIdentity=" << localParticipant->identity()
                          << std::endl;
                try {
                    localParticipant->setMetadata(metadata);
                } catch (const std::exception& error) {
                    std::cout << "WARN [OrbitLiveKitAudio] local participant metadata update failed: "
                              << error.what() << std::endl;
                } catch (...) {
                    std::cout << "WARN [OrbitLiveKitAudio] local participant metadata update failed: unknown error" << std::endl;
                }
            }
        }

        {
            // Snapshot under the sink's own mutex: g_mixedAudioSink is written
            // by nativeSetMixedAudioSink on the JVM thread.
            std::lock_guard<std::mutex> sinkLock(g_mixedAudioSinkMutex);
            hasMixedAudioSink = g_mixedAudioSink != nullptr;
        }
        if (hasMixedAudioSink) {
            g_audioMixer.start();
        }

        for (const auto& participant : g_room->remoteParticipants()) {
            if (participant == nullptr) {
                continue;
            }
            {
                const std::string identity = participant->identity();
                const std::string sid = participant->sid();
                deferDispatchIfSessionLockHeld([identity, sid] { dispatchRemoteParticipantConnected(identity, sid); });
            }
            for (const auto& publicationEntry : participant->trackPublications()) {
                const auto& publication = publicationEntry.second;
                if (publication == nullptr || !publication->subscribed()) {
                    continue;
                }
                const auto track = publication->track();
                if (publication->kind() == livekit::TrackKind::KIND_VIDEO) {
                    logRemoteVideoPublicationDetected(participant.get(), publication, track, "existing");
                    startRemoteVideoReaderLocked(track, participant.get(), publication);
                    continue;
                }
                const auto trackSource = track != nullptr ? track->source() : std::optional<livekit::TrackSource>{};
                std::cout << "DEBUG [OrbitLiveKitAudio] existing remote audio publication "
                          << "participantIdentity=" << participant->identity()
                          << " participantName=" << participant->name()
                          << " participantMetadata=" << participant->metadata()
                          << " remoteTrackSid=" << (track != nullptr ? track->sid() : std::string{"<none>"})
                          << " remoteTrackName=" << (track != nullptr ? track->name() : std::string{"<none>"})
                          << " remoteTrackKind=" << (track != nullptr ? std::to_string(static_cast<int>(track->kind())) : std::string{"unset"})
                          << " remoteTrackSourceEnum=" << (trackSource.has_value() ? std::to_string(static_cast<int>(trackSource.value())) : "unset")
                          << " remoteTrackSourceLabel=" << optionalTrackSourceLabel(trackSource)
                          << " remotePublicationSid=" << publication->sid()
                          << " remotePublicationName=" << publication->name()
                          << " remotePublicationKind=" << static_cast<int>(publication->kind())
                          << " remotePublicationSourceEnum=" << static_cast<int>(publication->source())
                          << " remotePublicationSourceLabel=" << trackSourceLabel(publication->source())
                          << " remotePublicationMuted=" << (publication->muted() ? "true" : "false")
                          << " remotePublicationEncryption=" << encryptionTypeLabel(publication->encryptionType())
                          << " remotePublicationAudioFeatures=" << audioFeaturesLabel(publication->audioFeatures())
                          << std::endl;
                startRemoteReaderLocked(publication->track(), participant->identity());
            }
        }

        std::cout << "DEBUG [OrbitLiveKitAudio] native connect succeeded" << std::endl;
        return true;
    } catch (const std::exception& error) {
        // SessionLock, not lock_guard: resetRoomLocked() tears readers down,
        // which publishes per-track removals into Java. With a plain guard the
        // mutex is still held during that upcall, and the Kotlin handler calls
        // back into JNI functions that take the same non-recursive mutex.
        SessionLock lock;
        resetRoomLocked();
        if (errorMessage != nullptr) {
            *errorMessage = std::string("Connect threw: ") + error.what();
        }
        std::cerr << "ERROR [OrbitLiveKitAudio] Connect threw: " << error.what() << std::endl;
        return false;
    } catch (...) {
        SessionLock lock;
        resetRoomLocked();
        if (errorMessage != nullptr) {
            *errorMessage = "Connect threw unknown error";
        }
        std::cerr << "ERROR [OrbitLiveKitAudio] Connect threw unknown error" << std::endl;
        return false;
    }
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativeSdkVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("livekit-cpp-sdk-0.4.1");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativeConnect(JNIEnv* env, jclass, jstring url, jstring token) {
    g_sessionDestroyed = false;
    g_callbackShutdown = false;
    const std::string urlString = jStringToString(env, url);
    const std::string tokenString = jStringToString(env, token);
    std::string errorMessage;
    return connectRoom(urlString, tokenString, "", &errorMessage) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativeDisconnect(JNIEnv*, jclass) {
    g_callbackShutdown = true;
    // Before resetRoomLocked, which clears g_audioTrack: the TXSTAT thread holds a
    // shared_ptr to it and calls getStats() on it, so clearing first would leave
    // the thread querying a half-torn-down track.
    stopTxStats();
    g_audioMixer.stop();
    // SessionLock: resetRoomLocked publishes per-track removals into Java,
    // which must not happen with g_sessionMutex held.
    SessionLock lock;
    g_acceptingFrames = false;
    resetRoomLocked();
    {
        std::lock_guard<std::mutex> announcedLock(g_announcedParticipantsMutex);
        g_announcedParticipantSids.clear();
    }
    std::cout << "DEBUG [OrbitLiveKitAudio] disconnect cleanup completed" << std::endl;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativePublishAudio(
    JNIEnv*, jclass, jint sampleRate, jint channels, jint frameMs, jint queueSizeMs, jboolean expectedTestTone, jint maxBitrateBps) {
    std::cout << "DEBUG [OrbitLiveKitAudio] mic publish started" << std::endl;
    if (sampleRate != kSampleRate ||
        !isSupportedAudioChannels(channels) ||
        frameMs != kFrameMs ||
        !isSupportedAudioSourceQueueMs(queueSizeMs)) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        g_acceptingFrames = false;
        g_micPublished = false;
        g_publishedAudioChannels = 0;
        g_expectedTestTone = false;
        std::cerr << "ERROR [OrbitLiveKitAudio] Invalid audio publish format; expected 48000 Hz, 1 or 2 channels, 10 ms, queueSizeMs 0 or 10" << std::endl;
        return JNI_FALSE;
    }

    try {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        g_acceptingFrames = false;
        g_micPublished = false;
        g_publishedAudioChannels = 0;
        g_expectedTestTone = expectedTestTone == JNI_TRUE;
        g_firstAudioFrameAccepted = false;
        g_frameDroppedNotReadyLogged = false;
        g_audioPushDiagnosticsLogged = 0;

        if (!g_connected || g_room == nullptr || g_room->localParticipant() == nullptr) {
            std::cerr << "ERROR [OrbitLiveKitAudio] Cannot publish audio before room connect" << std::endl;
            return JNI_FALSE;
        }

        g_audioSourceWorker.stop();
        g_audioTrack.reset();
        g_audioSource = std::make_shared<livekit::AudioSource>(
            static_cast<int>(sampleRate),
            static_cast<int>(channels),
            static_cast<int>(queueSizeMs));
        std::cout << "DEBUG [OrbitLiveKitAudio] AudioSource(sampleRate="
                  << g_audioSource->sampleRate()
                  << ", channels=" << g_audioSource->numChannels()
                  << ", queueSizeMs=" << queueSizeMs
                  << ")" << std::endl;
        std::cout << "DEBUG [OrbitLiveKitAudio] AudioSource created sampleRate="
                  << g_audioSource->sampleRate()
                  << " channels=" << g_audioSource->numChannels()
                  << " queueSizeMs=" << queueSizeMs
                  << " frameMs=" << frameMs
                  << " samplesPerChannel=" << kSamplesPerChannel
                  << " bytesPerFrame=" << audioFrameBytes(channels)
                  << std::endl;
        int effectiveMaxBitrateBps = maxBitrateBps > 0 ? static_cast<int>(maxBitrateBps) : kDefaultAudioMaxBitrateBps;
        if (effectiveMaxBitrateBps < kMinAudioMaxBitrateBps) effectiveMaxBitrateBps = kMinAudioMaxBitrateBps;
        if (effectiveMaxBitrateBps > kMaxAudioMaxBitrateBps) effectiveMaxBitrateBps = kMaxAudioMaxBitrateBps;
        livekit::TrackPublishOptions publishOptions;
        publishOptions.source = livekit::TrackSource::SOURCE_MICROPHONE;
        publishOptions.dtx = true;
        // RED doesn't work with frame encryption (see livekit-client options.ts);
        // Element publishes with red=false in E2EE rooms, so mirror that.
        const bool e2eeActive = g_e2eeRequested.load(std::memory_order_relaxed);
        publishOptions.red = !e2eeActive;
        livekit::AudioEncodingOptions audioEncoding;
        audioEncoding.max_bitrate = static_cast<std::uint64_t>(effectiveMaxBitrateBps);
        publishOptions.audio_encoding = audioEncoding;

        std::cout << "DEBUG [OrbitLiveKitAudio] audio publish options: "
                  << "trackName=audio"
                  << " trackKind=" << static_cast<int>(livekit::TrackKind::KIND_AUDIO)
                  << " trackSourceEnum=" << static_cast<int>(livekit::TrackSource::SOURCE_MICROPHONE)
                  << " trackSourceLabel=" << trackSourceLabel(livekit::TrackSource::SOURCE_MICROPHONE)
                  << " source=SOURCE_MICROPHONE"
                  << " channels=" << channels
                  << " maxBitrateBps=" << effectiveMaxBitrateBps
                  << " dtx=true"
                  << " red=" << (e2eeActive ? "false" : "true")
                  << " expectedTestTone=" << (g_expectedTestTone.load(std::memory_order_relaxed) ? "true" : "false")
                  << std::endl;

        g_audioTrack = livekit::LocalAudioTrack::createLocalAudioTrack("audio", g_audioSource);
        if (g_audioTrack == nullptr) {
            g_audioSource.reset();
            std::cerr << "ERROR [OrbitLiveKitAudio] Native C++ microphone track creation failed" << std::endl;
            return JNI_FALSE;
        }
        g_room->localParticipant()->publishTrack(g_audioTrack, publishOptions);

        const auto publication = g_audioTrack->publication();
        if (g_audioSource == nullptr || g_audioTrack == nullptr || publication == nullptr) {
            g_audioTrack.reset();
            g_audioSource.reset();
            std::cerr << "ERROR [OrbitLiveKitAudio] Native C++ microphone publish returned no publication" << std::endl;
            return JNI_FALSE;
        }

        if (g_audioTrack->muted()) {
            g_audioTrack->unmute();
        }
        g_micTrackMutedCache.store(false, std::memory_order_relaxed);
        g_audioSourceWorker.start(g_audioSource);
        g_micPublished = true;
        g_acceptingFrames = true;
        g_publishedAudioChannels = static_cast<int>(channels);
        std::cout << "DEBUG [OrbitLiveKitAudio] mic publish succeeded" << std::endl;
        // TX telemetry thread: see logTxStatsIfDue for why the sender needs its own.
        g_txStatsRunning.store(true, std::memory_order_relaxed);
        // Same rule as the four sites already fixed, and this one had not been
        // looked at because it is not on the teardown path -- it is on the PUBLISH
        // path, so it stalls the next call rather than the hangup. A caller that
        // re-publishes the mic (a mute round-trip, a device change) blocks here
        // forever, waiting on a TXSTAT thread that is itself blocked on
        // g_sessionMutex, which this function's caller holds.
        retireTxStatsThread();
        g_txStatsThread = std::thread([] { logTxStatsIfDue(); });
        const auto trackSource = g_audioTrack->source();
        std::cout << "DEBUG [OrbitLiveKitAudio] local microphone track details "
                  << "trackName=" << g_audioTrack->name()
                  << " trackKind=" << static_cast<int>(g_audioTrack->kind())
                  << " trackSourceEnum=" << (trackSource.has_value() ? std::to_string(static_cast<int>(trackSource.value())) : "unset")
                  << " trackSourceLabel=" << optionalTrackSourceLabel(trackSource)
                  << " publicationSourceEnum=" << static_cast<int>(publication->source())
                  << " publicationSourceLabel=" << trackSourceLabel(publication->source())
                  << " publicationSid=" << publication->sid()
                  << " publicationName=" << publication->name()
                  << " publicationMuted=" << (publication->muted() ? "true" : "false")
                  << " publicationEncryption=" << encryptionTypeLabel(publication->encryptionType())
                  << " publicationAudioFeatures=" << audioFeaturesLabel(publication->audioFeatures())
                  << " localTrackSid=" << g_audioTrack->sid()
                  << " localTrackMuted=" << (g_audioTrack->muted() ? "true" : "false")
                  << " trackMetadata=<not exposed by LiveKit C++ SDK>"
                  << std::endl;
        std::cout << "DEBUG [MatrixRTCMapping] native audio published tokenRequestMemberId="
                  << (g_matrixRtcMemberId.empty() ? std::string{"<none>"} : g_matrixRtcMemberId)
                  << " livekitLocalParticipantIdentity="
                  << (g_room->localParticipant() != nullptr ? g_room->localParticipant()->identity() : std::string{"<none>"})
                  << " livekitParticipantSid="
                  << (g_room->localParticipant() != nullptr ? g_room->localParticipant()->sid() : std::string{"<none>"})
                  << " publishedTrackSid=" << publication->sid()
                  << " publishedTrackName=" << publication->name()
                  << " publishedTrackSource=" << trackSourceLabel(publication->source())
                  << std::endl;
        std::cout << "DEBUG [OrbitLiveKitAudio] acceptingFrames=true" << std::endl;
        return JNI_TRUE;
    } catch (const std::exception& error) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        g_acceptingFrames = false;
        g_micPublished = false;
        g_publishedAudioChannels = 0;
        g_expectedTestTone = false;
        g_audioSourceWorker.stop();
        g_audioTrack.reset();
        g_audioSource.reset();
        std::cerr << "ERROR [OrbitLiveKitAudio] Publish audio threw: " << error.what() << std::endl;
        return JNI_FALSE;
    } catch (...) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        g_acceptingFrames = false;
        g_micPublished = false;
        g_publishedAudioChannels = 0;
        g_expectedTestTone = false;
        g_audioSourceWorker.stop();
        g_audioTrack.reset();
        g_audioSource.reset();
        std::cerr << "ERROR [OrbitLiveKitAudio] Publish audio threw unknown error" << std::endl;
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativePushAudioFrame(
    JNIEnv* env,
    jclass,
    jobject pcm,
    jint byteLength,
    jint sampleRate,
    jint channels,
    jint samplesPerChannel) {
    if (pcm == nullptr) {
        return JNI_FALSE;
    }

    if (byteLength != audioFrameBytes(channels, samplesPerChannel) ||
        sampleRate != kSampleRate ||
        !isSupportedAudioChannels(channels) ||
        samplesPerChannel != kSamplesPerChannel) {
        return JNI_FALSE;
    }

    bool acceptingAudioFrames = false;
    bool nativeTrackMuted = false;
    {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        if (!hasReadyMicLocked()) {
            logFrameDroppedNotReadyLocked();
            return JNI_FALSE;
        }
        if (g_publishedAudioChannels != channels) {
            std::cerr << "ERROR [OrbitLiveKitAudio] Audio frame channels do not match published AudioSource channels frameChannels="
                      << channels << " publishedChannels=" << g_publishedAudioChannels << std::endl;
            return JNI_FALSE;
        }
        acceptingAudioFrames = hasReadyMicLocked();
        nativeTrackMuted = g_audioTrack != nullptr && g_audioTrack->muted();
    }

    void* data = env->GetDirectBufferAddress(pcm);
    if (data == nullptr) {
        std::cerr << "ERROR [OrbitLiveKitAudio] Audio frame buffer must be direct" << std::endl;
        return JNI_FALSE;
    }

    const int expectedBytes = samplesPerChannel * channels * static_cast<jint>(sizeof(std::int16_t));

    // Same capacity check the video push paths do: without it a caller-supplied
    // direct buffer that is smaller than a frame makes the memcpy below read
    // past the end of the native allocation.
    const jlong capacity = env->GetDirectBufferCapacity(pcm);
    if (capacity < static_cast<jlong>(expectedBytes)) {
        std::cerr << "ERROR [OrbitLiveKitAudio] Audio frame buffer is smaller than one frame capacity="
                  << capacity << " expectedBytes=" << expectedBytes << std::endl;
        return JNI_FALSE;
    }

    try {
        std::vector<std::int16_t> samples(static_cast<std::size_t>(expectedBytes / sizeof(std::int16_t)));
        std::memcpy(samples.data(), data, static_cast<std::size_t>(expectedBytes));

        {
            std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
            if (g_audioPushDiagnosticsLogged < 10) {
                const auto stats = computePcmDiagnostics(samples.data(), samples.size());
                g_audioPushDiagnosticsLogged += 1;
                std::cout << "DEBUG [OrbitLiveKitAudio] pushed mic frame #"
                          << g_audioPushDiagnosticsLogged
                          << " min=" << stats.min
                          << " max=" << stats.max
                          << " rms=" << stats.rms
                          << " peak=" << stats.peak
                          << " allZero=" << (stats.allZero ? "true" : "false")
                          << " sampleRate=" << sampleRate
                          << " channels=" << channels
                          << " samplesPerChannel=" << samplesPerChannel
                          << " acceptingAudioFrames=" << (acceptingAudioFrames ? "true" : "false")
                          << " muted=" << (nativeTrackMuted ? "true" : "false")
                          << " nativeTrackMuted=" << (nativeTrackMuted ? "true" : "false")
                          << " first10Samples=[";
                for (std::size_t i = 0; i < stats.firstSamples.size(); ++i) {
                    if (i > 0) {
                        std::cout << ",";
                    }
                    std::cout << stats.firstSamples[i];
                }
                std::cout << "]" << std::endl;
                if (stats.allZero) {
                    std::cout << "DEBUG [OrbitLiveKitAudio] pushed mic frame is silence" << std::endl;
                } else {
                    std::cout << "DEBUG [OrbitLiveKitAudio] pushed mic frame RMS > 0" << std::endl;
                }
            }
        }

        const auto timestampNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        if (!g_audioSourceWorker.enqueue(
                std::move(samples),
                static_cast<int>(sampleRate),
                static_cast<int>(channels),
                static_cast<int>(samplesPerChannel),
                static_cast<std::int64_t>(timestampNs))) {
            return JNI_FALSE;
        }
        return JNI_TRUE;
    } catch (const std::exception& error) {
        std::cerr << "ERROR [OrbitLiveKitAudio] Push audio frame threw: " << error.what() << std::endl;
        return JNI_FALSE;
    } catch (...) {
        std::cerr << "ERROR [OrbitLiveKitAudio] Push audio frame threw unknown error" << std::endl;
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativeIsAcceptingFrames(JNIEnv*, jclass) {
    std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
    return hasReadyMicLocked() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativeSetAcceptingFrames(JNIEnv*, jclass, jboolean accepting) {
    std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
    g_acceptingFrames = accepting == JNI_TRUE && g_connected && g_micPublished && g_audioSource != nullptr && g_audioTrack != nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativeSetParticipantGain(JNIEnv* env, jclass, jstring identity, jfloat gain) {
    if (identity == nullptr) {
        return;
    }
    // A C++ exception crossing an extern "C" boundary is UB and, on MSVC,
    // terminates the JVM. setParticipantGainEverywhere allocates std::strings
    // and takes several mutexes, so guard the whole body.
    try {
        const std::string identityUtf = jStringToString(env, identity);
        setParticipantGainEverywhere(identityUtf, static_cast<float>(gain));
    } catch (const std::exception& error) {
        setRawErrorThreadSafe(
            "NATIVE_EXCEPTION",
            std::string("Set participant gain failed: ") + error.what(),
            "nativeSetParticipantGain",
            true);
        std::cerr << "ERROR [OrbitLiveKitAudio] nativeSetParticipantGain threw: "
                  << error.what() << std::endl;
    } catch (...) {
        setRawErrorThreadSafe(
            "NATIVE_EXCEPTION",
            "Set participant gain failed",
            "nativeSetParticipantGain",
            true);
        std::cerr << "ERROR [OrbitLiveKitAudio] nativeSetParticipantGain threw unknown error" << std::endl;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativeSetRemoteAudioSink(JNIEnv* env, jclass, jobject sink) {
    if (g_javaVm == nullptr) {
        env->GetJavaVM(&g_javaVm);
    }

    std::lock_guard<std::mutex> lock(g_sinkMutex);
    if (g_remoteAudioSink != nullptr) {
        env->DeleteGlobalRef(g_remoteAudioSink);
        g_remoteAudioSink = nullptr;
        g_remoteAudioSinkMethod = nullptr;
    }

    if (sink == nullptr) {
        return;
    }

    jobject newSink = env->NewGlobalRef(sink);
    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID method = env->GetMethodID(sinkClass, "onAudioFrame", "([BIII)V");
    env->DeleteLocalRef(sinkClass);

    if (method == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteGlobalRef(newSink);
        std::cerr << "ERROR [OrbitLiveKitAudio] RemoteAudioSink.onAudioFrame not found" << std::endl;
        return;
    }

    g_remoteAudioSink = newSink;
    g_remoteAudioSinkMethod = method;
}

extern "C" JNIEXPORT jlong JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeCreateSession(JNIEnv*, jclass) {
    initializeSdkOnce();
    g_sessionDestroyed = false;
    g_callbackShutdown = false;
    std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
    setRawEpochLocked(g_rawEpoch + 1);
    g_rawDisconnecting = false;
    clearRawErrorLocked();
    return kRawSessionId;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeDestroySession(JNIEnv* env, jclass, jlong sessionId) {
    if (!isRawSessionValid(sessionId)) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeDestroySession", false);
        return kStatusStaleEvent;
    }

    g_callbackShutdown = true;
    g_sessionDestroyed = true;
    {
        // SessionLock: stopping the video readers publishes removals into Java,
        // which must not run with g_sessionMutex held.
        SessionLock lock;
        g_rawDisconnecting = true;
        g_acceptingFrames = false;
        if (g_room != nullptr) {
            g_room->setDelegate(nullptr);
        }
        stopAllRemoteReadersLocked();
        stopAllRemoteVideoReadersLocked("destroy-session");
    }
    g_audioMixer.stop();
    {
        std::lock_guard<std::mutex> lock(g_eventSinkMutex);
        if (g_eventSink != nullptr) {
            env->DeleteGlobalRef(g_eventSink);
            g_eventSink = nullptr;
            g_eventSinkMethod = nullptr;
        }
    }
    {
        std::lock_guard<std::mutex> lock(g_mixedAudioSinkMutex);
        if (g_mixedAudioSink != nullptr) {
            env->DeleteGlobalRef(g_mixedAudioSink);
            g_mixedAudioSink = nullptr;
            g_mixedAudioSinkMethod = nullptr;
        }
    }
    {
        std::lock_guard<std::mutex> lock(g_sinkMutex);
        if (g_remoteAudioSink != nullptr) {
            env->DeleteGlobalRef(g_remoteAudioSink);
            g_remoteAudioSink = nullptr;
            g_remoteAudioSinkMethod = nullptr;
        }
    }
    {
        std::lock_guard<std::mutex> lock(g_remoteVideoSinkMutex);
        if (g_remoteVideoSink != nullptr) {
            env->DeleteGlobalRef(g_remoteVideoSink);
            g_remoteVideoSink = nullptr;
            g_remoteParticipantConnectedMethod = nullptr;
            g_remoteParticipantDisconnectedMethod = nullptr;
            g_remoteAudioMuteChangedMethod = nullptr;
            g_remoteConnectionQualityMethod = nullptr;
            g_remoteVideoTrackAddedMethod = nullptr;
            g_remoteVideoFrameMethod = nullptr;
            g_remoteVideoTrackRemovedMethod = nullptr;
        }
    }
    std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
    resetRoomLocked();
    // Real teardown, so the caches go here rather than in resetRoomLocked():
    // connectRoom() reuses that to replay the pre-connect key cache into a
    // fresh E2EE manager.
    g_participantMediaKeys.clear();
    // Our transmit key is gone with the cache. Leaving the old index in place
    // would make TXSTAT keep claiming a key we are no longer encrypting with.
    g_ownKeyIdentity.clear();
    g_ownKeyIndex = -1;
    g_ownKeyFingerprint = 0;
    {
        std::lock_guard<std::mutex> gainLock(g_participantGainsMutex);
        g_participantGains.clear();
    }
    clearRawErrorLocked();
    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeSetEventSink(JNIEnv* env, jclass, jobject sink) {
    if (g_javaVm == nullptr) {
        env->GetJavaVM(&g_javaVm);
    }

    {
        std::lock_guard<std::mutex> lock(g_eventSinkMutex);
        if (g_eventSink != nullptr) {
            env->DeleteGlobalRef(g_eventSink);
            g_eventSink = nullptr;
            g_eventSinkMethod = nullptr;
        }

        if (sink != nullptr) {
            jobject newSink = env->NewGlobalRef(sink);
            jclass sinkClass = env->GetObjectClass(sink);
            jmethodID method = env->GetMethodID(sinkClass, "onNativeEvent", "(Llivekit/NativeMediaEvent;)V");
            env->DeleteLocalRef(sinkClass);

            if (method == nullptr) {
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                }
                env->DeleteGlobalRef(newSink);
                std::lock_guard<std::timed_mutex> sessionLock(g_sessionMutex);
                setRawErrorLocked("NATIVE_EXCEPTION", "NativeMediaEventSink.onNativeEvent not found", "nativeSetEventSink", false);
                return kStatusNativeException;
            }

            g_eventSink = newSink;
            g_eventSinkMethod = method;
        }
    }

    std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
    clearRawErrorLocked();
    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeSetMixedAudioSink(JNIEnv* env, jclass, jobject sink) {
    if (g_javaVm == nullptr) {
        env->GetJavaVM(&g_javaVm);
    }

    if (sink == nullptr) {
        {
            std::lock_guard<std::mutex> lock(g_mixedAudioSinkMutex);
            if (g_mixedAudioSink != nullptr) {
                env->DeleteGlobalRef(g_mixedAudioSink);
                g_mixedAudioSink = nullptr;
            }
            g_mixedAudioSinkMethod = nullptr;
        }
        g_audioMixer.stop();
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        clearRawErrorLocked();
        return kStatusOk;
    }

    jobject newSink = env->NewGlobalRef(sink);
    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID method = env->GetMethodID(
        sinkClass,
        "onMixedAudioFrame",
        "(JJ[BIIIJ)V");
    env->DeleteLocalRef(sinkClass);

    if (method == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteGlobalRef(newSink);
        std::lock_guard<std::timed_mutex> sessionLock(g_sessionMutex);
        setRawErrorLocked("NATIVE_EXCEPTION", "NativeMixedAudioSink.onMixedAudioFrame not found", "nativeSetMixedAudioSink", false);
        return kStatusNativeException;
    }

    {
        std::lock_guard<std::mutex> lock(g_mixedAudioSinkMutex);
        if (g_mixedAudioSink != nullptr) {
            env->DeleteGlobalRef(g_mixedAudioSink);
        }
        g_mixedAudioSink = newSink;
        g_mixedAudioSinkMethod = method;
    }

    if (sink != nullptr) {
        g_audioMixer.start();
    }

    std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
    clearRawErrorLocked();
    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeSetRemoteVideoSink(JNIEnv* env, jclass, jobject sink) {
    if (g_javaVm == nullptr) {
        env->GetJavaVM(&g_javaVm);
    }

    if (sink == nullptr) {
        {
            std::lock_guard<std::mutex> lock(g_remoteVideoSinkMutex);
            if (g_remoteVideoSink != nullptr) {
                env->DeleteGlobalRef(g_remoteVideoSink);
                g_remoteVideoSink = nullptr;
            }
            g_remoteParticipantConnectedMethod = nullptr;
            g_remoteParticipantDisconnectedMethod = nullptr;
            g_remoteAudioMuteChangedMethod = nullptr;
            g_remoteConnectionQualityMethod = nullptr;
            g_remoteVideoTrackAddedMethod = nullptr;
            g_remoteVideoFrameMethod = nullptr;
            g_remoteVideoTrackRemovedMethod = nullptr;
        }
        {
            // SessionLock, NOT lock_guard: stopAllRemoteVideoReadersLocked
            // publishes per-track removals into Java, and
            // deferDispatchIfSessionLockHeld dispatches IMMEDIATELY when the
            // SessionLock depth is 0 -- which a plain lock_guard leaves it at.
            // So the upcall would be raised with g_sessionMutex still held, and
            // the Kotlin handler calls back into JNI functions that take the
            // same non-recursive mutex: a self-deadlock. It is currently masked
            // only because the sink globals are nulled just above, so the
            // dispatch bails at hasRemoteVideoSink(). Same class of bug the
            // onDisconnected comment describes.
            SessionLock lock;
            stopAllRemoteVideoReadersLocked("sink-cleared");
            clearRawErrorLocked();
        }
        return kStatusOk;
    }

    jobject newSink = env->NewGlobalRef(sink);
    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID partConnectedMethod = env->GetMethodID(
        sinkClass,
        "onRemoteParticipantConnected",
        "(JJLjava/lang/String;Ljava/lang/String;)V");
    jmethodID partDisconnectedMethod = env->GetMethodID(
        sinkClass,
        "onRemoteParticipantDisconnected",
        "(JJLjava/lang/String;Ljava/lang/String;)V");
    jmethodID audioMuteMethod = env->GetMethodID(
        sinkClass,
        "onRemoteAudioMuteChanged",
        "(JJLjava/lang/String;Z)V");
    jmethodID connQualityMethod = env->GetMethodID(
        sinkClass,
        "onRemoteConnectionQuality",
        "(JJLjava/lang/String;JJD)V");
    jmethodID addedMethod = env->GetMethodID(
        sinkClass,
        "onRemoteVideoTrackAdded",
        "(JJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V");
    jmethodID frameMethod = env->GetMethodID(
        sinkClass,
        "onRemoteVideoFrame",
        // trackSource sits before the byte[] so the Java side can classify the
        // track on the very first frame, even if it beats trackAdded to the store.
        "(JJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[BIIIIIIJI)V");
    jmethodID removedMethod = env->GetMethodID(
        sinkClass,
        "onRemoteVideoTrackRemoved",
        "(JJLjava/lang/String;Ljava/lang/String;)V");
    // Same shape as onRemoteVideoTrackRemoved. The two are separate methods on
    // purpose: one means "the sender withdrew it", the other means "it is still
    // up there and Orbit cannot get hold of it", and collapsing them would make
    // the stuck case indistinguishable from a clean teardown.
    jmethodID stuckMethod = env->GetMethodID(
        sinkClass,
        "onRemoteVideoTrackStuck",
        "(JJLjava/lang/String;Ljava/lang/String;)V");
    // Fired for a publication with no track behind it as well, so the UI can put
    // the tile on screen -- and its waiting state -- before there is any frame to
    // draw. Without it a slow share had no tile at all until its first frame.
    jmethodID announcedMethod = env->GetMethodID(
        sinkClass,
        "onRemoteVideoAnnounced",
        "(JJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    env->DeleteLocalRef(sinkClass);

    if (partConnectedMethod == nullptr || partDisconnectedMethod == nullptr ||
        audioMuteMethod == nullptr || connQualityMethod == nullptr ||
        addedMethod == nullptr || frameMethod == nullptr || removedMethod == nullptr ||
        stuckMethod == nullptr || announcedMethod == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteGlobalRef(newSink);
        std::lock_guard<std::timed_mutex> sessionLock(g_sessionMutex);
        setRawErrorLocked("NATIVE_EXCEPTION", "NativeRemoteVideoSink methods not found", "nativeSetRemoteVideoSink", false);
        return kStatusNativeException;
    }

    {
        std::lock_guard<std::mutex> lock(g_remoteVideoSinkMutex);
        if (g_remoteVideoSink != nullptr) {
            env->DeleteGlobalRef(g_remoteVideoSink);
        }
        g_remoteVideoSink = newSink;
        g_remoteParticipantConnectedMethod = partConnectedMethod;
        g_remoteParticipantDisconnectedMethod = partDisconnectedMethod;
        g_remoteAudioMuteChangedMethod = audioMuteMethod;
        g_remoteConnectionQualityMethod = connQualityMethod;
        g_remoteVideoTrackAddedMethod = addedMethod;
        g_remoteVideoFrameMethod = frameMethod;
        g_remoteVideoTrackRemovedMethod = removedMethod;
        g_remoteVideoTrackStuckMethod = stuckMethod;
        g_remoteVideoAnnouncedMethod = announcedMethod;
    }

    std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
    clearRawErrorLocked();
    std::cout << "DEBUG [RemoteVideo] native remote video sink installed" << std::endl;
    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeConnectAsync(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch,
    jstring url,
    jstring token,
    jstring identity) {
    if (!isRawSessionValid(sessionId)) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeConnectAsync", false);
        return kStatusStaleEvent;
    }

    // A C++ exception crossing an extern "C" boundary is UB and, on MSVC,
    // terminates the JVM. The string conversions, the event dispatch and the
    // std::thread construction below all allocate.
    try {
    const std::string urlString = jStringToString(env, url);
    const std::string tokenString = jStringToString(env, token);
    const std::string identityString = jStringToString(env, identity);
    if (urlString.empty() || tokenString.empty()) {
        {
            std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
            setRawEpochLocked(epoch);
            setRawErrorLocked("CONNECT_FAILED", "Missing LiveKit URL or token", "nativeConnectAsync", false);
        }
        dispatchErrorNativeEvent(
            env,
            "livekit/NativeMediaEvent$ConnectFailed",
            "CONNECT_FAILED",
            sessionId,
            epoch,
            "Missing LiveKit URL or token",
            "nativeConnectAsync",
            false);
        return kStatusConnectFailed;
    }

    {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawEpochLocked(epoch);
        g_rawDisconnecting = false;
        clearRawErrorLocked();
    }
    g_sessionDestroyed = false;
    g_callbackShutdown = false;

    dispatchSimpleNativeEvent(env, "livekit/NativeMediaEvent$Connecting", sessionId, epoch);
    // The worker is a native thread: an escaping exception calls
    // std::terminate() and kills the JVM, so guard it here too.
    std::thread([sessionId, epoch, urlString, tokenString, identityString] {
        try {
        std::string errorMessage;
        const bool connected = connectRoom(urlString, tokenString, identityString, &errorMessage);

        bool shouldDispatch = false;
        bool cleanupDisconnectedStaleConnect = false;
        {
            std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
            shouldDispatch = isRawSessionValid(sessionId) && epoch == g_rawEpoch && !g_rawDisconnecting;
            cleanupDisconnectedStaleConnect = connected && epoch == g_rawEpoch && g_rawDisconnecting;
            if (!connected && shouldDispatch) {
                setRawErrorLocked(
                    "CONNECT_FAILED",
                    errorMessage.empty() ? "LiveKit C++ room connect failed" : errorMessage,
                    "nativeConnectAsync",
                    true);
            }
        }

        if (cleanupDisconnectedStaleConnect) {
            // SessionLock: resetRoomLocked() publishes per-track removals into
            // Java, which must not happen with g_sessionMutex held.
            SessionLock lock;
            resetRoomLocked();
        }

        JNIEnv* workerEnv = nullptr;
        bool attached = false;
        if (shouldDispatch) {
            getJniEnv(&workerEnv, &attached, "native-event");
            logJniAttachSuccess("native-event", attached);
        }

        if (workerEnv != nullptr && shouldDispatch) {
            if (connected) {
                dispatchSimpleNativeEvent(workerEnv, "livekit/NativeMediaEvent$Connected", sessionId, epoch);
            } else {
                dispatchErrorNativeEvent(
                    workerEnv,
                    "livekit/NativeMediaEvent$ConnectFailed",
                    "CONNECT_FAILED",
                    sessionId,
                    epoch,
                    errorMessage.empty() ? "LiveKit C++ room connect failed" : errorMessage,
                    "nativeConnectAsync",
                    true);
            }
        }

        releaseJniEnv(attached);
        } catch (const std::exception& error) {
            std::cerr << "ERROR [OrbitLiveKitAudio] connect worker threw: " << error.what() << std::endl;
        } catch (...) {
            std::cerr << "ERROR [OrbitLiveKitAudio] connect worker threw unknown error" << std::endl;
        }
    }).detach();

    return kStatusOk;
    } catch (const std::exception& error) {
        setRawErrorThreadSafe(
            "NATIVE_EXCEPTION",
            std::string("Native connect request failed: ") + error.what(),
            "nativeConnectAsync",
            true);
        std::cerr << "ERROR [OrbitLiveKitAudio] nativeConnectAsync threw: " << error.what() << std::endl;
        return kStatusNativeException;
    } catch (...) {
        setRawErrorThreadSafe(
            "NATIVE_EXCEPTION",
            "Native connect request failed",
            "nativeConnectAsync",
            true);
        std::cerr << "ERROR [OrbitLiveKitAudio] nativeConnectAsync threw unknown error" << std::endl;
        return kStatusNativeException;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeReconnectAsync(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch,
    jstring url,
    jstring token) {
    return Java_livekit_OrbitLiveKitNativeRaw_nativeConnectAsync(
        env,
        nullptr,
        sessionId,
        epoch,
        url,
        token,
        nullptr);
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeConfigureE2EE(
    JNIEnv*,
    jclass,
    jlong sessionId,
    jboolean enabled) {
    // The flag is module-level and is read by connectRoom when it builds
    // RoomOptions, so it does NOT require a live session. The previous version
    // returned kStatusStaleEvent first, which meant the pre-connect arming
    // that Kotlin explicitly does (requestE2EE is called before the session
    // exists) silently did nothing: the connect then built RoomOptions with
    // E2EE off, so in an ENCRYPTED MatrixRTC room the media went out
    // UNENCRYPTED and the whole media-key exchange had no effect. That is both
    // a privacy failure and the H8 deafness bug class.
    //
    // Set the flag unconditionally. If a session id is supplied and it is not
    // merely "not yet created", the caller still gets told.
    const bool haveSession = isRawSessionValid(sessionId);
    // No g_sessionMutex here, deliberately -- see g_e2eeRequested. This runs
    // BEFORE the session exists, on the rejoin path, and used to block behind a
    // previous session's teardown. That teardown joins reader threads while
    // holding the mutex; a reader's exit path needs the same mutex, so the wait
    // could not complete and the rejoin hung with no diagnostic of any kind.
    g_e2eeRequested.store(enabled == JNI_TRUE, std::memory_order_relaxed);
    std::cout << "DEBUG [OrbitLiveKitAudio] e2ee configured requested="
              << (enabled == JNI_TRUE ? "true" : "false")
              << " liveSession=" << (haveSession ? "yes" : "no (pre-connect arming)")
              << std::endl;
    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeSetParticipantMediaKey(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jstring identity,
    jbyteArray key,
    jint keyIndex) {
    if (!isRawSessionValid(sessionId)) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeSetParticipantMediaKey", false);
        return kStatusStaleEvent;
    }

    // A C++ exception crossing an extern "C" boundary is UB and, on MSVC,
    // terminates the JVM; the string conversion and the key vector below both
    // allocate, so the whole body has to be inside the guard.
    try {
    const std::string identityString = jStringToString(env, identity);
    if (identityString.empty() || key == nullptr) {
        std::cerr << "WARN [OrbitLiveKitAudio] e2ee set key rejected empty identity or null key" << std::endl;
        return kStatusNativeException;
    }

    const jsize keyLength = env->GetArrayLength(key);
    if (keyLength <= 0) {
        std::cerr << "WARN [OrbitLiveKitAudio] e2ee set key rejected empty key bytes" << std::endl;
        return kStatusNativeException;
    }
    std::vector<std::uint8_t> keyBytes(static_cast<std::size_t>(keyLength));
    env->GetByteArrayRegion(key, 0, keyLength, reinterpret_cast<jbyte*>(keyBytes.data()));
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
        std::cerr << "ERROR [OrbitLiveKitAudio] e2ee set key byte copy failed" << std::endl;
        return kStatusNativeException;
    }

    {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        g_participantMediaKeys[identityString] = {keyBytes, static_cast<int>(keyIndex)};

        if (g_room != nullptr) {
            auto* e2eeManager = g_room->e2eeManager();
            if (e2eeManager != nullptr && e2eeManager->keyProvider() != nullptr) {
                e2eeManager->keyProvider()->setKey(identityString, keyBytes, static_cast<int>(keyIndex));
                std::cout << "DEBUG [OrbitLiveKitAudio] e2ee participant key installed identity=" << identityString
                          << " keyIndex=" << keyIndex
                          << " keyLen=" << keyLength << std::endl;

                // Is this the key we are TRANSMITTING with? Only the local
                // participant's key is used to encrypt outgoing media; every other
                // entry here is for decrypting somebody else. Recording which one is
                // ours is what lets TXSTAT report the transmit key rather than
                // letting the peer-side desync hide behind healthy packet counters.
                if (g_room->localParticipant() != nullptr) {
                    const std::string localIdentity = g_room->localParticipant()->identity();
                    if (identityString == localIdentity) {
                        g_ownKeyIdentity = identityString;
                        g_ownKeyIndex = static_cast<int>(keyIndex);
                        g_ownKeyFingerprint = fingerprintKeyBytes(keyBytes);
                        std::cout << "DEBUG [OrbitLiveKitAudio] TXKEY outgoing key armed keyIndex=" << keyIndex
                                  << " fingerprint=" << std::hex << g_ownKeyFingerprint << std::dec
                                  << " identity=" << identityString << std::endl;
                    }
                }
                return kStatusOk;
            }
        }
        std::cout << "DEBUG [OrbitLiveKitAudio] e2ee participant key cached (room not connected yet) identity=" << identityString
                  << " keyIndex=" << keyIndex
                  << " keyLen=" << keyLength << std::endl;
        return kStatusOk;
    }
    } catch (const std::exception& error) {
        std::cerr << "ERROR [OrbitLiveKitAudio] e2ee set key failed error=" << error.what() << std::endl;
        setRawErrorThreadSafe(
            "NATIVE_EXCEPTION",
            std::string("E2EE set key failed: ") + error.what(),
            "nativeSetParticipantMediaKey",
            true);
        return kStatusNativeException;
    } catch (...) {
        std::cerr << "ERROR [OrbitLiveKitAudio] e2ee set key failed unknown error" << std::endl;
        setRawErrorThreadSafe(
            "NATIVE_EXCEPTION",
            "E2EE set key failed",
            "nativeSetParticipantMediaKey",
            true);
        return kStatusNativeException;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeDisconnect(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch,
    jboolean userInitiated) {
    if (!isRawSessionValid(sessionId)) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeDisconnect", false);
        return kStatusStaleEvent;
    }

    // NOTE: g_callbackShutdown is deliberately NOT raised here; see below.
    g_audioMixer.stop();
    {
        // SessionLock: the teardown publishes per-track removals into Java,
        // which must not happen with g_sessionMutex held.
        SessionLock lock;
        setRawEpochLocked(epoch);
        g_rawDisconnecting = true;
        g_acceptingFrames = false;
        if (g_room != nullptr) {
            g_room->setDelegate(nullptr);
        }
        // Readers are torn down BEFORE g_callbackShutdown latches: that flag
        // makes dispatchRemoteVideoTrackRemoved a no-op, so raising it first
        // suppressed the very per-track removals the teardown is supposed to
        // publish and left stale tiles on every caller's screen.
        stopAllRemoteReadersLocked();
        stopAllRemoteVideoReadersLocked("native-disconnect");
        g_callbackShutdown = true;
        resetRoomLocked();
        // Hangup is a real boundary; connectRoom() is not.
        g_participantMediaKeys.clear();
        g_ownKeyIdentity.clear();
        g_ownKeyIndex = -1;
        g_ownKeyFingerprint = 0;
        {
            std::lock_guard<std::mutex> gainLock(g_participantGainsMutex);
            g_participantGains.clear();
        }
        clearRawErrorLocked();
        {
            std::lock_guard<std::mutex> announcedLock(g_announcedParticipantsMutex);
            g_announcedParticipantSids.clear();
        }
        std::cout << "DEBUG [OrbitLiveKitAudio] disconnect cleanup completed" << std::endl;
    }
    dispatchDisconnectedEvent(
        env,
        sessionId,
        epoch,
        userInitiated == JNI_TRUE ? "user-left" : "native-disconnect",
        userInitiated != JNI_TRUE);
    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativePublishAudio(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch,
    jint sampleRate,
    jint channels,
    jint queueSizeMs,
    jboolean expectedTestTone,
    jint maxBitrateBps) {
    if (!isRawSessionValid(sessionId)) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativePublishAudio", false);
        return kStatusStaleEvent;
    }

    {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        // A publish that lands after a reconnect carries the OLD epoch. Applying
        // it would rewind g_rawEpoch, after which every mute returns 23 and mic
        // frames are dropped as stale — which itself counts as a push failure
        // and tears capture down. Reject it instead.
        if (epoch < g_rawEpoch) {
            setRawErrorLocked("STALE_EVENT", "Stale native media epoch", "nativePublishAudio", false);
            return kStatusStaleEvent;
        }
        adoptEpochLocked(epoch);
        clearRawErrorLocked();
    }

    const jboolean published =
        Java_livekit_LiveKitNativeAudioBridge_nativePublishAudio(env, nullptr, sampleRate, channels, kFrameMs, queueSizeMs, expectedTestTone, maxBitrateBps);
    if (published == JNI_TRUE) {
        dispatchSimpleNativeEvent(env, "livekit/NativeMediaEvent$AudioPublished", sessionId, epoch);
        return kStatusOk;
    }

    std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
    setRawErrorLocked("PUBLISH_AUDIO_FAILED", "LiveKit C++ microphone publish failed", "nativePublishAudio", false);
    return kStatusPublishAudioFailed;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativePushAudioFrame(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch,
    jobject buffer,
    jint sampleRate,
    jint channels,
    jint samplesPerChannel,
    jlong) {
    if (!isRawSessionValid(sessionId)) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativePushAudioFrame", false);
        return kStatusStaleEvent;
    }

    {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        if (epoch != g_rawEpoch) {
            setRawErrorLocked("STALE_EVENT", "Stale native media epoch", "nativePushAudioFrame", true);
            return kStatusStaleEvent;
        }
        if (!hasReadyMicLocked()) {
            logFrameDroppedNotReadyLocked();
            clearRawErrorLocked();
            return kStatusOk;
        }
    }

    const jint byteLength = samplesPerChannel * channels * static_cast<jint>(sizeof(std::int16_t));
    const jboolean pushed =
        Java_livekit_LiveKitNativeAudioBridge_nativePushAudioFrame(
            env,
            nullptr,
            buffer,
            byteLength,
            sampleRate,
            channels,
            samplesPerChannel);
    if (pushed == JNI_TRUE) {
        return kStatusOk;
    }

    std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
    setRawErrorLocked("AUDIO_CAPTURE_FAILED", "Native audio frame was rejected", "nativePushAudioFrame", true);
    return kStatusAudioCaptureFailed;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativePublishVideo(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch,
    jint width,
    jint height,
    jint fps,
    jint format,
    jint maxBitrateBps) {
    // SessionLock, not lock_guard: the VideoPublished upcall at the end of this
    // function must not be made with g_sessionMutex held, or Java re-entry into
    // any session entry point self-deadlocks. ~SessionLock releases the mutex
    // before it flushes the deferred dispatch.
    SessionLock lock;
    if (!isRawSessionValid(sessionId)) {
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativePublishVideo", false);
        return kStatusStaleEvent;
    }
    // A publish that lands after a reconnect carries the OLD epoch. Applying it
    // would rewind g_rawEpoch, after which every mute and frame push is rejected
    // as stale for the rest of the process. Reject it instead.
    if (epoch < g_rawEpoch) {
        setRawErrorLocked("STALE_EVENT", "Stale native media epoch", "nativePublishVideo", false);
        return kStatusStaleEvent;
    }
    adoptEpochLocked(epoch);
    clearRawErrorLocked();

    constexpr jint kVideoFormatI420 = 5;
    if (width <= 0 || height <= 0 || fps <= 0 || format != kVideoFormatI420) {
        setRawErrorLocked("CAMERA_MODE_REJECTED", "Unsupported native video publish format", "nativePublishVideo", true);
        return kStatusPublishVideoFailed;
    }

    if (!g_connected || g_room == nullptr || g_room->localParticipant() == nullptr) {
        setRawErrorLocked("PUBLISH_VIDEO_FAILED", "Cannot publish video before room connect", "nativePublishVideo", true);
        return kStatusPublishVideoFailed;
    }

    g_cameraRequested = true;
    g_acceptingVideoFrames = false;
    g_videoPublished = false;
    g_videoPushDiagnosticsLogged = 0;
    g_videoFrameDroppedNotReadyLogged = false;
    g_lastVideoTimestampNs = 0;

    if (g_videoSource != nullptr || g_videoTrack != nullptr || !g_videoPublicationSid.empty()) {
        if (g_room != nullptr && g_room->localParticipant() != nullptr && !g_videoPublicationSid.empty()) {
            try {
                std::cout << "DEBUG [VideoPublish] unpublish started publicationSid="
                          << g_videoPublicationSid
                          << " reason=republish"
                          << std::endl;
                g_room->localParticipant()->unpublishTrack(g_videoPublicationSid);
                std::cout << "DEBUG [VideoPublish] unpublish success publicationSid="
                          << g_videoPublicationSid << std::endl;
            } catch (const std::exception& error) {
                std::cerr << "WARN [VideoPublish] previous unpublish failed message="
                          << error.what() << std::endl;
            } catch (...) {
                std::cerr << "WARN [VideoPublish] previous unpublish failed message=unknown" << std::endl;
            }
        }
        g_videoTrack.reset();
        g_videoSource.reset();
        g_videoPublicationSid.clear();
        g_videoLocalTrackSid.clear();
    }

    try {
        g_videoWidth = static_cast<int>(width);
        g_videoHeight = static_cast<int>(height);
        g_videoFps = static_cast<int>(fps);
        g_videoFormat = static_cast<int>(format);
        g_videoSource = std::make_shared<livekit::VideoSource>(g_videoWidth, g_videoHeight);
        std::cout << "VideoPublish source created "
                  << "width=" << g_videoWidth
                  << " height=" << g_videoHeight
                  << " fps=" << g_videoFps
                  << " livekitFormat=I420"
                  << std::endl;

        g_videoTrack = livekit::LocalVideoTrack::createLocalVideoTrack("camera", g_videoSource);
        if (g_videoTrack == nullptr) {
            g_videoSource.reset();
            setRawErrorLocked("PUBLISH_VIDEO_FAILED", "Native C++ camera track creation failed", "nativePublishVideo", true);
            return kStatusPublishVideoFailed;
        }
        g_videoLocalTrackSid = g_videoTrack->sid();
        std::cout << "VideoPublish track created "
                  << "trackName=camera "
                  << "trackSource=SOURCE_CAMERA "
                  << "localTrackSid=" << (g_videoLocalTrackSid.empty() ? std::string{"<pending>"} : g_videoLocalTrackSid)
                  << " muted=" << (g_videoTrack->muted() ? "true" : "false")
                  << std::endl;

        livekit::TrackPublishOptions publishOptions;
        publishOptions.source = livekit::TrackSource::SOURCE_CAMERA;
        // Match the "simulcast=false" line this has always logged. Leaving the
        // optional unset let the SDK choose instead, so the log was a claim the
        // code never made.
        publishOptions.simulcast = false;
        int effectiveVideoMaxBitrateBps = maxBitrateBps > 0 ? static_cast<int>(maxBitrateBps) : kDefaultVideoMaxBitrateBps;
        if (effectiveVideoMaxBitrateBps < kMinVideoMaxBitrateBps) effectiveVideoMaxBitrateBps = kMinVideoMaxBitrateBps;
        if (effectiveVideoMaxBitrateBps > kMaxVideoMaxBitrateBps) effectiveVideoMaxBitrateBps = kMaxVideoMaxBitrateBps;
        livekit::VideoEncodingOptions videoEncoding;
        videoEncoding.max_bitrate = static_cast<std::uint64_t>(effectiveVideoMaxBitrateBps);
        videoEncoding.max_framerate = static_cast<double>(g_videoFps);
        publishOptions.video_encoding = videoEncoding;

        std::cout << "VideoPublish publish started "
                  << "trackName=camera "
                  << "trackSource=SOURCE_CAMERA "
                  << "width=" << g_videoWidth
                  << " height=" << g_videoHeight
                  << " fps=" << g_videoFps
                  << " format=I420"
                  << " maxBitrateBps=" << effectiveVideoMaxBitrateBps
                  << " simulcast=false"
                  << std::endl;
        g_room->localParticipant()->publishTrack(g_videoTrack, publishOptions);
        std::cout << "VideoPublish publishTrack returned success "
                  << "trackName=camera "
                  << "trackSource=SOURCE_CAMERA "
                  << "width=" << g_videoWidth
                  << " height=" << g_videoHeight
                  << " fps=" << g_videoFps
                  << " format=I420"
                  << std::endl;

        const auto publication = g_videoTrack->publication();
        if (g_videoSource == nullptr || g_videoTrack == nullptr || publication == nullptr) {
            g_videoTrack.reset();
            g_videoSource.reset();
            setRawErrorLocked("PUBLISH_VIDEO_FAILED", "Native C++ camera publish returned no publication", "nativePublishVideo", true);
            return kStatusPublishVideoFailed;
        }

        if (g_videoTrack->muted()) {
            g_videoTrack->unmute();
        }

        const bool simulcasted = publication->simulcasted();
        g_videoPublicationSid = publication->sid();
        g_videoLocalTrackSid = g_videoTrack->sid();
        g_videoPublished = true;
        g_acceptingVideoFrames = true;

        std::cout << "VideoPublish publish success "
                  << "publicationSid=" << (g_videoPublicationSid.empty() ? std::string{"<none>"} : g_videoPublicationSid)
                  << " publicationName=" << publication->name()
                  << " publicationKind=" << trackKindLabel(publication->kind())
                  << " publicationKindEnum=" << static_cast<int>(publication->kind())
                  << " publicationSourceEnum=" << static_cast<int>(publication->source())
                  << " publicationSourceLabel=" << trackSourceLabel(publication->source())
                  << " localTrackSid=" << (g_videoLocalTrackSid.empty() ? std::string{"<none>"} : g_videoLocalTrackSid)
                  << " localTrackMuted=" << (g_videoTrack->muted() ? "true" : "false")
                  << " trackName=camera "
                  << "trackSource=SOURCE_CAMERA "
                  << " publicationWidth=" << publication->width()
                  << " publicationHeight=" << publication->height()
                  << " publicationSimulcasted=" << (simulcasted ? "true" : "false")
                  << std::endl;
        dumpLocalParticipantPublicationsLocked("after_video_publish");
    } catch (const std::exception& error) {
        g_acceptingVideoFrames = false;
        g_videoPublished = false;
        g_videoTrack.reset();
        g_videoSource.reset();
        g_videoPublicationSid.clear();
        g_videoLocalTrackSid.clear();
        setRawErrorLocked("PUBLISH_VIDEO_FAILED", std::string("Native C++ camera publish failed: ") + error.what(), "nativePublishVideo", true);
        return kStatusPublishVideoFailed;
    } catch (...) {
        g_acceptingVideoFrames = false;
        g_videoPublished = false;
        g_videoTrack.reset();
        g_videoSource.reset();
        g_videoPublicationSid.clear();
        g_videoLocalTrackSid.clear();
        setRawErrorLocked("PUBLISH_VIDEO_FAILED", "Native C++ camera publish failed", "nativePublishVideo", true);
        return kStatusPublishVideoFailed;
    }

    // Queued rather than sent here so the event reaches Java only after
    // ~SessionLock has released g_sessionMutex; it is still delivered before
    // this JNI call returns.
    deferDispatchIfSessionLockHeld([env, sessionId, epoch] {
        dispatchSimpleNativeEvent(env, "livekit/NativeMediaEvent$VideoPublished", sessionId, epoch);
    });
    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativePushVideoFrame(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch,
    jobject buffer,
    jint width,
    jint height,
    jint format,
    jint strideY,
    jint strideU,
    jint strideV,
    jlong timestampNs,
    jint rotation) {
    constexpr jint kVideoFormatI420 = 5;
    std::shared_ptr<livekit::VideoSource> source;
    {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        if (!isRawSessionValid(sessionId)) {
            setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativePushVideoFrame", false);
            return kStatusStaleEvent;
        }
        if (epoch != g_rawEpoch) {
            setRawErrorLocked("STALE_EVENT", "Stale native media epoch", "nativePushVideoFrame", true);
            return kStatusStaleEvent;
        }
        if (!hasReadyVideoLocked()) {
            logVideoFrameDroppedNotReadyLocked();
            clearRawErrorLocked();
            return kStatusOk;
        }
        if (format != kVideoFormatI420 || width != g_videoWidth || height != g_videoHeight) {
            setRawErrorLocked("VIDEO_CAPTURE_FAILED", "Video frame format or dimensions do not match published source", "nativePushVideoFrame", true);
            return kStatusVideoCaptureFailed;
        }
        source = g_videoSource;
    }

    if (buffer == nullptr || source == nullptr) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("VIDEO_CAPTURE_FAILED", "Missing native video frame buffer or source", "nativePushVideoFrame", true);
        return kStatusVideoCaptureFailed;
    }

    void* data = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (data == nullptr || capacity <= 0) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("VIDEO_CAPTURE_FAILED", "Video frame buffer must be direct", "nativePushVideoFrame", true);
        return kStatusVideoCaptureFailed;
    }

    const int chromaWidth = (static_cast<int>(width) + 1) / 2;
    const int chromaHeight = (static_cast<int>(height) + 1) / 2;
    const int expectedStrideY = static_cast<int>(width);
    const int expectedStrideU = chromaWidth;
    const int expectedStrideV = chromaWidth;
    const std::size_t expectedBytes =
        static_cast<std::size_t>(width) * static_cast<std::size_t>(height) +
        static_cast<std::size_t>(chromaWidth) * static_cast<std::size_t>(chromaHeight) * 2U;

    if (strideY != expectedStrideY || strideU != expectedStrideU || strideV != expectedStrideV ||
        static_cast<std::size_t>(capacity) < expectedBytes) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("VIDEO_CAPTURE_FAILED", "Packed I420 frame layout is invalid", "nativePushVideoFrame", true);
        return kStatusVideoCaptureFailed;
    }

    try {
        std::vector<std::uint8_t> frameData(expectedBytes);
        std::memcpy(frameData.data(), data, expectedBytes);
        livekit::VideoFrame frame(
            static_cast<int>(width),
            static_cast<int>(height),
            livekit::VideoBufferType::I420,
            std::move(frameData));

        livekit::VideoRotation nativeRotation = livekit::VideoRotation::VIDEO_ROTATION_0;
        if (rotation == 90) {
            nativeRotation = livekit::VideoRotation::VIDEO_ROTATION_90;
        } else if (rotation == 180) {
            nativeRotation = livekit::VideoRotation::VIDEO_ROTATION_180;
        } else if (rotation == 270) {
            nativeRotation = livekit::VideoRotation::VIDEO_ROTATION_270;
        }
        const auto timestampUs = static_cast<std::int64_t>(timestampNs / 1000LL);
        source->captureFrame(frame, timestampUs, nativeRotation);

        {
            std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
            g_lastVideoTimestampNs = static_cast<std::int64_t>(timestampNs);
            if (g_videoPushDiagnosticsLogged < 8) {
                g_videoPushDiagnosticsLogged += 1;
                std::cout << "DEBUG [VideoPublish] native frame #"
                          << g_videoPushDiagnosticsLogged
                          << " width=" << width
                          << " height=" << height
                          << " sourceFormat=I420"
                          << " livekitFormat=I420"
                          << " bytes=" << expectedBytes
                          << " timestampNs=" << timestampNs
                          << " timestampUs=" << timestampUs
                          << " rotation=" << rotation
                          << " trackName=camera"
                          << " trackSource=SOURCE_CAMERA"
                          << std::endl;
            }
        }
        return kStatusOk;
    } catch (const std::exception& error) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("VIDEO_CAPTURE_FAILED", std::string("Native video captureFrame failed: ") + error.what(), "nativePushVideoFrame", true);
        return kStatusVideoCaptureFailed;
    } catch (...) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("VIDEO_CAPTURE_FAILED", "Native video captureFrame failed", "nativePushVideoFrame", true);
        return kStatusVideoCaptureFailed;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativePublishScreenShare(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch,
    jint width,
    jint height,
    jint fps,
    jint maxBitrateBps) {
    // Resolve the stream-diag upcall on this JVM-attached thread, before the
    // native stats thread emits its first SHARE line. FindClass from a detached
    // native thread uses the system loader, which is not where app classes live.
    resolveStreamDiagCallback(env);
    std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
    if (!isRawSessionValid(sessionId)) {
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativePublishScreenShare", false);
        return kStatusStaleEvent;
    }
    // A publish that lands after a reconnect carries the OLD epoch; applying it
    // would rewind g_rawEpoch and permanently break mute and frame pushes.
    if (epoch < g_rawEpoch) {
        setRawErrorLocked("STALE_EVENT", "Stale native media epoch", "nativePublishScreenShare", false);
        return kStatusStaleEvent;
    }
    adoptEpochLocked(epoch);
    clearRawErrorLocked();

    if (width <= 0 || height <= 0 || fps <= 0) {
        setRawErrorLocked("CAMERA_MODE_REJECTED", "Invalid screen share publish dimensions", "nativePublishScreenShare", true);
        return kStatusPublishVideoFailed;
    }

    if (!g_connected || g_room == nullptr || g_room->localParticipant() == nullptr) {
        setRawErrorLocked("PUBLISH_VIDEO_FAILED", "Cannot publish screen share before room connect", "nativePublishScreenShare", true);
        return kStatusPublishVideoFailed;
    }

    if (g_screenShareSource != nullptr && (width != g_screenShareWidth || height != g_screenShareHeight)) {
        // Resolution changed since last share: republish on top of the previous track.
        g_acceptingScreenShareFrames = false;
        if (!g_screenSharePublicationSid.empty()) {
            try {
                g_room->localParticipant()->unpublishTrack(g_screenSharePublicationSid);
            } catch (...) {
            }
        }
        g_screenShareTrack.reset();
        g_screenShareSource.reset();
        g_screenSharePublicationSid.clear();
        g_screenSharePublished = false;
    }

    try {
        int effectiveShareMaxBitrateBps = maxBitrateBps > 0 ? static_cast<int>(maxBitrateBps) : kDefaultScreenShareMaxBitrateBps;
        if (effectiveShareMaxBitrateBps < kMinScreenShareMaxBitrateBps) effectiveShareMaxBitrateBps = kMinScreenShareMaxBitrateBps;
        if (effectiveShareMaxBitrateBps > kMaxScreenShareMaxBitrateBps) effectiveShareMaxBitrateBps = kMaxScreenShareMaxBitrateBps;

        if (g_screenShareSource == nullptr) {
            g_screenShareWidth = static_cast<int>(width);
            g_screenShareHeight = static_cast<int>(height);
            g_screenShareFps = static_cast<int>(fps);
            g_screenShareSource = std::make_shared<livekit::VideoSource>(g_screenShareWidth, g_screenShareHeight);
            g_screenShareTrack = livekit::LocalVideoTrack::createLocalVideoTrack("screenshare", g_screenShareSource);
            if (g_screenShareTrack == nullptr) {
                g_screenShareSource.reset();
                setRawErrorLocked("PUBLISH_VIDEO_FAILED", "Native C++ screenshare track creation failed", "nativePublishScreenShare", true);
                return kStatusPublishVideoFailed;
            }

            livekit::TrackPublishOptions publishOptions;
            publishOptions.source = livekit::TrackSource::SOURCE_SCREENSHARE;
            // Screen share is a single full-resolution stream. Leaving this
            // unset lets the Rust SDK default to simulcast, which wraps the
            // encoder in SimulcastEncoderAdapter and splits the target bitrate
            // across layers. Observed in SHARESTAT: two libvpx encoders, a
            // 30 kbps initial target, and ~60 s to reach the configured max.
            publishOptions.simulcast = false;
            livekit::VideoEncodingOptions videoEncoding;
            videoEncoding.max_bitrate = static_cast<std::uint64_t>(effectiveShareMaxBitrateBps);
            videoEncoding.max_framerate = static_cast<double>(g_screenShareFps);
            publishOptions.video_encoding = videoEncoding;

            std::cout << "ScreenSharePublish started "
                      << "trackName=screenshare "
                      << "trackSource=SOURCE_SCREENSHARE "
                      << "width=" << g_screenShareWidth
                      << " height=" << g_screenShareHeight
                      << " fps=" << g_screenShareFps
                      << " format=I420"
                      << " maxBitrateBps=" << effectiveShareMaxBitrateBps
                      << " simulcast=false"
                      << std::endl;
            g_room->localParticipant()->publishTrack(g_screenShareTrack, publishOptions);

            const auto publication = g_screenShareTrack->publication();
            if (g_screenShareSource == nullptr || g_screenShareTrack == nullptr || publication == nullptr) {
                g_screenShareTrack.reset();
                g_screenShareSource.reset();
                setRawErrorLocked("PUBLISH_VIDEO_FAILED", "Native C++ screenshare publish returned no publication", "nativePublishScreenShare", true);
                return kStatusPublishVideoFailed;
            }

            const bool simulcasted = publication->simulcasted();
            std::cout << "ScreenSharePublish publication "
                      << "publicationSid=" << publication->sid()
                      << " simulcasted=" << (simulcasted ? "true" : "false")
                      << " width=" << publication->width()
                      << " height=" << publication->height()
                      << std::endl;

            if (g_screenShareTrack->muted()) {
                g_screenShareTrack->unmute();
            }
            g_screenSharePublicationSid = publication->sid();
            g_screenSharePublished = true;
        }

        g_acceptingScreenShareFrames = true;
        g_screenShareFrameDroppedNotReadyLogged = false;
        g_screenSharePushDiagnosticsLogged = 0;
        std::cout << "ScreenSharePublish accepting frames "
                  << "publicationSid=" << (g_screenSharePublicationSid.empty() ? std::string{"<none>"} : g_screenSharePublicationSid)
                  << std::endl;
    } catch (const std::exception& error) {
        g_acceptingScreenShareFrames = false;
        g_screenShareTrack.reset();
        g_screenShareSource.reset();
        g_screenSharePublicationSid.clear();
        g_screenSharePublished = false;
        setRawErrorLocked("PUBLISH_VIDEO_FAILED", std::string("Native C++ screenshare publish failed: ") + error.what(), "nativePublishScreenShare", true);
        return kStatusPublishVideoFailed;
    } catch (...) {
        g_acceptingScreenShareFrames = false;
        g_screenShareTrack.reset();
        g_screenShareSource.reset();
        g_screenSharePublicationSid.clear();
        g_screenSharePublished = false;
        setRawErrorLocked("PUBLISH_VIDEO_FAILED", "Native C++ screenshare publish failed", "nativePublishScreenShare", true);
        return kStatusPublishVideoFailed;
    }

    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeUnpublishScreenShare(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch) {
    std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
    if (!isRawSessionValid(sessionId)) {
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeUnpublishScreenShare", false);
        return kStatusStaleEvent;
    }
    // Same guard the other mutating entry points use: an unpublish from a
    // previous epoch must not tear down the track the current epoch published.
    if (epoch != g_rawEpoch) {
        setRawErrorLocked("STALE_EVENT", "Stale native media epoch", "nativeUnpublishScreenShare", true);
        return kStatusStaleEvent;
    }
    clearRawErrorLocked();

    g_acceptingScreenShareFrames = false;
    if (g_room != nullptr && g_room->localParticipant() != nullptr && !g_screenSharePublicationSid.empty()) {
        try {
            std::cout << "DEBUG [ScreenSharePublish] unpublish started publicationSid="
                      << g_screenSharePublicationSid
                      << " reason=share-stopped"
                      << std::endl;
            g_room->localParticipant()->unpublishTrack(g_screenSharePublicationSid);
            std::cout << "DEBUG [ScreenSharePublish] unpublish success" << std::endl;
        } catch (const std::exception& error) {
            std::cerr << "WARN [ScreenSharePublish] unpublish failed message=" << error.what() << std::endl;
        } catch (...) {
            std::cerr << "WARN [ScreenSharePublish] unpublish failed message=unknown" << std::endl;
        }
    }
    g_screenShareTrack.reset();
    g_screenShareSource.reset();
    g_screenSharePublished = false;
    g_screenShareWidth = 0;
    g_screenShareHeight = 0;
    g_screenShareFps = 0;
    g_screenSharePublicationSid.clear();
    g_screenShareFrameDroppedNotReadyLogged = false;
    g_screenSharePushDiagnosticsLogged = 0;
    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativePublishScreenShareAudio(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch) {
    std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
    if (!isRawSessionValid(sessionId)) {
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativePublishScreenShareAudio", false);
        return kStatusStaleEvent;
    }
    // A publish that lands after a reconnect carries the OLD epoch; applying it
    // would rewind g_rawEpoch and permanently break mute and frame pushes.
    if (epoch < g_rawEpoch) {
        setRawErrorLocked("STALE_EVENT", "Stale native media epoch", "nativePublishScreenShareAudio", false);
        return kStatusStaleEvent;
    }
    adoptEpochLocked(epoch);
    clearRawErrorLocked();

    if (g_shareAudioPublished && g_shareAudioTrack != nullptr && g_shareAudioSource != nullptr) {
        std::cout << "DEBUG [ScreenShareAudioPublish] already active; reusing track" << std::endl;
        return kStatusOk;
    }

    if (!g_connected || g_room == nullptr || g_room->localParticipant() == nullptr) {
        setRawErrorLocked("PUBLISH_AUDIO_FAILED", "Cannot publish screen share audio before room connect", "nativePublishScreenShareAudio", true);
        return kStatusPublishAudioFailed;
    }

    try {
        g_shareAudioSource = std::make_shared<livekit::AudioSource>(kSampleRate, 2, 0);
        g_shareAudioTrack = livekit::LocalAudioTrack::createLocalAudioTrack("screenshare_audio", g_shareAudioSource);
        if (g_shareAudioTrack == nullptr) {
            g_shareAudioSource.reset();
            setRawErrorLocked("PUBLISH_AUDIO_FAILED", "Native C++ screenshare audio track creation failed", "nativePublishScreenShareAudio", true);
            return kStatusPublishAudioFailed;
        }

        // Music-mode preset: DTX off and a higher Opus budget than the mic track;
        // this source bypasses the APM entirely (frames are pushed, not captured
        // from the device), so no NS/AGC can pump or gate it.
        livekit::TrackPublishOptions publishOptions;
        publishOptions.source = livekit::TrackSource::SOURCE_SCREENSHARE_AUDIO;
        publishOptions.dtx = false;
        publishOptions.red = true;
        livekit::AudioEncodingOptions audioEncoding;
        audioEncoding.max_bitrate = static_cast<std::uint64_t>(kDefaultShareAudioMaxBitrateBps);
        publishOptions.audio_encoding = audioEncoding;

        std::cout << "DEBUG [ScreenShareAudioPublish] publishing "
                  << "trackName=screenshare_audio"
                  << " source=SOURCE_SCREENSHARE_AUDIO"
                  << " sampleRate=" << kSampleRate
                  << " channels=2"
                  << " maxBitrateBps=" << kDefaultShareAudioMaxBitrateBps
                  << " dtx=false red=true"
                  << std::endl;

        g_room->localParticipant()->publishTrack(g_shareAudioTrack, publishOptions);

        const auto publication = g_shareAudioTrack->publication();
        if (g_shareAudioSource == nullptr || g_shareAudioTrack == nullptr || publication == nullptr) {
            g_shareAudioTrack.reset();
            g_shareAudioSource.reset();
            setRawErrorLocked("PUBLISH_AUDIO_FAILED", "Native C++ screenshare audio publish returned no publication", "nativePublishScreenShareAudio", true);
            return kStatusPublishAudioFailed;
        }
        if (g_shareAudioTrack->muted()) {
            g_shareAudioTrack->unmute();
        }
        g_shareAudioPublicationSid = publication->sid();

        stopShareLoopbackThread();
        g_shareLoopbackActive.store(true, std::memory_order_release);
        // Resolve the AEC upcall on this (JVM-attached) thread so the loopback
        // thread can use it from its very first frame.
        resolveShareAecCallback(env);
        g_shareAudioFeedTarget.store(g_shareAudioSource.get(), std::memory_order_release);
        g_shareLoopbackThread = std::thread(shareAudioLoopbackMain, g_shareAudioSource);
        g_shareAudioPublished = true;
        std::cout << "DEBUG [MatrixRTCMapping] native screenshare audio published"
                  << " publishedTrackSid=" << publication->sid()
                  << " publishedTrackName=" << publication->name()
                  << " publishedTrackSource=" << trackSourceLabel(publication->source())
                  << std::endl;
        return kStatusOk;
    } catch (const std::exception& error) {
        stopShareLoopbackThread();
        g_shareAudioTrack.reset();
        g_shareAudioSource.reset();
        g_shareAudioPublicationSid.clear();
        g_shareAudioPublished = false;
        setRawErrorLocked("PUBLISH_AUDIO_FAILED", std::string("Native C++ screenshare audio publish failed: ") + error.what(), "nativePublishScreenShareAudio", true);
        return kStatusPublishAudioFailed;
    } catch (...) {
        stopShareLoopbackThread();
        g_shareAudioTrack.reset();
        g_shareAudioSource.reset();
        g_shareAudioPublicationSid.clear();
        g_shareAudioPublished = false;
        setRawErrorLocked("PUBLISH_AUDIO_FAILED", "Native C++ screenshare audio publish failed", "nativePublishScreenShareAudio", true);
        return kStatusPublishAudioFailed;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeGetShareLoopbackMode(JNIEnv*, jclass) {
    if (!g_shareLoopbackModeResolved.load(std::memory_order_acquire)) return 0; // pending
    return g_shareLoopbackFullDeviceFallback.load(std::memory_order_acquire) ? 2 : 1;
}

// Down-call from livekit.ShareAudioAec: pushes one echo-cancelled 10 ms stereo
// frame back into the share publisher source. Must NOT take g_sessionMutex —
// teardown joins the loopback thread while holding it, and this call runs on
// that thread.
extern "C" JNIEXPORT void JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeFeedProcessedShareAudioFrame(
    JNIEnv* env,
    jclass,
    jbyteArray pcm) {
    if (pcm == nullptr) return;
    livekit::AudioSource* source = g_shareAudioFeedTarget.load(std::memory_order_acquire);
    if (source == nullptr) return;
    // This is a RE-ENTRANT upcall: C++ -> JVM ShareAudioAec.onLoopbackFrame ->
    // JNI straight back into here, so no Java-level try/catch can intercept a
    // C++ exception and a std::bad_alloc from the vector below would unwind
    // straight through the extern "C" boundary and terminate the VM. Everything
    // that can throw has to live inside this guard.
    try {
        const jsize byteLength = env->GetArrayLength(pcm);
        if (byteLength <= 0 || (byteLength % 2) != 0) return;
        std::vector<std::int16_t> samples(static_cast<std::size_t>(byteLength / 2));
        env->GetByteArrayRegion(pcm, 0, byteLength, reinterpret_cast<jbyte*>(samples.data()));
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
            return;
        }
        const int samplesPerChannel = static_cast<int>(samples.size() / 2);
        if (samplesPerChannel <= 0) return;
        try {
            livekit::AudioFrame frame(std::move(samples), kSampleRate, 2, samplesPerChannel);
            source->captureFrame(frame, kAudioSourceCaptureTimeoutMs);
        } catch (...) {
            // Consumer not ready or transient overflow; drop.
        }
    } catch (const std::exception& error) {
        std::cerr << "ERROR [ScreenShareAudioPublish] feed processed frame threw: "
                  << error.what() << std::endl;
    } catch (...) {
        std::cerr << "ERROR [ScreenShareAudioPublish] feed processed frame threw unknown error" << std::endl;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeUnpublishScreenShareAudio(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch) {
    std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
    if (!isRawSessionValid(sessionId)) {
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeUnpublishScreenShareAudio", false);
        return kStatusStaleEvent;
    }
    // Same guard the other mutating entry points use: an unpublish from a
    // previous epoch must not tear down the track the current epoch published.
    if (epoch != g_rawEpoch) {
        setRawErrorLocked("STALE_EVENT", "Stale native media epoch", "nativeUnpublishScreenShareAudio", true);
        return kStatusStaleEvent;
    }
    clearRawErrorLocked();

    if (g_shareAudioPublished || !g_shareAudioPublicationSid.empty()) {
        std::cout << "DEBUG [ScreenShareAudioPublish] unpublish started reason=share-audio-stopped" << std::endl;
    }
    stopShareLoopbackThread();
    if (g_room != nullptr && g_room->localParticipant() != nullptr && !g_shareAudioPublicationSid.empty()) {
        try {
            g_room->localParticipant()->unpublishTrack(g_shareAudioPublicationSid);
            std::cout << "DEBUG [ScreenShareAudioPublish] unpublish success" << std::endl;
        } catch (const std::exception& error) {
            std::cerr << "WARN [ScreenShareAudioPublish] unpublish failed message=" << error.what() << std::endl;
        } catch (...) {
            std::cerr << "WARN [ScreenShareAudioPublish] unpublish failed message=unknown" << std::endl;
        }
    }
    g_shareAudioTrack.reset();
    g_shareAudioSource.reset();
    g_shareAudioPublished = false;
    g_shareAudioPublicationSid.clear();
    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativePushScreenShareFrame(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch,
    jobject buffer,
    jint width,
    jint height,
    jint format,
    jint strideY,
    jint strideU,
    jint strideV,
    jlong timestampNs,
    jint rotation) {
    constexpr jint kVideoFormatI420 = 5;
    std::shared_ptr<livekit::VideoSource> source;
    {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        if (!isRawSessionValid(sessionId)) {
            setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativePushScreenShareFrame", false);
            return kStatusStaleEvent;
        }
        if (epoch != g_rawEpoch) {
            setRawErrorLocked("STALE_EVENT", "Stale native media epoch", "nativePushScreenShareFrame", true);
            return kStatusStaleEvent;
        }
        if (!g_acceptingScreenShareFrames || g_screenShareSource == nullptr) {
            if (!g_screenShareFrameDroppedNotReadyLogged) {
                g_screenShareFrameDroppedNotReadyLogged = true;
                std::cout << "DEBUG [ScreenSharePublish] frame dropped; share source not ready" << std::endl;
            }
            clearRawErrorLocked();
            return kStatusOk;
        }
        if (format != kVideoFormatI420 || width != g_screenShareWidth || height != g_screenShareHeight) {
            setRawErrorLocked("VIDEO_CAPTURE_FAILED", "Screen share frame dimensions do not match published source", "nativePushScreenShareFrame", true);
            return kStatusVideoCaptureFailed;
        }
        source = g_screenShareSource;
    }

    if (buffer == nullptr || source == nullptr) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("VIDEO_CAPTURE_FAILED", "Missing screen share frame buffer or source", "nativePushScreenShareFrame", true);
        return kStatusVideoCaptureFailed;
    }

    void* data = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (data == nullptr || capacity <= 0) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("VIDEO_CAPTURE_FAILED", "Screen share frame buffer must be direct", "nativePushScreenShareFrame", true);
        return kStatusVideoCaptureFailed;
    }

    const int chromaWidth = (static_cast<int>(width) + 1) / 2;
    const int chromaHeight = (static_cast<int>(height) + 1) / 2;
    const std::size_t expectedBytes =
        static_cast<std::size_t>(width) * static_cast<std::size_t>(height) +
        static_cast<std::size_t>(chromaWidth) * static_cast<std::size_t>(chromaHeight) * 2U;

    if (strideY != width || strideU != chromaWidth || strideV != chromaWidth ||
        static_cast<std::size_t>(capacity) < expectedBytes) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("VIDEO_CAPTURE_FAILED", "Screen share packed I420 frame layout is invalid", "nativePushScreenShareFrame", true);
        return kStatusVideoCaptureFailed;
    }

    try {
        std::vector<std::uint8_t> frameData(expectedBytes);
        std::memcpy(frameData.data(), data, expectedBytes);
        livekit::VideoFrame frame(
            static_cast<int>(width),
            static_cast<int>(height),
            livekit::VideoBufferType::I420,
            std::move(frameData));

        livekit::VideoRotation nativeRotation = livekit::VideoRotation::VIDEO_ROTATION_0;
        if (rotation == 90) {
            nativeRotation = livekit::VideoRotation::VIDEO_ROTATION_90;
        } else if (rotation == 180) {
            nativeRotation = livekit::VideoRotation::VIDEO_ROTATION_180;
        } else if (rotation == 270) {
            nativeRotation = livekit::VideoRotation::VIDEO_ROTATION_270;
        }
        const auto timestampUs = static_cast<std::int64_t>(timestampNs / 1000LL);
        source->captureFrame(frame, timestampUs, nativeRotation);

        {
            std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
            if (g_screenSharePushDiagnosticsLogged < 4) {
                g_screenSharePushDiagnosticsLogged += 1;
                std::cout << "DEBUG [ScreenSharePublish] frame #"
                          << g_screenSharePushDiagnosticsLogged
                          << " width=" << width
                          << " height=" << height
                          << " bytes=" << expectedBytes
                          << " timestampUs=" << timestampUs
                          << " trackName=screenshare"
                          << " trackSource=SOURCE_SCREENSHARE"
                          << std::endl;
            }
        }
        return kStatusOk;
    } catch (const std::exception& error) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("VIDEO_CAPTURE_FAILED", std::string("Native screenshare captureFrame failed: ") + error.what(), "nativePushScreenShareFrame", true);
        return kStatusVideoCaptureFailed;
    } catch (...) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("VIDEO_CAPTURE_FAILED", "Native screenshare captureFrame failed", "nativePushScreenShareFrame", true);
        return kStatusVideoCaptureFailed;
    }
}

#if defined(_MSC_VER)
static bool safeAudioTrackSetMuted(livekit::LocalAudioTrack* track, bool muted) {
    if (!track) return false;
    __try {
        if (muted) {
            track->mute();
        } else {
            track->unmute();
        }
        return true;
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        std::cerr << "WARN [OrbitLiveKitAudio] safeAudioTrackSetMuted caught SEH exception code="
                  << GetExceptionCode() << std::endl;
        return false;
    }
}

static bool safeVideoTrackSetMuted(livekit::LocalVideoTrack* track, bool muted) {
    if (!track) return false;
    __try {
        if (muted) {
            track->mute();
        } else {
            track->unmute();
        }
        return true;
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        std::cerr << "WARN [OrbitLiveKitAudio] safeVideoTrackSetMuted caught SEH exception code="
                  << GetExceptionCode() << std::endl;
        return false;
    }
}
#else
static bool safeAudioTrackSetMuted(livekit::LocalAudioTrack* track, bool muted) {
    if (!track) return false;
    try {
        if (muted) {
            track->mute();
        } else {
            track->unmute();
        }
        return true;
    } catch (...) {
        return false;
    }
}

static bool safeVideoTrackSetMuted(livekit::LocalVideoTrack* track, bool muted) {
    if (!track) return false;
    try {
        if (muted) {
            track->mute();
        } else {
            track->unmute();
        }
        return true;
    } catch (...) {
        return false;
    }
}
#endif

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeSetAudioMuted(JNIEnv*, jclass, jlong sessionId, jlong epoch, jboolean muted) {
    std::shared_ptr<livekit::LocalAudioTrack> track;
    {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        if (!isRawSessionValid(sessionId)) {
            setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeSetAudioMuted", false);
            return kStatusStaleEvent;
        }
        if (epoch != g_rawEpoch) {
            setRawErrorLocked("STALE_EVENT", "Stale native media epoch", "nativeSetAudioMuted", true);
            return kStatusStaleEvent;
        }
        clearRawErrorLocked();
        track = g_audioTrack;
    }

    if (track == nullptr) {
        std::cout << "WARN [OrbitLiveKitAudio] nativeSetAudioMuted ignored: no audio track published"
                  << " sessionId=" << sessionId
                  << " muted=" << (muted == JNI_TRUE ? "true" : "false")
                  << std::endl;
        return kStatusOk;
    }

    try {
        safeAudioTrackSetMuted(track.get(), muted == JNI_TRUE);
        // Authoritative write of the worker's fail-closed mute gate input; the
        // worker only refreshes this opportunistically when it wins the session
        // mutex.
        g_micTrackMutedCache.store(muted == JNI_TRUE, std::memory_order_relaxed);
        std::cout << "DEBUG [OrbitLiveKitAudio] native mic muted="
                  << (muted == JNI_TRUE ? "true" : "false")
                  << " trackMuted=" << (track->muted() ? "true" : "false")
                  << std::endl;
        return kStatusOk;
    } catch (const std::exception& error) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("NATIVE_EXCEPTION", std::string("Native audio mute failed: ") + error.what(), "nativeSetAudioMuted", true);
        return kStatusNativeException;
    } catch (...) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("NATIVE_EXCEPTION", "Native audio mute failed", "nativeSetAudioMuted", true);
        return kStatusNativeException;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeSetVideoMuted(JNIEnv*, jclass, jlong sessionId, jlong epoch, jboolean muted) {
    std::shared_ptr<livekit::LocalVideoTrack> track;
    {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        if (!isRawSessionValid(sessionId)) {
            setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeSetVideoMuted", false);
            return kStatusStaleEvent;
        }
        if (epoch != g_rawEpoch) {
            setRawErrorLocked("STALE_EVENT", "Stale native media epoch", "nativeSetVideoMuted", true);
            return kStatusStaleEvent;
        }
        clearRawErrorLocked();
        track = g_videoTrack;
    }

    if (track == nullptr) {
        return kStatusOk;
    }

    try {
        safeVideoTrackSetMuted(track.get(), muted == JNI_TRUE);
        std::cout << "DEBUG [VideoPublish] native video muted="
                  << (muted == JNI_TRUE ? "true" : "false")
                  << " trackMuted=" << (track->muted() ? "true" : "false")
                  << " trackName=camera"
                  << " trackSource=SOURCE_CAMERA"
                  << std::endl;
        return kStatusOk;
    } catch (const std::exception& error) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("NATIVE_EXCEPTION", std::string("Native video mute failed: ") + error.what(), "nativeSetVideoMuted", true);
        return kStatusNativeException;
    } catch (...) {
        std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
        setRawErrorLocked("NATIVE_EXCEPTION", "Native video mute failed", "nativeSetVideoMuted", true);
        return kStatusNativeException;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeGetLastErrorJson(JNIEnv* env, jclass, jlong) {
    // A C++ exception crossing an extern "C" boundary is UB and, on MSVC,
    // terminates the JVM. Copy the payload under the lock and only then touch
    // JNI, so no allocation or encoding work happens while the mutex is held.
    try {
        std::string lastErrorJson;
        {
            std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
            lastErrorJson = g_rawLastErrorJson;
        }
        return env->NewStringUTF(lastErrorJson.c_str());
    } catch (const std::exception& error) {
        std::cerr << "ERROR [OrbitLiveKitAudio] nativeGetLastErrorJson threw: "
                  << error.what() << std::endl;
        return nullptr;
    } catch (...) {
        std::cerr << "ERROR [OrbitLiveKitAudio] nativeGetLastErrorJson threw unknown error" << std::endl;
        return nullptr;
    }
}

// BUG-B fix: idempotent rescan of remote participants' video publications.
// Reader startup previously depended solely on delegate events; a TrackSubscribed
// landing before the sink was armed or a subscription completing after the
// post-connect scan left the track with no frame reader forever. The Kotlin
// session calls this periodically; the guards below only skip tracks that still
// have a LIVE reader, and startRemoteVideoReaderLocked replaces a dead entry, so
// re-invocation is safe and recovers a track whose reader thread died.
extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeRescanRemoteVideoTracks(JNIEnv*, jclass, jlong sessionId, jlong epoch) {
    // SessionLock, not lock_guard: the rescan announces participants and starts
    // readers, both of which cross into Java. ~SessionLock releases the mutex
    // before flushing those upcalls.
    SessionLock lock;
    if (!isRawSessionValid(sessionId)) {
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeRescanRemoteVideoTracks", false);
        return kStatusStaleEvent;
    }
    if (epoch != g_rawEpoch) {
        setRawErrorLocked("STALE_EVENT", "Stale native media epoch", "nativeRescanRemoteVideoTracks", true);
        return kStatusStaleEvent;
    }
    clearRawErrorLocked();
    if (g_room == nullptr || !g_connected) {
        return kStatusOk;
    }
    int startedVideo = 0;
    int forcedSubscribes = 0;
    int renegotiatedSubscribes = 0;
    // Rebuilt every tick and compared against the previous one, so a line is
    // printed only on a real change to the tracks present in the room.
    std::string inventory;
    int startedAudio = 0;
    std::unordered_set<std::string> activeVideoPubSidsInRoom;
    std::unordered_set<std::string> activeVideoTrackSidsInRoom;
    for (const auto& participant : g_room->remoteParticipants()) {
        if (participant == nullptr) {
            continue;
        }
        // The pre-check that used to live here is gone. It duplicated the dedup
        // rule at a fifth call site, used a different key derivation than the
        // dispatch (no identity fallback), and therefore disagreed with it --
        // which is how one peer produced eight announcements. The dispatch is now
        // the only place that decides, and it returns without side effects when
        // the participant is already announced, so calling it unconditionally
        // costs a set lookup and cannot double-announce.
        const std::string identity = participant->identity();
        const std::string sid = normalizedSid(participant->sid());
        deferDispatchIfSessionLockHeld([identity, sid] { dispatchRemoteParticipantConnected(identity, sid); });
        for (const auto& publicationEntry : participant->trackPublications()) {
            const auto& publication = publicationEntry.second;
            if (publication == nullptr) {
                continue;
            }
            const auto track = publication->track();
            if (publication->kind() == livekit::TrackKind::KIND_VIDEO) {
                activeVideoPubSidsInRoom.insert(normalizedSid(publication->sid()));
                if (track != nullptr && !track->sid().empty()) {
                    activeVideoTrackSidsInRoom.insert(normalizedSid(track->sid()));
                }
            }
            inventory += " " + trackSourceLabel(publication->source()) + "/" +
                publication->sid() + (publication->subscribed() ? "=sub" : "=UNSUB") +
                (publication->muted() ? "/muted" : "") +
                // Track presence is in the inventory because it is the thing that
                // was invisible. Run 22's first share sat at `=UNSUB` and then,
                // once force-subscribed, at `=sub` -- and still produced no
                // picture, because the publication had no track attached at ANY
                // sighting and the sender withdrew it. Every line said the right
                // thing about the subscription and nothing about whether there
                // was anything to subscribe TO. `/+track` appearing (or not) in
                // a later inventory line is now the whole diagnosis.
                (track != nullptr ? "/+track" : "/noTrack");
            if (track == nullptr) {
                // Announce BEFORE the trackless handling, so the UI has a tile to
                // put its waiting state on. This is the only path that can tell
                // the app a share exists before a frame arrives; without it the
                // tile appears at the same instant as the picture and there is
                // nothing to show a spinner on.
                announceRemoteVideoOnce(participant.get(), publication, nullptr);
                // A publication with no track attached. This is the state that
                // makes a screen share permanently invisible, and the 2 s rescan
                // is the only thing that ever sees it, so it cannot be skipped.
                //
                // COUNTED ON TRACKLESS ALONE, NOT ON TRACKLESS-AND-UNSUBSCRIBED.
                //
                // That condition was the previous version and it made the whole
                // escalation dead code. The first renegotiate ends with
                // setSubscribed(true), so the publication stops being "unsubscribed",
                // the `!subscribed() && trackless` test stops matching, and the
                // counter freezes at whatever it reached. Run 26 shows the result:
                // one renegotiate per publication, `count=2`, and the 18 s and
                // 36 s attempts could never fire no matter how long the share was
                // held open.
                //
                // The counter is about a missing TRACK. Subscription state is what
                // the renegotiate changes, not what it is waiting on.
                int tracklessTicks = 0;
                {
                    std::lock_guard<std::mutex> lock(g_tracklessTicksMutex);
                    tracklessTicks = ++g_tracklessTicks[publication->sid()];
                }
                if (isTracklessRenegotiateTick(tracklessTicks)) {
                    std::cout << "DEBUG [OrbitLiveKitAudio] publication trackless for "
                              << (tracklessTicks * 2) << "s (attempt), renegotiating subscription "
                              << "kind=" << (publication->kind() == livekit::TrackKind::KIND_VIDEO ? "video" : "audio")
                              << " source=" << trackSourceLabel(publication->source())
                              << " publicationSid=" << publication->sid()
                              << " subscribed=" << (publication->subscribed() ? "true" : "false")
                              << " participantIdentity=" << participant->identity()
                              << std::endl;
                    try {
                        publication->setSubscribed(false);
                        publication->setSubscribed(true);
                        renegotiatedSubscribes += 1;
                    } catch (const std::exception& error) {
                        std::cerr << "WARN [OrbitLiveKitAudio] renegotiate threw publicationSid="
                                  << publication->sid() << " message=" << error.what() << std::endl;
                    } catch (...) {
                        std::cerr << "WARN [OrbitLiveKitAudio] renegotiate threw publicationSid="
                                  << publication->sid() << " message=unknown" << std::endl;
                    }
                }
                if (tracklessTicks == kTracklessTicksBeforeStuck) {
                    // Retrying has not produced a track and nothing else in this
                    // pipeline can without one. Hand it to the UI, which is the only
                    // layer that can offer the one recovery that works.
                    //
                    // VIDEO only, and only a screenshare: this is the reported
                    // failure, and a stuck microphone is a different problem with a
                    // different answer.
                    const bool isVideo = publication->kind() == livekit::TrackKind::KIND_VIDEO;
                    const bool isScreenShare =
                        publication->source() == livekit::TrackSource::SOURCE_SCREENSHARE ||
                        publication->source() == livekit::TrackSource::SOURCE_SCREENSHARE_AUDIO;
                    if (isVideo && isScreenShare) {
                        const std::string identity = participant->identity();
                        const std::string publicationSid = publication->sid();
                        std::cout << "WARN [OrbitLiveKitAudio] screenshare track still has no track after "
                                  << (tracklessTicks * 2) << "s and "
                                  << kTracklessTicksBeforeRenegotiate << " renegotiations; "
                                  << "surfacing to UI publicationSid=" << publicationSid
                                  << " participantIdentity=" << identity << std::endl;
                        deferDispatchIfSessionLockHeld([identity, publicationSid] {
                            // kRawSessionId for the id, and the ATOMIC epoch mirror
                            // rather than g_rawEpoch: this dispatch is deferred, so
                            // it can run after the mutex is released, and a reader
                            // that took g_sessionMutex here would deadlock the
                            // teardown already waiting on that reader.
                            dispatchRemoteVideoTrackStuck(
                                kRawSessionId,
                                g_rawEpochForCallbacks.load(std::memory_order_acquire),
                                identity,
                                publicationSid);
                        });
                    }
                }
                continue;
            }
            if (track != nullptr) {
                std::lock_guard<std::mutex> lock(g_everHadTrackMutex);
                g_publicationsEverSeenWithTrack.insert(publication->sid());
            }
            {
                // Seen with a track, so stop counting it as trackless.
                std::lock_guard<std::mutex> lock(g_tracklessTicksMutex);
                g_tracklessTicks.erase(publication->sid());
            }
            announceRemoteVideoOnce(participant.get(), publication, track);
            if (!publication->subscribed()) {
                // auto_subscribe is on, so an unsubscribed publication means the
                // auto-subscribe lost a race with the publish and nothing ever
                // claimed it. The track exists on the server, Orbit asked for
                // everything, and the request never happened for this one track.
                //
                // Subscribe explicitly, ONCE per publication. Unbounded retries
                // would hammer the SFU every tick and could never succeed if the
                // server is refusing for some other reason.
                //
                // BOTH kinds, and that restriction is the whole bug this replaces.
                // The first version of this guard was `kind() == KIND_AUDIO &&`,
                // written when the only known symptom was missing share audio. Run
                // 21 then showed the same race hitting VIDEO:
                //
                //   inventory: ... screenshare_audio/TR_AsX64VRzkuR4yn=UNSUB
                //              screenshare/TR_VSHDWz6h52h3pe=UNSUB
                //
                // The audio one was picked up on the next tick and became =sub.
                // The video one stayed =UNSUB for three consecutive ticks and was
                // then unpublished -- that is the whole of "Element's first share
                // showed nothing at all". A guard written to match the symptom
                // instead of the mechanism fixes one instance of the bug and
                // leaves the identical bug next to it.
                const auto publicationSid = publication->sid();
                bool alreadyTried = false;
                {
                    std::lock_guard<std::mutex> forcedLock(g_forcedSubscribesMutex);
                    alreadyTried = !g_forcedSubscribes.insert(publicationSid).second;
                }
                if (!alreadyTried) {
                    const char* kindLabel =
                        publication->kind() == livekit::TrackKind::KIND_VIDEO ? "video" : "audio";
                    std::cout << "DEBUG [OrbitLiveKitAudio] forcing subscribe for unsubscribed publication "
                              << "kind=" << kindLabel
                              << " participantIdentity=" << participant->identity()
                              << " publicationSid=" << publicationSid
                              << " source=" << trackSourceLabel(publication->source())
                              << " trackPresent=" << (track != nullptr ? "true" : "false")
                              << std::endl;
                    try {
                        publication->setSubscribed(true);
                        forcedSubscribes += 1;
                    } catch (const std::exception& error) {
                        std::cerr << "WARN [OrbitLiveKitAudio] force subscribe threw publicationSid="
                                  << publicationSid << " message=" << error.what() << std::endl;
                    } catch (...) {
                        std::cerr << "WARN [OrbitLiveKitAudio] force subscribe threw publicationSid="
                                  << publicationSid << " message=unknown" << std::endl;
                    }
                }
                continue;
            }
            if (!publication->subscribed()) {
                continue;
            }
            if (track == nullptr) {
                continue;
            }
            const auto trackSid = track->sid();
            if (trackSid.empty()) {
                continue;
            }
            if (publication->kind() == livekit::TrackKind::KIND_VIDEO) {
                // Only skip tracks that still have a LIVE reader: a reader whose
                // thread already exited leaves a map entry behind, and skipping
                // that entry is what kept the track frozen for the rest of the
                // call. startRemoteVideoReaderLocked replaces a dead entry.
                auto it = g_remoteVideoReaders.find(trackSid);
                const bool hasLiveReader =
                    it != g_remoteVideoReaders.end() && it->second != nullptr && it->second->running.load();
                if (!hasLiveReader) {
                    logRemoteVideoPublicationDetected(participant.get(), publication, track, "rescan");
                    startRemoteVideoReaderLocked(track, participant.get(), publication);
                    startedVideo += 1;
                }
            } else if (publication->kind() == livekit::TrackKind::KIND_AUDIO) {
                auto it = g_remoteAudioReaders.find(trackSid);
                const bool hasLiveReader =
                    it != g_remoteAudioReaders.end() && it->second != nullptr && it->second->running.load();
                if (!hasLiveReader) {
                    std::cout << "DEBUG [OrbitLiveKitAudio] rescan starting missing audio reader trackSid=" << trackSid << std::endl;
                    startRemoteReaderLocked(track, participant->identity());
                    startedAudio += 1;
                }
            }
        }
    }
    // Prune video readers for publications that were withdrawn from the room
    // without an unpublish event.
    //
    // Gated on consecutive absences (see g_absentVideoTicksMutex). Pruning on the
    // first missing sighting is what made this destructive rather than a safety
    // net: it fired for publications that were still live, and because every prune
    // publishes a track-removed into Java, the UI could never settle on a state.
    std::vector<std::string> withdrawnVideoTracks;
    {
        std::lock_guard<std::mutex> absentLock(g_absentVideoTicksMutex);
        // Counters are keyed by reader sid; drop the ones whose reader is gone so
        // the map cannot grow across a long call.
        for (auto it = g_absentVideoTicks.begin(); it != g_absentVideoTicks.end();) {
            if (g_remoteVideoReaders.count(it->first) == 0) {
                it = g_absentVideoTicks.erase(it);
            } else {
                ++it;
            }
        }
        for (const auto& entry : g_remoteVideoReaders) {
            const auto& reader = entry.second;
            if (reader == nullptr) continue;
            const std::string normTrack = normalizedSid(reader->trackSid);
            const std::string normPub = normalizedSid(reader->publicationSid);
            const bool inRoom = (activeVideoTrackSidsInRoom.count(normTrack) > 0) ||
                                (!normPub.empty() && activeVideoPubSidsInRoom.count(normPub) > 0);
            if (inRoom) {
                g_absentVideoTicks.erase(entry.first);
                continue;
            }
            const int absentTicks = ++g_absentVideoTicks[entry.first];
            if (absentTicks >= kAbsentVideoTicksBeforePrune) {
                withdrawnVideoTracks.push_back(entry.first);
            } else {
                std::cout << "DEBUG [RemoteVideo] rescan saw video publication absent, tick "
                          << absentTicks << "/" << kAbsentVideoTicksBeforePrune
                          << " sid=" << entry.first << " (not pruning yet)" << std::endl;
            }
        }
    }
    for (const auto& sid : withdrawnVideoTracks) {
        {
            std::lock_guard<std::mutex> absentLock(g_absentVideoTicksMutex);
            g_absentVideoTicks.erase(sid);
        }
        std::cout << "DEBUG [RemoteVideo] rescan detected video track withdrawn from room sid=" << sid << std::endl;
        stopRemoteVideoReaderLocked(sid, "rescan-withdrawn");
    }
    if (inventory != g_remotePublicationInventory) {
        g_remotePublicationInventory = inventory;
        std::cout << "DEBUG [OrbitLiveKitAudio] remote publication inventory "
                  << delegateEventCounts() << ":"
                  << (inventory.empty() ? " <empty>" : inventory) << std::endl;
    } else {
        // Unchanged inventory can still mean new events, so the counters are
        // reported on their own schedule rather than only on a change. This is the
        // line that would have caught run 22: an inventory that never changed
        // while a publication sat in it, trackless and unsubscribed, for as long
        // as the sender kept it up.
        const std::string counts = delegateEventCounts();
        if (counts != g_lastDelegateCounts) {
            g_lastDelegateCounts = counts;
            std::cout << "DEBUG [OrbitLiveKitAudio] delegate events " << counts << std::endl;
        }
    }
    if (startedVideo > 0 || startedAudio > 0) {
        std::cout << "DEBUG [OrbitLiveKitAudio] rescan recovered missing readers video="
                  << startedVideo << " audio=" << startedAudio << std::endl;
    }
    if (forcedSubscribes > 0) {
        std::cout << "DEBUG [OrbitLiveKitAudio] rescan forced subscribe count="
                  << forcedSubscribes << std::endl;
    }
    if (renegotiatedSubscribes > 0) {
        std::cout << "DEBUG [OrbitLiveKitAudio] rescan renegotiated trackless publications count="
                  << renegotiatedSubscribes << std::endl;
    }
    return kStatusOk;
}

extern "C" JNIEXPORT jstring JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeGetReadinessJson(JNIEnv* env, jclass, jlong sessionId) {
    // A C++ exception crossing an extern "C" boundary is UB and, on MSVC,
    // terminates the JVM. rawReadinessJsonLocked builds a std::string with
    // several concatenations, so the whole body has to be inside the guard.
    try {
        std::string readinessJson;
        {
            std::lock_guard<std::timed_mutex> lock(g_sessionMutex);
            readinessJson = rawReadinessJsonLocked(sessionId);
        }
        return env->NewStringUTF(readinessJson.c_str());
    } catch (const std::exception& error) {
        std::cerr << "ERROR [OrbitLiveKitAudio] nativeGetReadinessJson threw: "
                  << error.what() << std::endl;
        return nullptr;
    } catch (...) {
        std::cerr << "ERROR [OrbitLiveKitAudio] nativeGetReadinessJson threw unknown error" << std::endl;
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    g_javaVm = vm;
    g_sessionDestroyed = false;
    g_callbackShutdown = false;
    return JNI_VERSION_1_8;
}

extern "C" JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM* vm, void*) {
    g_callbackShutdown = true;
    g_sessionDestroyed = true;
    g_javaVm = nullptr;
    // Stop the native threads BEFORE the JVM destroys itself, and wait for them.
    // Run 83 exited with 0xC0000409 (STATUS_STACK_BUFFER_OVERRUN, a HotSpot
    // fail-fast) immediately after:
    //
    //     VM attach current thread failed
    //     Failed to attach thread 24756
    //
    // That is what a LiveKit SDK thread calling getJniEnv looks like once the VM
    // is tearing down: it asks to attach, HotSpot refuses, and the abort is
    // HotSpot's, not ours -- which is why that string appears nowhere in this
    // file. Latching the flags above only stops the NEXT upcall. It does nothing
    // about a thread already inside one, and nothing about threads that outlive
    // the library entirely.
    //
    // Joining here is safe precisely because this function holds NO lock. Every
    // join that had to be deferred elsewhere in this file was deferred because
    // the joiner held g_sessionMutex, which the joined thread needs to finish.
    // Here there is nothing held, so the joins can be immediate -- and they have
    // to be, because a detached thread reaching the VM during teardown is the
    // thing being fixed.
    //
    // Bounded by one sleep interval each: both threads check their atomic at the
    // top of every loop, so clearing it here releases them within 5s worst case.
    g_txStatsRunning.store(false, std::memory_order_release);
    g_readerReaperRunning.store(false, std::memory_order_release);
    if (g_txStatsThread.joinable() &&
        g_txStatsThread.get_id() != std::this_thread::get_id()) {
        g_txStatsThread.join();
    }
    if (g_txStatsRetiredThread.joinable()) {
        g_txStatsRetiredThread.join();
    }
    // The AEC router class was cached as a global ref for the process
    // lifetime; drop it here so unloading the library does not leave a global
    // ref pointing at a class whose class loader may already be tearing down.
    JNIEnv* env = nullptr;
    const bool haveEnv =
        vm != nullptr && vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_OK;
    if (haveEnv && env != nullptr) {
        {
            std::lock_guard<std::mutex> lock(g_shareAecMutex);
            if (g_shareAecRouterClass != nullptr) {
                env->DeleteGlobalRef(g_shareAecRouterClass);
                g_shareAecRouterClass = nullptr;
            }
            g_shareAecOnFrameMethod = nullptr;
        }
        {
            std::lock_guard<std::mutex> lock(g_streamDiagMutex);
            if (g_streamDiagClass != nullptr) {
                env->DeleteGlobalRef(g_streamDiagClass);
                g_streamDiagClass = nullptr;
            }
            g_streamDiagOnLineMethod = nullptr;
        }
    }
}
