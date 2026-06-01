#include <jni.h>

#include "livekit/livekit.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cmath>
#include <cstring>
#include <deque>
#include <exception>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace {

std::once_flag g_initializeOnce;
std::mutex g_sessionMutex;
std::unique_ptr<livekit::Room> g_room;
std::unique_ptr<livekit::RoomDelegate> g_delegate;
std::shared_ptr<livekit::AudioSource> g_audioSource;
std::shared_ptr<livekit::LocalAudioTrack> g_audioTrack;
bool g_connected = false;
bool g_micPublished = false;
bool g_acceptingFrames = false;
bool g_firstAudioFrameAccepted = false;
bool g_frameDroppedNotReadyLogged = false;

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
constexpr jint kFrameMs = 10;
constexpr jint kSamplesPerChannel = 480;
constexpr jint kFrameBytes = 960;
constexpr std::size_t kTargetParticipantQueueSamples = kSamplesPerChannel * 6; // 60 ms target playout depth
constexpr std::size_t kMaxParticipantQueueSamples = kSamplesPerChannel * 12; // 120 ms
constexpr jlong kRawSessionId = 1;
jlong g_rawEpoch = 0;
bool g_rawDisconnecting = false;

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

bool getJniEnv(JNIEnv** env, bool* attached) {
    *env = nullptr;
    *attached = false;
    if (g_javaVm == nullptr) {
        setRawErrorThreadSafe("NATIVE_EXCEPTION", "JavaVM is not available for JNI callback", "JNI_ATTACH", true);
        std::cerr << "ERROR [OrbitLiveKitAudio] JavaVM is not available for JNI callback" << std::endl;
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

    JNIEnv* env = nullptr;
    bool attached = false;
    if (!getJniEnv(&env, &attached) || env == nullptr) {
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
    JNIEnv* env = nullptr;
    bool attached = false;
    if (!getJniEnv(&env, &attached) || env == nullptr) {
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

class OrbitAudioRoomDelegate final : public livekit::RoomDelegate {
public:
    void onTrackSubscribed(livekit::Room&, const livekit::TrackSubscribedEvent& event) override {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        const std::string identity = event.participant != nullptr
            ? event.participant->identity()
            : std::string{};
        startRemoteReaderLocked(event.track, identity);
    }

    void onTrackUnsubscribed(livekit::Room&, const livekit::TrackUnsubscribedEvent& event) override {
        if (event.track == nullptr) {
            return;
        }
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        stopRemoteReaderLocked(event.track->sid());
    }

    void onParticipantDisconnected(livekit::Room&, const livekit::ParticipantDisconnectedEvent& event) override {
        if (event.participant == nullptr) {
            return;
        }
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        stopRemoteReadersForParticipantLocked(event.participant->identity());
    }

    void onDisconnected(livekit::Room&, const livekit::DisconnectedEvent&) override {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_acceptingFrames = false;
        g_micPublished = false;
        stopAllRemoteReadersLocked();
        g_connected = false;
    }
};

void resetRoomLocked() {
    g_acceptingFrames = false;
    stopAllRemoteReadersLocked();
    g_audioTrack.reset();
    g_audioSource.reset();
    if (g_room != nullptr) {
        g_room->setDelegate(nullptr);
    }
    g_delegate.reset();
    g_room.reset();
    g_connected = false;
    g_micPublished = false;
    g_firstAudioFrameAccepted = false;
    g_frameDroppedNotReadyLogged = false;
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

void logFrameDroppedNotReadyLocked() {
    if (!g_frameDroppedNotReadyLogged) {
        g_frameDroppedNotReadyLogged = true;
        std::cout << "DEBUG [OrbitLiveKitAudio] frame dropped because native audio not ready" << std::endl;
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
    const bool acceptingAudioFrames = validSession && hasReadyMicLocked();
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

    return std::string("{\"sessionId\":") + std::to_string(sessionId) +
        ",\"epoch\":" + std::to_string(g_rawEpoch) +
        ",\"state\":\"" + state +
        "\",\"connected\":" + (connected ? "true" : "false") +
        ",\"audioPublished\":" + (audioPublished ? "true" : "false") +
        ",\"videoPublished\":false" +
        ",\"acceptingAudioFrames\":" + (acceptingAudioFrames ? "true" : "false") +
        ",\"acceptingVideoFrames\":false" +
        ",\"cameraRequested\":false" +
        ",\"cameraRequired\":false" +
        ",\"cameraState\":\"NotRequested\"" +
        ",\"joinedAsAudioOnlyFallback\":false" +
        ",\"tokenRefreshScheduled\":false" +
        ",\"lastError\":" + (g_rawHasLastError ? g_rawLastErrorJson : "null") +
        "}";
}

bool connectRoom(const std::string& urlString, const std::string& tokenString, std::string* errorMessage) {
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

        for (const auto& participant : g_room->remoteParticipants()) {
            if (participant == nullptr) {
                continue;
            }
            for (const auto& publicationEntry : participant->trackPublications()) {
                const auto& publication = publicationEntry.second;
                if (publication == nullptr || !publication->subscribed()) {
                    continue;
                }
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
    const std::string urlString = jStringToString(env, url);
    const std::string tokenString = jStringToString(env, token);
    std::string errorMessage;
    return connectRoom(urlString, tokenString, &errorMessage) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativeDisconnect(JNIEnv*, jclass) {
    g_audioMixer.stop();
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    g_acceptingFrames = false;
    resetRoomLocked();
    std::cout << "DEBUG [OrbitLiveKitAudio] disconnect cleanup completed" << std::endl;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_livekit_LiveKitNativeAudioBridge_nativePublishAudio(
    JNIEnv*, jclass, jint sampleRate, jint channels, jint frameMs) {
    std::cout << "DEBUG [OrbitLiveKitAudio] mic publish started" << std::endl;
    if (sampleRate != kSampleRate || channels != kChannels || frameMs != kFrameMs) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_acceptingFrames = false;
        g_micPublished = false;
        std::cerr << "ERROR [OrbitLiveKitAudio] Invalid audio publish format; expected 48000 Hz mono 10 ms" << std::endl;
        return JNI_FALSE;
    }

    try {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_acceptingFrames = false;
        g_micPublished = false;
        g_firstAudioFrameAccepted = false;
        g_frameDroppedNotReadyLogged = false;

        if (!g_connected || g_room == nullptr || g_room->localParticipant() == nullptr) {
            std::cerr << "ERROR [OrbitLiveKitAudio] Cannot publish audio before room connect" << std::endl;
            return JNI_FALSE;
        }

        g_audioTrack.reset();
        g_audioSource = std::make_shared<livekit::AudioSource>(
            static_cast<int>(sampleRate),
            static_cast<int>(channels),
            0);
        g_audioTrack = g_room->localParticipant()->publishAudioTrack(
            "microphone",
            g_audioSource,
            livekit::TrackSource::SOURCE_MICROPHONE);

        if (g_audioSource == nullptr || g_audioTrack == nullptr) {
            g_audioTrack.reset();
            g_audioSource.reset();
            std::cerr << "ERROR [OrbitLiveKitAudio] Native C++ microphone publish returned no track" << std::endl;
            return JNI_FALSE;
        }

        g_micPublished = true;
        g_acceptingFrames = true;
        std::cout << "DEBUG [OrbitLiveKitAudio] mic publish succeeded" << std::endl;
        std::cout << "DEBUG [OrbitLiveKitAudio] acceptingFrames=true" << std::endl;
        return JNI_TRUE;
    } catch (const std::exception& error) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_acceptingFrames = false;
        g_micPublished = false;
        g_audioTrack.reset();
        g_audioSource.reset();
        std::cerr << "ERROR [OrbitLiveKitAudio] Publish audio threw: " << error.what() << std::endl;
        return JNI_FALSE;
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_acceptingFrames = false;
        g_micPublished = false;
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

    if (byteLength != kFrameBytes ||
        sampleRate != kSampleRate ||
        channels != kChannels ||
        samplesPerChannel != kSamplesPerChannel) {
        return JNI_FALSE;
    }

    std::shared_ptr<livekit::AudioSource> source;
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        if (!hasReadyMicLocked()) {
            logFrameDroppedNotReadyLocked();
            return JNI_FALSE;
        }
        source = g_audioSource;
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

        livekit::AudioFrame frame(
            std::move(samples),
            static_cast<int>(sampleRate),
            static_cast<int>(channels),
            static_cast<int>(samplesPerChannel));
        source->captureFrame(frame, 20);
        {
            std::lock_guard<std::mutex> lock(g_sessionMutex);
            if (!g_firstAudioFrameAccepted) {
                g_firstAudioFrameAccepted = true;
                std::cout << "DEBUG [OrbitLiveKitAudio] first audio frame accepted" << std::endl;
            }
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
    g_rawDisconnecting = true;
    g_acceptingFrames = false;
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
    } else {
        g_audioMixer.stop();
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
    jstring) {
    if (!isRawSessionValid(sessionId)) {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeConnectAsync", false);
        return kStatusStaleEvent;
    }

    const std::string urlString = jStringToString(env, url);
    const std::string tokenString = jStringToString(env, token);
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

    dispatchSimpleNativeEvent(env, "livekit/NativeMediaEvent$Connecting", sessionId, epoch);
    std::thread([sessionId, epoch, urlString, tokenString] {
        JNIEnv* workerEnv = nullptr;
        bool attached = false;
        getJniEnv(&workerEnv, &attached);
        logJniAttachSuccess("native-event", attached);

        std::string errorMessage;
        const bool connected = connectRoom(urlString, tokenString, &errorMessage);

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

    g_audioMixer.stop();
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_rawEpoch = epoch;
        g_rawDisconnecting = true;
        g_acceptingFrames = false;
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
    jint channels) {
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
        Java_livekit_LiveKitNativeAudioBridge_nativePublishAudio(env, nullptr, sampleRate, channels, kFrameMs);
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
    JNIEnv*,
    jclass,
    jlong sessionId,
    jlong epoch,
    jint,
    jint,
    jint,
    jint) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    if (!isRawSessionValid(sessionId)) {
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativePublishVideo", false);
        return kStatusStaleEvent;
    }
    g_rawEpoch = epoch;
    setRawErrorLocked("PUBLISH_VIDEO_FAILED", "Native C++ video is not implemented in this session", "nativePublishVideo", true);
    return kStatusPublishVideoFailed;
}

extern "C" JNIEXPORT jint JNICALL
Java_livekit_OrbitLiveKitNativeRaw_nativePushVideoFrame(
    JNIEnv*,
    jclass,
    jlong sessionId,
    jlong epoch,
    jobject,
    jint,
    jint,
    jint,
    jint,
    jint,
    jint,
    jlong,
    jint) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    if (!isRawSessionValid(sessionId)) {
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativePushVideoFrame", false);
        return kStatusStaleEvent;
    }
    if (epoch != g_rawEpoch) {
        setRawErrorLocked("STALE_EVENT", "Stale native media epoch", "nativePushVideoFrame", true);
        return kStatusStaleEvent;
    }
    setRawErrorLocked("VIDEO_CAPTURE_FAILED", "Native C++ video is not implemented in this session", "nativePushVideoFrame", true);
    return kStatusVideoCaptureFailed;
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
Java_livekit_OrbitLiveKitNativeRaw_nativeSetVideoMuted(JNIEnv*, jclass, jlong sessionId, jlong epoch, jboolean) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    if (!isRawSessionValid(sessionId)) {
        setRawErrorLocked("STALE_EVENT", "Unknown native media session", "nativeSetVideoMuted", false);
        return kStatusStaleEvent;
    }
    g_rawEpoch = epoch;
    clearRawErrorLocked();
    return kStatusOk;
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
    return JNI_VERSION_1_8;
}
