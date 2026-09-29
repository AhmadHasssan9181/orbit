#include "livekit/livekit.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <exception>
#include <iomanip>
#include <iostream>
#include <limits>
#include <memory>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr int kSampleRate = 48000;
constexpr int kDefaultChannels = 2;
constexpr int kSamplesPerChannel = 480;
constexpr int kQueueSizeMs = 10;
constexpr int kToneHz = 1000;
constexpr int kTonePeak = 12000;
constexpr double kPi = 3.14159265358979323846;

struct PcmStats {
  int min = 0;
  int max = 0;
  int peak = 0;
  double rms = 0.0;
  bool allZero = true;
};

std::string getArgOrEnv(int argc, char** argv, int index, const char* envName) {
  if (argc > index && argv[index] != nullptr && argv[index][0] != '\0') {
    return argv[index];
  }
#ifdef _WIN32
  char* value = nullptr;
  std::size_t valueSize = 0;
  if (_dupenv_s(&value, &valueSize, envName) != 0 || value == nullptr) {
    return {};
  }
  std::string result(value);
  std::free(value);
  return result;
#else
  const char* value = std::getenv(envName);
  return value == nullptr ? std::string() : std::string(value);
#endif
}

int getDurationSeconds(int argc, char** argv) {
  std::string value = getArgOrEnv(argc, argv, 3, "ORBIT_LIVEKIT_TONE_SECONDS");
  if (value.empty()) {
    return 120;
  }
  try {
    return std::max(1, std::stoi(value));
  } catch (const std::exception&) {
    return 120;
  }
}

int getToneChannels(int argc, char** argv) {
  std::string value = getArgOrEnv(argc, argv, 4, "ORBIT_LIVEKIT_TONE_CHANNELS");
  if (value.empty()) {
    return kDefaultChannels;
  }
  try {
    const int parsed = std::stoi(value);
    if (parsed == 1 || parsed == 2) {
      return parsed;
    }
  } catch (const std::exception&) {
  }
  return kDefaultChannels;
}

PcmStats computeStats(const std::vector<std::int16_t>& samples) {
  PcmStats stats;
  if (samples.empty()) {
    return stats;
  }

  int minSample = std::numeric_limits<int>::max();
  int maxSample = std::numeric_limits<int>::min();
  long double sumSquares = 0.0;
  bool allZero = true;

  for (std::int16_t sample : samples) {
    const int value = static_cast<int>(sample);
    minSample = std::min(minSample, value);
    maxSample = std::max(maxSample, value);
    stats.peak = std::max(stats.peak, std::abs(value));
    sumSquares += static_cast<long double>(value) * static_cast<long double>(value);
    if (value != 0) {
      allZero = false;
    }
  }

  stats.min = minSample;
  stats.max = maxSample;
  stats.rms = std::sqrt(static_cast<double>(sumSquares / samples.size()));
  stats.allZero = allZero;
  return stats;
}

std::string firstSamples(const std::vector<std::int16_t>& samples, std::size_t count = 10) {
  std::ostringstream out;
  out << "[";
  const std::size_t limit = std::min(count, samples.size());
  for (std::size_t i = 0; i < limit; ++i) {
    if (i > 0) {
      out << ",";
    }
    out << samples[i];
  }
  out << "]";
  return out.str();
}

const char* connectionStateName(livekit::ConnectionState state) {
  switch (state) {
  case livekit::ConnectionState::Disconnected:
    return "Disconnected";
  case livekit::ConnectionState::Connected:
    return "Connected";
  case livekit::ConnectionState::Reconnecting:
    return "Reconnecting";
  }
  return "Unknown";
}

const char* trackSourceName(livekit::TrackSource source) {
  switch (source) {
  case livekit::TrackSource::SOURCE_UNKNOWN:
    return "SOURCE_UNKNOWN";
  case livekit::TrackSource::SOURCE_CAMERA:
    return "SOURCE_CAMERA";
  case livekit::TrackSource::SOURCE_MICROPHONE:
    return "SOURCE_MICROPHONE";
  case livekit::TrackSource::SOURCE_SCREENSHARE:
    return "SOURCE_SCREENSHARE";
  case livekit::TrackSource::SOURCE_SCREENSHARE_AUDIO:
    return "SOURCE_SCREENSHARE_AUDIO";
  }
  return "SOURCE_UNKNOWN";
}

