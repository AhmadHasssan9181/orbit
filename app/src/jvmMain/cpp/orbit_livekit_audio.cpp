#include <jni.h>

#include "livekit/livekit.h"
#include "livekit/audio_processing_module.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <exception>
#include <iostream>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>
#include <queue>
namespace {

std::once_flag g_initializeOnce;
std::mutex g_sessionMutex;
std::unique_ptr<livekit::Room> g_room;
std::unique_ptr<livekit::RoomDelegate> g_delegate;
std::shared_ptr<livekit::AudioSource> g_audioSource;
std::shared_ptr<livekit::LocalAudioTrack> g_audioTrack;
std::shared_ptr<livekit::AudioProcessingModule> g_audioProcessor;
std::shared_ptr<livekit::VideoSource> g_videoSource;
std::shared_ptr<livekit::LocalVideoTrack> g_videoTrack;
std::string g_matrixRtcMemberId;
bool g_connected = false;
bool g_micPublished = false;
bool g_acceptingFrames = false;
bool g_videoPublished = false;
bool g_acceptingVideoFrames = false;
bool g_cameraRequested = false;
int g_videoWidth = 0;
int g_videoHeight = 0;
int g_videoFps = 0;
int g_videoFormat = 0;
std::string g_videoPublicationSid;
std::string g_videoLocalTrackSid;
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

std::mutex g_mixedAudioSinkMutex;
jobject g_mixedAudioSink = nullptr;
jmethodID g_mixedAudioSinkMethod = nullptr;

std::mutex g_eventSinkMutex;
jobject g_eventSink = nullptr;
jmethodID g_eventSinkMethod = nullptr;

std::mutex g_jniAttachLogMutex;
std::unordered_set<std::string> g_loggedJniAttachSuccess;
std::unordered_set<std::string> g_loggedCallbackSkips;

struct RemoteAudioReader {
    std::string trackSid;
    std::string participantIdentity;
    std::shared_ptr<livekit::AudioStream> stream;
    std::thread thread;
    std::atomic_bool running{true};
};

std::unordered_map<std::string, std::shared_ptr<RemoteAudioReader>> g_remoteAudioReaders;

constexpr jint kSampleRate = 48000;
constexpr jint kChannels = 1;
constexpr jint kMaxChannels = 2;
constexpr jint kFrameMs = 10;
constexpr jint kSamplesPerChannel = 480;
constexpr jint kFrameBytes = 960;
constexpr jint kBytesPerSample = static_cast<jint>(sizeof(std::int16_t));
constexpr int kAudioSourceCaptureTimeoutMs = 0;
constexpr std::size_t kTargetParticipantQueueSamples = kSamplesPerChannel * 6; // 60 ms target playout depth
constexpr std::size_t kMaxParticipantQueueSamples = kSamplesPerChannel * 12; // 120 ms
constexpr jlong kRawSessionId = 1;
jlong g_rawEpoch = 0;
bool g_rawDisconnecting = false;

bool isSupportedAudioChannels(jint channels) {
    return channels == kChannels || channels == kMaxChannels;
}

bool isSupportedAudioSourceQueueMs(jint queueSizeMs) {
    return queueSizeMs == 10;
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

void setRawErrorThreadSafe(
    const std::string& code,
    const std::string& message,
    const std::string& operation,
    bool recoverable);

struct ParticipantPcmQueue {
    explicit ParticipantPcmQueue(std::string identity)
        : participantIdentity(std::move(identity)) {}

    std::string participantIdentity;
    std::deque<std::int16_t> pcm;
    std::mutex queueMutex;
    std::atomic_bool active{true};
    std::atomic<std::int64_t> lastFrameNs{0};
    bool targetDepthPrimed{false};
};

void dispatchMixedAudioFrame(const std::vector<std::int16_t>& samples, std::int64_t timestampNs);

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
            std::queue<AudioSourceFramePacket> empty;
            std::swap(queue_, empty);
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
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!running_ || source_ == nullptr) {
                setLastCaptureError("audio source worker is not running");
                return false;
            }
            if (queue_.size() > 50) { // Max 500ms buffering
                framesDroppedDueBackpressure_.fetch_add(1, std::memory_order_relaxed);
                queue_.pop(); // Drop oldest frame
            }
            queue_.push(AudioSourceFramePacket{
                std::move(samples), sampleRate, channels, samplesPerChannel, timestampNs
            });
        }
        cv_.notify_one();
        return true;
    }

