#include "OrbitAudioDeviceModule.h"

namespace orbit {

void OrbitAudioDeviceModule::SetSystemAudioShareEnabled(bool enabled) { is_system_audio_shared_.store(enabled); }
bool OrbitAudioDeviceModule::IsSystemAudioShareEnabled() const { return is_system_audio_shared_.load(); }

int32_t OrbitAudioDeviceModule::ActiveAudioLayer(AudioLayer* audioLayer) const { *audioLayer = AudioLayer::kWindowsCoreAudio; return 0; }
int32_t OrbitAudioDeviceModule::RegisterAudioCallback(webrtc::AudioTransport* audioCallback) { audio_callback_ = audioCallback; return 0; }
int32_t OrbitAudioDeviceModule::Init() { initialized_ = true; return 0; }
int32_t OrbitAudioDeviceModule::Terminate() { initialized_ = false; return 0; }
bool OrbitAudioDeviceModule::Initialized() const { return initialized_; }

int16_t OrbitAudioDeviceModule::PlayoutDevices() { return 0; }
int16_t OrbitAudioDeviceModule::RecordingDevices() { return 1; }
int32_t OrbitAudioDeviceModule::PlayoutDeviceName(uint16_t index, char name[webrtc::kAdmMaxDeviceNameSize], char guid[webrtc::kAdmMaxGuidSize]) { return -1; }
int32_t OrbitAudioDeviceModule::RecordingDeviceName(uint16_t index, char name[webrtc::kAdmMaxDeviceNameSize], char guid[webrtc::kAdmMaxGuidSize]) { return 0; }

int32_t OrbitAudioDeviceModule::SetPlayoutDevice(uint16_t index) { return 0; }
int32_t OrbitAudioDeviceModule::SetPlayoutDevice(WindowsDeviceType device) { return 0; }
int32_t OrbitAudioDeviceModule::SetRecordingDevice(uint16_t index) { return 0; }
int32_t OrbitAudioDeviceModule::SetRecordingDevice(WindowsDeviceType device) { return 0; }

int32_t OrbitAudioDeviceModule::PlayoutIsAvailable(bool* available) { *available = false; return 0; }
int32_t OrbitAudioDeviceModule::InitPlayout() { return 0; }
bool OrbitAudioDeviceModule::PlayoutIsInitialized() const { return true; }
int32_t OrbitAudioDeviceModule::RecordingIsAvailable(bool* available) { *available = true; return 0; }
int32_t OrbitAudioDeviceModule::InitRecording() { return 0; }
bool OrbitAudioDeviceModule::RecordingIsInitialized() const { return true; }

int32_t OrbitAudioDeviceModule::StartPlayout() { playing_ = true; return 0; }
int32_t OrbitAudioDeviceModule::StopPlayout() { playing_ = false; return 0; }
bool OrbitAudioDeviceModule::Playing() const { return playing_; }
int32_t OrbitAudioDeviceModule::StartRecording() { recording_ = true; return 0; }
int32_t OrbitAudioDeviceModule::StopRecording() { recording_ = false; return 0; }
bool OrbitAudioDeviceModule::Recording() const { return recording_; }

int32_t OrbitAudioDeviceModule::InitSpeaker() { return 0; }
bool OrbitAudioDeviceModule::SpeakerIsInitialized() const { return true; }
int32_t OrbitAudioDeviceModule::InitMicrophone() { return 0; }
bool OrbitAudioDeviceModule::MicrophoneIsInitialized() const { return true; }

int32_t OrbitAudioDeviceModule::SpeakerVolumeIsAvailable(bool* available) { *available = false; return 0; }
int32_t OrbitAudioDeviceModule::SetSpeakerVolume(uint32_t volume) { return -1; }
int32_t OrbitAudioDeviceModule::SpeakerVolume(uint32_t* volume) const { return -1; }
int32_t OrbitAudioDeviceModule::MaxSpeakerVolume(uint32_t* maxVolume) const { return -1; }
int32_t OrbitAudioDeviceModule::MinSpeakerVolume(uint32_t* minVolume) const { return -1; }

int32_t OrbitAudioDeviceModule::MicrophoneVolumeIsAvailable(bool* available) { *available = false; return 0; }
int32_t OrbitAudioDeviceModule::SetMicrophoneVolume(uint32_t volume) { return -1; }
int32_t OrbitAudioDeviceModule::MicrophoneVolume(uint32_t* volume) const { return -1; }
int32_t OrbitAudioDeviceModule::MaxMicrophoneVolume(uint32_t* maxVolume) const { return -1; }
int32_t OrbitAudioDeviceModule::MinMicrophoneVolume(uint32_t* minVolume) const { return -1; }

int32_t OrbitAudioDeviceModule::SpeakerMuteIsAvailable(bool* available) { *available = false; return 0; }
int32_t OrbitAudioDeviceModule::SetSpeakerMute(bool enable) { return -1; }
int32_t OrbitAudioDeviceModule::SpeakerMute(bool* enabled) const { return -1; }

int32_t OrbitAudioDeviceModule::MicrophoneMuteIsAvailable(bool* available) { *available = false; return 0; }
int32_t OrbitAudioDeviceModule::SetMicrophoneMute(bool enable) { return -1; }
int32_t OrbitAudioDeviceModule::MicrophoneMute(bool* enabled) const { return -1; }

int32_t OrbitAudioDeviceModule::StereoPlayoutIsAvailable(bool* available) const { *available = false; return 0; }
int32_t OrbitAudioDeviceModule::SetStereoPlayout(bool enable) { return -1; }
int32_t OrbitAudioDeviceModule::StereoPlayout(bool* enabled) const { return -1; }
int32_t OrbitAudioDeviceModule::StereoRecordingIsAvailable(bool* available) const { *available = true; return 0; }
int32_t OrbitAudioDeviceModule::SetStereoRecording(bool enable) { return 0; }
int32_t OrbitAudioDeviceModule::StereoRecording(bool* enabled) const { *enabled = true; return 0; }

int32_t OrbitAudioDeviceModule::PlayoutDelay(uint16_t* delayMS) const { *delayMS = 0; return 0; }

void OrbitAudioDeviceModule::AddRef() const {
    ref_count_.fetch_add(1, std::memory_order_relaxed);
}

webrtc::RefCountReleaseStatus OrbitAudioDeviceModule::Release() const {
    if (ref_count_.fetch_sub(1, std::memory_order_acq_rel) == 1) {
        delete this;
        return webrtc::RefCountReleaseStatus::kDroppedLastRef;
    }
    return webrtc::RefCountReleaseStatus::kOtherRefsRemained;
}

} // namespace orbit