std::string trackSourceName(const std::optional<livekit::TrackSource>& source) {
  return source.has_value() ? trackSourceName(*source) : "<none>";
}

class TonePublisherDelegate final : public livekit::RoomDelegate {
public:
  void onConnectionStateChanged(livekit::Room&, const livekit::ConnectionStateChangedEvent& event) override {
    std::cout << "[tone] room state=" << connectionStateName(event.state) << std::endl;
  }

  void onLocalTrackPublished(livekit::Room&, const livekit::LocalTrackPublishedEvent& event) override {
    std::cout << "[tone] local track published";
    if (event.publication) {
      std::cout << " publicationSid=" << event.publication->sid()
                << " name=" << event.publication->name()
                << " source=" << trackSourceName(event.publication->source())
                << " muted=" << (event.publication->muted() ? "true" : "false");
    }
    if (event.track) {
      std::cout << " trackSid=" << event.track->sid()
                << " trackName=" << event.track->name()
                << " trackMuted=" << (event.track->muted() ? "true" : "false")
                << " trackSource=" << trackSourceName(event.track->source());
    }
    std::cout << std::endl;
  }

  void onDisconnected(livekit::Room&, const livekit::DisconnectedEvent&) override {
    std::cout << "[tone] room disconnected" << std::endl;
  }
};

void logPublicationSnapshot(livekit::LocalParticipant* participant) {
  if (participant == nullptr) {
    std::cout << "[tone] local participant missing" << std::endl;
    return;
  }

  std::cout << "[tone] local participant identity=" << participant->identity()
            << " sid=" << participant->sid()
            << " name=" << participant->name() << std::endl;

  const auto publications = participant->trackPublications();
  std::cout << "[tone] publication count=" << publications.size() << std::endl;
  for (const auto& entry : publications) {
    const auto& publication = entry.second;
    if (!publication) {
      continue;
    }
    std::cout << "[tone] publication sid=" << publication->sid()
              << " name=" << publication->name()
              << " source=" << trackSourceName(publication->source())
              << " muted=" << (publication->muted() ? "true" : "false")
              << " mimeType=" << publication->mimeType() << std::endl;
  }
}

// Owns initialize()/shutdown() for everything created after it. main declares it
// BEFORE the SDK objects, so it is destroyed AFTER them: livekit::shutdown() now
// runs last. Calling shutdown() first left the Room/AudioSource/LocalAudioTrack
// destructors running against an already torn-down runtime.
class SdkSession {
public:
  SdkSession() {
    livekit::initialize(livekit::LogLevel::Info, livekit::LogSink::kConsole);
  }
  ~SdkSession() {
    livekit::shutdown();
  }

  SdkSession(const SdkSession&) = delete;
  SdkSession& operator=(const SdkSession&) = delete;
};

} // namespace