private:
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
        formatDiagnosticsLogged_ = 0;
        preCaptureDiagnosticsLogged_ = 0;
        workerFrameSequence_ = 0;
        lastFrameTimestampNs_ = 0;
        setLastCaptureError("");
    }

    void run() {
        using clock = std::chrono::steady_clock;
        auto nextSubmit = clock::now();

        while (true) {
            AudioSourceFramePacket packet;
            std::shared_ptr<livekit::AudioSource> source;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                cv_.wait(lock, [this] { return !running_ || !queue_.empty(); });
                if (!running_) {
                    break;
                }
                packet = std::move(queue_.front());
                queue_.pop();
                source = source_;
            }

            const auto now = clock::now();
            if (nextSubmit > now) {
                std::this_thread::sleep_until(nextSubmit);
            } else if (now - nextSubmit > std::chrono::milliseconds(kFrameMs * 2)) {
                nextSubmit = now;
            }
            nextSubmit += std::chrono::milliseconds(kFrameMs);

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
                
                {
                    std::lock_guard<std::mutex> sessionLock(g_sessionMutex);
                    if (g_audioProcessor) {
                        try {
                            g_audioProcessor->processStream(frame);
                        } catch (const std::exception& e) {
                            std::cerr << "ERROR [OrbitLiveKitAudio] processStream failed: " << e.what() << std::endl;
                        }
                    }
                }

                const auto frameSequence = ++workerFrameSequence_;
                const auto& frameSamples = frame.data();
                const auto pcmStats = computePcmDiagnostics(frameSamples.data(), frameSamples.size());
                if (pcmStats.peak > 1000) {
                    workerFramesWithTonePeakGreaterThan1000_.fetch_add(1, std::memory_order_relaxed);
                }
                if (pcmStats.allZero || pcmStats.peak <= 3) {
                    workerFramesSilent_.fetch_add(1, std::memory_order_relaxed);
                }

                bool acceptingFrames = true;
                bool micPublished = true;
                bool trackMuted = false;
                bool stateRead = false;
                {
                    std::unique_lock<std::mutex> sessionLock(g_sessionMutex, std::try_to_lock);
                    if (sessionLock.owns_lock()) {
                        acceptingFrames = g_acceptingFrames;
                        micPublished = g_micPublished;
                        trackMuted = g_audioTrack != nullptr && g_audioTrack->muted();
                        stateRead = true;
                    }
                }

                if (stateRead && (trackMuted || !acceptingFrames)) {
                    if (trackMuted) {
                        workerFramesSkippedMuted_.fetch_add(1, std::memory_order_relaxed);
                    }
                    if (preCaptureDiagnosticsLogged_ < 20) {
                        preCaptureDiagnosticsLogged_ += 1;
                        std::cout << "DEBUG [OrbitLiveKitAudio] worker frame skipped before captureFrame seq="
                                  << frameSequence
                                  << " acceptingFrames=" << (acceptingFrames ? "true" : "false")
                                  << " micPublished=" << (micPublished ? "true" : "false")
                                  << " trackMuted=" << (trackMuted ? "true" : "false")
                                  << " stateRead=" << (stateRead ? "true" : "false")
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
                              << " stateRead=" << (stateRead ? "true" : "false")
                              << std::endl;
                }

                if (frameSequence <= 10) {
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
                source->captureFrame(frame, kAudioSourceCaptureTimeoutMs);
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
                  << " lastCaptureError=" << (error.empty() ? "<none>" : error)
                  << std::endl;
    }

    mutable std::mutex mutex_;
    mutable std::mutex errorMutex_;
    std::condition_variable cv_;
    std::thread worker_;
    bool running_{false};
    std::shared_ptr<livekit::AudioSource> source_;
    std::queue<AudioSourceFramePacket> queue_;
    std::chrono::steady_clock::time_point lastStatsLog_{};
    std::string lastCaptureError_;
    int formatDiagnosticsLogged_{0};
    int preCaptureDiagnosticsLogged_{0};
    std::uint64_t workerFrameSequence_{0};
    std::int64_t lastFrameTimestampNs_{0};

    std::atomic<std::uint64_t> framesReceivedFromKotlin_{0};
    std::atomic<std::uint64_t> framesSubmittedToLiveKit_{0};
    std::atomic<std::uint64_t> framesAcceptedByLiveKit_{0};
    std::atomic<std::uint64_t> framesTimedOut_{0};
    std::atomic<std::uint64_t> framesDroppedDueBackpressure_{0};
    std::atomic<std::uint64_t> workerFramesWithTonePeakGreaterThan1000_{0};
    std::atomic<std::uint64_t> workerFramesSilent_{0};
    std::atomic<std::uint64_t> workerFramesCaptured_{0};
    std::atomic<std::uint64_t> workerFramesSkippedMuted_{0};
};

NativeAudioSourceWorker g_audioSourceWorker;

class NativeAudioMixer {
public:
    ~NativeAudioMixer() {
        stop();
    }

    void start() {
        bool expected = false;
        if (!running_.compare_exchange_strong(expected, true)) {
            return;
        }

        worker_ = std::thread([this] { mixLoop(); });
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

    void addParticipant(const std::string& identity) {
        if (identity.empty()) {
            return;
        }

        std::lock_guard<std::mutex> lock(mutex_);
        auto& queue = participants_[identity];
        if (queue == nullptr) {
            queue = std::make_shared<ParticipantPcmQueue>(identity);
            std::cout << "DEBUG [OrbitLiveKitAudio] remote audio participant added: "
                      << identity << std::endl;
        }
        queue->active = true;
    }

    void removeParticipant(const std::string& identity) {
        if (identity.empty()) {
            return;
        }

        std::shared_ptr<ParticipantPcmQueue> removed;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            auto it = participants_.find(identity);
            if (it == participants_.end()) {
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
    }

    void pushParticipantFrame(
        const std::string& identity,
        const std::int16_t* pcm,
        int sampleRate,
        int samplesPerChannel,
        int channels,
        std::int64_t timestampNs) {
        if (identity.empty() ||
            pcm == nullptr ||
            sampleRate != kSampleRate ||
            samplesPerChannel <= 0 ||
            channels <= 0) {
            return;
        }

        std::shared_ptr<ParticipantPcmQueue> queue;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            auto& existing = participants_[identity];
            if (existing == nullptr) {
                existing = std::make_shared<ParticipantPcmQueue>(identity);
                std::cout << "DEBUG [OrbitLiveKitAudio] remote audio participant added: "
                          << identity << std::endl;
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

        {
            std::lock_guard<std::mutex> queueLock(queue->queueMutex);
            while (queue->pcm.size() + mono.size() > kMaxParticipantQueueSamples && !queue->pcm.empty()) {
                queue->pcm.pop_front();
            }
            queue->pcm.insert(queue->pcm.end(), mono.begin(), mono.end());
            if (queue->pcm.size() >= kTargetParticipantQueueSamples) {
                queue->targetDepthPrimed = true;
            }
            queue->lastFrameNs = timestampNs;
        }
    }

private:
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

        for (const auto& queue : queues) {
            if (queue == nullptr || !queue->active) {
                continue;
            }

            std::vector<std::int16_t> frame(kSamplesPerChannel, 0);
            {
                std::lock_guard<std::mutex> queueLock(queue->queueMutex);
                const bool canRead = queue->targetDepthPrimed || queue->pcm.size() >= kTargetParticipantQueueSamples;
                if (canRead) {
                    queue->targetDepthPrimed = true;
                }
                const auto samplesToRead = canRead
                    ? std::min<std::size_t>(queue->pcm.size(), kSamplesPerChannel)
                    : 0;
                for (std::size_t i = 0; i < samplesToRead; ++i) {
                    frame[i] = queue->pcm.front();
                    queue->pcm.pop_front();
                }
                if (queue->pcm.empty()) {
                    queue->targetDepthPrimed = false;
                }
            }

            activeParticipants += 1;
            for (int i = 0; i < kSamplesPerChannel; ++i) {
                accumulator[i] += frame[static_cast<std::size_t>(i)];
            }
        }

        if (activeParticipants <= 0) {
            return;
        }

        const double divisor = std::sqrt(static_cast<double>(activeParticipants));
        std::vector<std::int16_t> mixed(kSamplesPerChannel, 0);
        for (int i = 0; i < kSamplesPerChannel; ++i) {
            const auto scaled = static_cast<std::int32_t>(accumulator[i] / divisor);
            mixed[static_cast<std::size_t>(i)] = static_cast<std::int16_t>(
                std::clamp(scaled, -32768, 32767));
        }

        if (!mixedCallbackStartedLogged_) {
            mixedCallbackStartedLogged_ = true;
            std::cout << "DEBUG [OrbitLiveKitAudio] mixed audio callback started" << std::endl;
        }

        const auto timestampNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        dispatchMixedAudioFrame(mixed, static_cast<std::int64_t>(timestampNs));
    }

void mixLoop() {
        using clock = std::chrono::steady_clock;
        auto nextTick = clock::now();

        while (running_) {
            nextTick += std::chrono::milliseconds(kFrameMs);
            mixOneFrame();
            std::this_thread::sleep_until(nextTick);

            const auto now = clock::now();
            if (now > nextTick + std::chrono::milliseconds(20)) {
                nextTick = now;
            }
        }
        std::cout << "DEBUG [OrbitLiveKitAudio] mixer thread exited" << std::endl;
    }

    std::atomic_bool running_{false};
    std::atomic_bool mixedCallbackStartedLogged_{false};
    std::thread worker_;
    std::mutex mutex_;
    std::unordered_map<std::string, std::shared_ptr<ParticipantPcmQueue>> participants_;
};

NativeAudioMixer g_audioMixer;

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

bool callbacksStopped(const char* callbackType) {
    if (g_sessionDestroyed.load() || g_callbackShutdown.load()) {
        logCallbackSkipOnce(callbackType, "session destroyed");
        return true;
    }
    return false;
}

bool getJniEnv(JNIEnv** env, bool* attached, const char* callbackType = "jni-callback") {
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
            std::lock_guard<std::mutex> lock(g_sessionMutex);
            epoch = g_rawEpoch;
            shouldDispatch = g_connected && !g_rawDisconnecting;
        }

        if (shouldDispatch) {
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

void stopAllRemoteReadersLocked() {
    auto readers = std::move(g_remoteAudioReaders);
    g_remoteAudioReaders.clear();
    for (const auto& entry : readers) {
        stopReader(entry.second);
    }
    g_audioMixer.clearParticipants();
}

void startRemoteReaderLocked(const std::shared_ptr<livekit::Track>& track, const std::string& participantIdentity) {
    if (track == nullptr || track->kind() != livekit::TrackKind::KIND_AUDIO) {
        return;
    }

    try {
        const std::string trackSid = track->sid();
        if (g_remoteAudioReaders.find(trackSid) != g_remoteAudioReaders.end()) {
            std::cout << "DEBUG [OrbitLiveKitAudio] Remote audio reader already active for track "
                      << trackSid << std::endl;
            return;
        }

        livekit::AudioStream::Options options;
        options.capacity = 20;

        auto reader = std::make_shared<RemoteAudioReader>();
        reader->trackSid = trackSid;
        reader->participantIdentity = participantIdentity.empty() ? track->sid() : participantIdentity;
        g_audioMixer.addParticipant(reader->participantIdentity);
        reader->stream = livekit::AudioStream::fromTrack(track, options);
        reader->thread = std::thread([reader] {
            livekit::AudioFrameEvent event;
            while (reader->running && reader->stream != nullptr && reader->stream->read(event)) {
                if (callbacksStopped("remote-audio-reader")) {
                    break;
                }
                const auto& samples = event.frame.data();
                if (samples.empty()) {
                    continue;
                }
                const auto timestampNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count();
                g_audioMixer.pushParticipantFrame(
                    reader->participantIdentity,
                    samples.data(),
                    event.frame.sampleRate(),
                    event.frame.samplesPerChannel(),
                    event.frame.numChannels(),
                    static_cast<std::int64_t>(timestampNs));
            }
            std::unique_lock<std::mutex> lock(g_sessionMutex, std::try_to_lock);
            if (lock.owns_lock()) {
                auto it = g_remoteAudioReaders.find(reader->trackSid);
                if (it != g_remoteAudioReaders.end() && it->second == reader) {
                    g_audioMixer.removeParticipant(reader->participantIdentity);
                    g_remoteAudioReaders.erase(it);
                    std::cout << "DEBUG [OrbitLiveKitAudio] Remote audio reader exited for track "
                              << reader->trackSid << std::endl;
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

void stopRemoteReaderLocked(const std::string& trackSid) {
    auto it = g_remoteAudioReaders.find(trackSid);
    if (it == g_remoteAudioReaders.end()) {
        return;
    }

    auto reader = it->second;
    g_remoteAudioReaders.erase(it);
    if (reader != nullptr) {
        g_audioMixer.removeParticipant(reader->participantIdentity);
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

    g_audioMixer.removeParticipant(participantIdentity);
    for (const auto& reader : readers) {
        stopReader(reader);
    }
}

std::string trackSourceLabel(livekit::TrackSource source);
std::string optionalTrackSourceLabel(const std::optional<livekit::TrackSource>& source);
std::string audioFeaturesLabel(const std::vector<livekit::AudioTrackFeature>& features);
std::string encryptionTypeLabel(livekit::EncryptionType type);

class OrbitAudioRoomDelegate final : public livekit::RoomDelegate {
public:
    void onLocalTrackPublished(livekit::Room&, const livekit::LocalTrackPublishedEvent& event) override {
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
    }

    void onTrackSubscribed(livekit::Room&, const livekit::TrackSubscribedEvent& event) override {
        if (callbacksStopped("room-delegate")) {
            return;
        }
        std::lock_guard<std::mutex> lock(g_sessionMutex);
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
    }

    void onTrackUnsubscribed(livekit::Room&, const livekit::TrackUnsubscribedEvent& event) override {
        if (callbacksStopped("room-delegate")) {
            return;
        }
        if (event.track == nullptr) {
            return;
        }
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        stopRemoteReaderLocked(event.track->sid());
    }

    void onParticipantDisconnected(livekit::Room&, const livekit::ParticipantDisconnectedEvent& event) override {
        if (callbacksStopped("room-delegate")) {
            return;
        }
        if (event.participant == nullptr) {
            return;
        }
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        stopRemoteReadersForParticipantLocked(event.participant->identity());
    }

    void onDisconnected(livekit::Room&, const livekit::DisconnectedEvent&) override {
        if (callbacksStopped("room-delegate")) {
            return;
        }
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_acceptingFrames = false;
        g_micPublished = false;
        stopAllRemoteReadersLocked();
        g_connected = false;
    }
};

void resetRoomLocked() {
    g_acceptingFrames = false;
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
    g_audioSourceWorker.stop();
    if (g_room != nullptr) {
        g_room->setDelegate(nullptr);
    }
    stopAllRemoteReadersLocked();
    g_audioTrack.reset();
    g_audioSource.reset();
    g_delegate.reset();
    g_room.reset();
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
                output += c;
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
    std::lock_guard<std::mutex> lock(g_sessionMutex);
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

        std::lock_guard<std::mutex> lock(g_sessionMutex);
        resetRoomLocked();
        g_matrixRtcMemberId = requestedIdentity;
        std::cout << "DEBUG [OrbitLiveKitAudio] native connect started" << std::endl;

        g_room = std::make_unique<livekit::Room>();
        g_delegate = std::make_unique<OrbitAudioRoomDelegate>();
        g_room->setDelegate(g_delegate.get());
        livekit::RoomOptions options;
        options.auto_subscribe = true;
        options.dynacast = false;

        g_connected = g_room->connect(urlString, tokenString, options);
        if (!g_connected) {
            resetRoomLocked();
            if (errorMessage != nullptr) {
                *errorMessage = "LiveKit C++ room connect failed";
            }
            std::cerr << "ERROR [OrbitLiveKitAudio] LiveKit C++ room connect failed" << std::endl;
            return false;
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

        for (const auto& participant : g_room->remoteParticipants()) {
            if (participant == nullptr) {
                continue;
            }
            for (const auto& publicationEntry : participant->trackPublications()) {
                const auto& publication = publicationEntry.second;
                if (publication == nullptr || !publication->subscribed()) {
                    continue;
                }
                const auto track = publication->track();
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
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        resetRoomLocked();
        if (errorMessage != nullptr) {
            *errorMessage = std::string("Connect threw: ") + error.what();
        }
        std::cerr << "ERROR [OrbitLiveKitAudio] Connect threw: " << error.what() << std::endl;
        return false;
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
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
    g_audioMixer.stop();
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    g_acceptingFrames = false;
    resetRoomLocked();
    std::cout << "DEBUG [OrbitLiveKitAudio] disconnect cleanup completed" << std::endl;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativePublishAudio(
    JNIEnv*, jclass, jint sampleRate, jint channels, jint frameMs, jint queueSizeMs, jboolean expectedTestTone) {
    std::cout << "DEBUG [OrbitLiveKitAudio] mic publish started" << std::endl;
    if (sampleRate != kSampleRate ||
        !isSupportedAudioChannels(channels) ||
        frameMs != kFrameMs ||
        !isSupportedAudioSourceQueueMs(queueSizeMs)) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_acceptingFrames = false;
        g_micPublished = false;
        g_publishedAudioChannels = 0;
        g_expectedTestTone = false;
        std::cerr << "ERROR [OrbitLiveKitAudio] Invalid audio publish format; expected 48000 Hz, 1 or 2 channels, 10 ms, queueSizeMs 10" << std::endl;
        return JNI_FALSE;
    }

    try {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
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
        
        livekit::AudioProcessingModule::Options apmOptions;
        apmOptions.echo_cancellation = true;
        apmOptions.noise_suppression = true;
        apmOptions.auto_gain_control = true;
        apmOptions.high_pass_filter = true;
        g_audioProcessor = std::make_shared<livekit::AudioProcessingModule>(apmOptions);

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
        livekit::TrackPublishOptions publishOptions;
        publishOptions.source = livekit::TrackSource::SOURCE_MICROPHONE;

        std::cout << "DEBUG [OrbitLiveKitAudio] audio publish options: "
                  << "trackName=audio"
                  << " trackKind=" << static_cast<int>(livekit::TrackKind::KIND_AUDIO)
                  << " trackSourceEnum=" << static_cast<int>(livekit::TrackSource::SOURCE_MICROPHONE)
                  << " trackSourceLabel=" << trackSourceLabel(livekit::TrackSource::SOURCE_MICROPHONE)
                  << " source=SOURCE_MICROPHONE"
                  << " channels=" << channels
                  << " expectedTestTone=" << (g_expectedTestTone.load(std::memory_order_relaxed) ? "true" : "false")
                  << std::endl;

        g_audioTrack = livekit::LocalAudioTrack::createLocalAudioTrack("audio", g_audioSource);
        if (g_audioTrack == nullptr) {
            g_audioTrack = nullptr;
            g_audioProcessor = nullptr;
            g_micPublished = false;
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
        g_audioSourceWorker.start(g_audioSource);
        g_micPublished = true;
        g_acceptingFrames = true;
        g_publishedAudioChannels = static_cast<int>(channels);
        std::cout << "DEBUG [OrbitLiveKitAudio] mic publish succeeded" << std::endl;
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
        std::lock_guard<std::mutex> lock(g_sessionMutex);
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
        std::lock_guard<std::mutex> lock(g_sessionMutex);
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
        std::lock_guard<std::mutex> lock(g_sessionMutex);
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

    try {
        std::vector<std::int16_t> samples(static_cast<std::size_t>(expectedBytes / sizeof(std::int16_t)));
        std::memcpy(samples.data(), data, static_cast<std::size_t>(expectedBytes));

        {
            std::lock_guard<std::mutex> lock(g_sessionMutex);
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
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    return hasReadyMicLocked() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativeSetAcceptingFrames(JNIEnv*, jclass, jboolean accepting) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    g_acceptingFrames = accepting == JNI_TRUE && g_connected && g_micPublished && g_audioSource != nullptr && g_audioTrack != nullptr;
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
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    g_rawEpoch += 1;
    g_rawDisconnecting = false;
    clearRawErrorLocked();
    return kRawSessionId;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeDestroySession(JNIEnv* env, jclass, jlong sessionId) {
    if (!isRawSessionValid(sessionId)) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeDestroySession", false);
        return kStatusStaleEvent;
    }

    g_callbackShutdown = true;
    g_sessionDestroyed = true;
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_rawDisconnecting = true;
        g_acceptingFrames = false;
        if (g_room != nullptr) {
            g_room->setDelegate(nullptr);
        }
        stopAllRemoteReadersLocked();
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
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    resetRoomLocked();
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
                std::lock_guard<std::mutex> sessionLock(g_sessionMutex);
                setRawErrorLocked("NATIVE_EXCEPTION", "NativeMediaEventSink.onNativeEvent not found", "nativeSetEventSink", false);
                return kStatusNativeException;
            }

            g_eventSink = newSink;
            g_eventSinkMethod = method;
        }
    }

    std::lock_guard<std::mutex> lock(g_sessionMutex);
    clearRawErrorLocked();
    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeSetMixedAudioSink(JNIEnv* env, jclass, jobject sink) {
    if (g_javaVm == nullptr) {
        env->GetJavaVM(&g_javaVm);
    }

    g_audioMixer.stop();
    {
        std::lock_guard<std::mutex> lock(g_mixedAudioSinkMutex);
        if (g_mixedAudioSink != nullptr) {
            env->DeleteGlobalRef(g_mixedAudioSink);
            g_mixedAudioSink = nullptr;
            g_mixedAudioSinkMethod = nullptr;
        }

        if (sink != nullptr) {
            jobject newSink = env->NewGlobalRef(sink);
            jclass sinkClass = env->GetObjectClass(sink);
            jmethodID method = env->GetMethodID(sinkClass, "onMixedAudioFrame", "(JJ[BIIIJ)V");
            env->DeleteLocalRef(sinkClass);

            if (method == nullptr) {
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                }
                env->DeleteGlobalRef(newSink);
                std::lock_guard<std::mutex> sessionLock(g_sessionMutex);
                setRawErrorLocked("NATIVE_EXCEPTION", "NativeMixedAudioSink.onMixedAudioFrame not found", "nativeSetMixedAudioSink", false);
                return kStatusNativeException;
            }

            g_mixedAudioSink = newSink;
            g_mixedAudioSinkMethod = method;
        }
    }

    if (sink != nullptr) {
        g_audioMixer.start();
    }

    std::lock_guard<std::mutex> lock(g_sessionMutex);
    clearRawErrorLocked();
    return kStatusOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeSetRemoteVideoSink(JNIEnv*, jclass, jobject) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    clearRawErrorLocked();
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
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeConnectAsync", false);
        return kStatusStaleEvent;
    }

    const std::string urlString = jStringToString(env, url);
    const std::string tokenString = jStringToString(env, token);
    const std::string identityString = jStringToString(env, identity);
    if (urlString.empty() || tokenString.empty()) {
        {
            std::lock_guard<std::mutex> lock(g_sessionMutex);
            g_rawEpoch = epoch;
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
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_rawEpoch = epoch;
        g_rawDisconnecting = false;
        clearRawErrorLocked();
    }
    g_sessionDestroyed = false;
    g_callbackShutdown = false;

    dispatchSimpleNativeEvent(env, "livekit/NativeMediaEvent$Connecting", sessionId, epoch);
    std::thread([sessionId, epoch, urlString, tokenString, identityString] {
        std::string errorMessage;
        const bool connected = connectRoom(urlString, tokenString, identityString, &errorMessage);

        bool shouldDispatch = false;
        bool cleanupDisconnectedStaleConnect = false;
        {
            std::lock_guard<std::mutex> lock(g_sessionMutex);
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
            std::lock_guard<std::mutex> lock(g_sessionMutex);
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
    }).detach();

    return kStatusOk;
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
Java_livekit_OrbitLiveKitNativeRaw_nativeDisconnect(
    JNIEnv* env,
    jclass,
    jlong sessionId,
    jlong epoch,
    jboolean userInitiated) {
    if (!isRawSessionValid(sessionId)) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeDisconnect", false);
        return kStatusStaleEvent;
    }

    g_callbackShutdown = true;
    g_audioMixer.stop();
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_rawEpoch = epoch;
        g_rawDisconnecting = true;
        g_acceptingFrames = false;
        if (g_room != nullptr) {
            g_room->setDelegate(nullptr);
        }
        stopAllRemoteReadersLocked();
        resetRoomLocked();
        clearRawErrorLocked();
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
    jboolean expectedTestTone) {
    if (!isRawSessionValid(sessionId)) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativePublishAudio", false);
        return kStatusStaleEvent;
    }

    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_rawEpoch = epoch;
        clearRawErrorLocked();
    }

    const jboolean published =
        Java_livekit_LiveKitNativeAudioBridge_nativePublishAudio(env, nullptr, sampleRate, channels, kFrameMs, queueSizeMs, expectedTestTone);
    if (published == JNI_TRUE) {
        dispatchSimpleNativeEvent(env, "livekit/NativeMediaEvent$AudioPublished", sessionId, epoch);
        return kStatusOk;
    }

    std::lock_guard<std::mutex> lock(g_sessionMutex);
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
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativePushAudioFrame", false);
        return kStatusStaleEvent;
    }

    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
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

    std::lock_guard<std::mutex> lock(g_sessionMutex);
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
    jint format) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    if (!isRawSessionValid(sessionId)) {
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativePublishVideo", false);
        return kStatusStaleEvent;
    }
    g_rawEpoch = epoch;
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
        livekit::VideoEncodingOptions videoEncoding;
        videoEncoding.max_framerate = static_cast<double>(g_videoFps);
        publishOptions.video_encoding = videoEncoding;

        std::cout << "VideoPublish publish started "
                  << "trackName=camera "
                  << "trackSource=SOURCE_CAMERA "
                  << "width=" << g_videoWidth
                  << " height=" << g_videoHeight
                  << " fps=" << g_videoFps
                  << " format=I420"
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

    dispatchSimpleNativeEvent(env, "livekit/NativeMediaEvent$VideoPublished", sessionId, epoch);
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
        std::lock_guard<std::mutex> lock(g_sessionMutex);
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
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("VIDEO_CAPTURE_FAILED", "Missing native video frame buffer or source", "nativePushVideoFrame", true);
        return kStatusVideoCaptureFailed;
    }

    void* data = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (data == nullptr || capacity <= 0) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
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
        std::lock_guard<std::mutex> lock(g_sessionMutex);
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
            std::lock_guard<std::mutex> lock(g_sessionMutex);
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
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("VIDEO_CAPTURE_FAILED", std::string("Native video captureFrame failed: ") + error.what(), "nativePushVideoFrame", true);
        return kStatusVideoCaptureFailed;
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("VIDEO_CAPTURE_FAILED", "Native video captureFrame failed", "nativePushVideoFrame", true);
        return kStatusVideoCaptureFailed;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeSetAudioMuted(JNIEnv*, jclass, jlong sessionId, jlong epoch, jboolean muted) {
    std::shared_ptr<livekit::LocalAudioTrack> track;
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
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
        return kStatusOk;
    }

    try {
        if (muted == JNI_TRUE) {
            track->mute();
        } else {
            track->unmute();
        }
        std::cout << "DEBUG [OrbitLiveKitAudio] native mic muted="
                  << (muted == JNI_TRUE ? "true" : "false")
                  << " trackMuted=" << (track->muted() ? "true" : "false")
                  << std::endl;
        return kStatusOk;
    } catch (const std::exception& error) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("NATIVE_EXCEPTION", std::string("Native audio mute failed: ") + error.what(), "nativeSetAudioMuted", true);
        return kStatusNativeException;
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("NATIVE_EXCEPTION", "Native audio mute failed", "nativeSetAudioMuted", true);
        return kStatusNativeException;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeSetVideoMuted(JNIEnv*, jclass, jlong sessionId, jlong epoch, jboolean muted) {
    std::shared_ptr<livekit::LocalVideoTrack> track;
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
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
        if (muted == JNI_TRUE) {
            track->mute();
        } else {
            track->unmute();
        }
        std::cout << "DEBUG [VideoPublish] native video muted="
                  << (muted == JNI_TRUE ? "true" : "false")
                  << " trackMuted=" << (track->muted() ? "true" : "false")
                  << " trackName=camera"
                  << " trackSource=SOURCE_CAMERA"
                  << std::endl;
        return kStatusOk;
    } catch (const std::exception& error) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("NATIVE_EXCEPTION", std::string("Native video mute failed: ") + error.what(), "nativeSetVideoMuted", true);
        return kStatusNativeException;
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("NATIVE_EXCEPTION", "Native video mute failed", "nativeSetVideoMuted", true);
        return kStatusNativeException;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeGetLastErrorJson(JNIEnv* env, jclass, jlong) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    return env->NewStringUTF(g_rawLastErrorJson.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativeGetReadinessJson(JNIEnv* env, jclass, jlong sessionId) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    const std::string readinessJson = rawReadinessJsonLocked(sessionId);
    return env->NewStringUTF(readinessJson.c_str());
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    g_javaVm = vm;
    g_sessionDestroyed = false;
    g_callbackShutdown = false;
    return JNI_VERSION_1_8;
}

extern "C" JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM*, void*) {
    g_callbackShutdown = true;
    g_sessionDestroyed = true;
    g_javaVm = nullptr;
}
