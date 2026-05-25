#pragma once

#include <atomic>
#include <stdint.h>
#include "modules/audio_device/include/audio_device.h"

namespace orbit {

class OrbitAudioDeviceModule : public webrtc::AudioDeviceModule {
public:
    OrbitAudioDeviceModule() = default;
    ~OrbitAudioDeviceModule() override = default;

    // Custom Flags
    void SetSystemAudioShareEnabled(bool enabled);
    bool IsSystemAudioShareEnabled() const;

    // --- webrtc::RefCountInterface Required Implementation ---
    void AddRef() const override;
    webrtc::RefCountReleaseStatus Release() const override;

    // --- webrtc::AudioDeviceModule Required Implementation ---
    int32_t ActiveAudioLayer(AudioLayer* audioLayer) const override;
    int32_t RegisterAudioCallback(webrtc::AudioTransport* audioCallback) override;
    
    int32_t Init() override;
    int32_t Terminate() override;
    bool Initialized() const override;

    int16_t PlayoutDevices() override;
    int16_t RecordingDevices() override;
    int32_t PlayoutDeviceName(uint16_t index, char name[webrtc::kAdmMaxDeviceNameSize], char guid[webrtc::kAdmMaxGuidSize]) override;
    int32_t RecordingDeviceName(uint16_t index, char name[webrtc::kAdmMaxDeviceNameSize], char guid[webrtc::kAdmMaxGuidSize]) override;
    
    int32_t SetPlayoutDevice(uint16_t index) override;
    int32_t SetPlayoutDevice(WindowsDeviceType device) override;
    int32_t SetRecordingDevice(uint16_t index) override;
    int32_t SetRecordingDevice(WindowsDeviceType device) override;

    int32_t PlayoutIsAvailable(bool* available) override;
    int32_t InitPlayout() override;
    bool PlayoutIsInitialized() const override;
    int32_t RecordingIsAvailable(bool* available) override;
    int32_t InitRecording() override;
    bool RecordingIsInitialized() const override;

    int32_t StartPlayout() override;
    int32_t StopPlayout() override;
    bool Playing() const override;
    int32_t StartRecording() override;
    int32_t StopRecording() override;
    bool Recording() const override;

    int32_t InitSpeaker() override;
    bool SpeakerIsInitialized() const override;
    int32_t InitMicrophone() override;
    bool MicrophoneIsInitialized() const override;

    int32_t SpeakerVolumeIsAvailable(bool* available) override;
    int32_t SetSpeakerVolume(uint32_t volume) override;
    int32_t SpeakerVolume(uint32_t* volume) const override;
    int32_t MaxSpeakerVolume(uint32_t* maxVolume) const override;
    int32_t MinSpeakerVolume(uint32_t* minVolume) const override;

    int32_t MicrophoneVolumeIsAvailable(bool* available) override;
    int32_t SetMicrophoneVolume(uint32_t volume) override;
    int32_t MicrophoneVolume(uint32_t* volume) const override;
    int32_t MaxMicrophoneVolume(uint32_t* maxVolume) const override;
    int32_t MinMicrophoneVolume(uint32_t* minVolume) const override;

    int32_t SpeakerMuteIsAvailable(bool* available) override;
    int32_t SetSpeakerMute(bool enable) override;
    int32_t SpeakerMute(bool* enabled) const override;

    int32_t MicrophoneMuteIsAvailable(bool* available) override;
    int32_t SetMicrophoneMute(bool enable) override;
    int32_t MicrophoneMute(bool* enabled) const override;

    int32_t StereoPlayoutIsAvailable(bool* available) const override;
    int32_t SetStereoPlayout(bool enable) override;
    int32_t StereoPlayout(bool* enabled) const override;
    int32_t StereoRecordingIsAvailable(bool* available) const override;
    int32_t SetStereoRecording(bool enable) override;
    int32_t StereoRecording(bool* enabled) const override;

    int32_t PlayoutDelay(uint16_t* delayMS) const override;
    
    // --- Modern WebRTC AEC/AGC/NS Overrides ---
    bool BuiltInAECIsAvailable() const override { return false; }
    int32_t EnableBuiltInAEC(bool enable) override { return -1; }
    
    bool BuiltInAGCIsAvailable() const override { return false; }
    int32_t EnableBuiltInAGC(bool enable) override { return -1; }
    
    bool BuiltInNSIsAvailable() const override { return false; }
    int32_t EnableBuiltInNS(bool enable) override { return -1; }

private:
    std::atomic<bool> is_system_audio_shared_{false};
    webrtc::AudioTransport* audio_callback_ = nullptr;
    bool initialized_ = false;
    bool recording_ = false;
    bool playing_ = false;

    // Mutable because AddRef/Release are marked const by webrtc::RefCountInterface
    mutable std::atomic<int> ref_count_{0};
};

} // namespace orbit