int main(int argc, char** argv) {
  const std::string url = getArgOrEnv(argc, argv, 1, "ORBIT_LIVEKIT_URL");
  const std::string token = getArgOrEnv(argc, argv, 2, "ORBIT_LIVEKIT_TOKEN");
  const int durationSeconds = getDurationSeconds(argc, argv);
  const int channels = getToneChannels(argc, argv);

  if (url.empty() || token.empty()) {
    std::cerr << "Usage: orbit_livekit_tone_publisher <livekit-url> <token> [durationSeconds] [channels]\n"
              << "Or set ORBIT_LIVEKIT_URL, ORBIT_LIVEKIT_TOKEN, optional ORBIT_LIVEKIT_TONE_SECONDS,"
              << " and optional ORBIT_LIVEKIT_TONE_CHANNELS."
              << std::endl;
    return 2;
  }

  std::cout << "[tone] LiveKit C++ tone publisher starting" << std::endl;
  std::cout << "[tone] AudioSource(" << kSampleRate << ", " << channels << ", " << kQueueSizeMs << ")"
            << std::endl;
  std::cout << "[tone] trackName=audio source=SOURCE_MICROPHONE durationSeconds=" << durationSeconds
            << " channels=" << channels << std::endl;

  // Constructed before the SDK objects below so it is destroyed after them.
  SdkSession sdk;

  try {
    TonePublisherDelegate delegate;
    livekit::Room room;
    room.setDelegate(&delegate);

    livekit::RoomOptions roomOptions;
    roomOptions.auto_subscribe = true;
    roomOptions.dynacast = false;
    roomOptions.single_peer_connection = true;

    std::cout << "[tone] connecting url=" << url << std::endl;
    const bool connected = room.connect(url, token, roomOptions);
    std::cout << "[tone] connect result=" << (connected ? "true" : "false")
              << " state=" << connectionStateName(room.connectionState()) << std::endl;
    if (!connected) {
      return 1;
    }

    auto* localParticipant = room.localParticipant();
    logPublicationSnapshot(localParticipant);
    if (localParticipant == nullptr) {
      std::cerr << "[tone] no local participant after connect" << std::endl;
      return 1;
    }

    auto audioSource = std::make_shared<livekit::AudioSource>(kSampleRate, channels, kQueueSizeMs);
    auto audioTrack = livekit::LocalAudioTrack::createLocalAudioTrack("audio", audioSource);
    livekit::TrackPublishOptions publishOptions;
    publishOptions.source = livekit::TrackSource::SOURCE_MICROPHONE;

    std::cout << "[tone] publishing audio track name=audio source=SOURCE_MICROPHONE" << std::endl;
    localParticipant->publishTrack(audioTrack, publishOptions);
    std::cout << "[tone] publishTrack returned trackMuted=" << (audioTrack->muted() ? "true" : "false")
              << " trackSource=" << trackSourceName(audioTrack->source()) << std::endl;
    logPublicationSnapshot(localParticipant);

    double phase = 0.0;
    const double phaseStep = 2.0 * kPi * static_cast<double>(kToneHz) / static_cast<double>(kSampleRate);
    std::uint64_t captured = 0;
    std::uint64_t failed = 0;
    auto nextTick = std::chrono::steady_clock::now();
    const auto endTime = nextTick + std::chrono::seconds(durationSeconds);

    while (std::chrono::steady_clock::now() < endTime) {
      std::vector<std::int16_t> buffer(kSamplesPerChannel * channels);
      for (int sampleIndex = 0; sampleIndex < kSamplesPerChannel; ++sampleIndex) {
        const auto sample = static_cast<std::int16_t>(std::lround(std::sin(phase) * kTonePeak));
        for (int channel = 0; channel < channels; ++channel) {
          buffer[(sampleIndex * channels) + channel] = sample;
        }
        phase += phaseStep;
        if (phase >= 2.0 * kPi) {
          phase -= 2.0 * kPi;
        }
      }

      const auto stats = computeStats(buffer);
      livekit::AudioFrame frame(buffer, kSampleRate, channels, kSamplesPerChannel);

      const bool shouldLogFrame = captured < 10 || captured % 100 == 0;
      if (shouldLogFrame) {
        std::cout << std::fixed << std::setprecision(2)
                  << "[tone] pre-capture seq=" << captured
                  << " min=" << stats.min
                  << " max=" << stats.max
                  << " peak=" << stats.peak
                  << " rms=" << stats.rms
                  << " allZero=" << (stats.allZero ? "true" : "false")
                  << " first10Samples=" << firstSamples(buffer)
                  << " frame.data.size=" << frame.data().size()
                  << " frame.samplesPerChannel=" << frame.samplesPerChannel()
                  << " frame.numChannels=" << frame.numChannels()
                  << " frame.sampleRate=" << frame.sampleRate()
                  << " frame.duration=" << frame.duration()
                  << std::endl;
      }

      try {
        audioSource->captureFrame(frame, 0);
        ++captured;
        if (shouldLogFrame) {
          std::cout << "[tone] captureFrame result=ok captured=" << captured << " failed=" << failed << std::endl;
        }
      } catch (const std::exception& ex) {
        ++failed;
        std::cerr << "[tone] captureFrame result=error message=" << ex.what()
                  << " captured=" << captured
                  << " failed=" << failed << std::endl;
      }

      nextTick += std::chrono::milliseconds(10);
      std::this_thread::sleep_until(nextTick);
    }

    std::cout << "[tone] finished captured=" << captured << " failed=" << failed << std::endl;
    return failed == 0 ? 0 : 1;
  } catch (const std::exception& ex) {
    std::cerr << "[tone] fatal error: " << ex.what() << std::endl;
    return 1;
  }
}
