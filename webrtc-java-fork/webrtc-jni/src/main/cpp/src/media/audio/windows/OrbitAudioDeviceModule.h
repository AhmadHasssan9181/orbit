#pragma once

#include <atomic>
#include <stdint.h>
#include "modules/audio_device/include/audio_device.h"
#include "api/scoped_refptr.h"
#include <mutex>
#include <thread>
#include <vector>

#include <mmdeviceapi.h>
#include <audioclient.h>
#include "rnnoise.h"

namespace orbit {

class AudioDucker;

class OrbitAudioDeviceModule : public webrtc::AudioDeviceModule, public webrtc::AudioTransport {
public:
    OrbitAudioDeviceModule(webrtc::scoped_refptr<webrtc::AudioDeviceModule> core_adm);
    ~OrbitAudioDeviceModule() override;

    // Custom Flags
    void SetSystemAudioShareEnabled(bool enabled);
    bool IsSystemAudioShareEnabled() const;
    
    void SetOrbitZeroEnabled(bool enabled);
    bool IsOrbitZeroEnabled() const;

    void SetAttenuationStrength(float strength);
    float GetAttenuationStrength() const;

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
    bool BuiltInAECIsAvailable() const override;
    int32_t EnableBuiltInAEC(bool enable) override;
    
    bool BuiltInAGCIsAvailable() const override;
    int32_t EnableBuiltInAGC(bool enable) override;
    
    bool BuiltInNSIsAvailable() const override;
    int32_t EnableBuiltInNS(bool enable) override;

    // --- AudioTransport Implementation ---
    int32_t RecordedDataIsAvailable(
        const void* audioSamples,
        const size_t nSamples,
        const size_t nBytesPerSample,
        const size_t nChannels,
        const uint32_t samplesPerSec,
        const uint32_t totalDelayMS,
        const int32_t clockDrift,
        const uint32_t currentMicLevel,
        const bool keyPressed,
        uint32_t& newMicLevel) override;

    int32_t NeedMorePlayData(
        const size_t nSamples,
        const size_t nBytesPerSample,
        const size_t nChannels,
        const uint32_t samplesPerSec,
        void* audioSamples,
        size_t& nSamplesOut,
        int64_t* elapsed_time_ms,
        int64_t* ntp_time_ms) override;

    void PullRenderData(
        int bits_per_sample,
        int sample_rate,
        size_t number_of_channels,
        size_t number_of_frames,
        void* audio_data,
        int64_t* elapsed_time_ms,
        int64_t* ntp_time_ms) override;

private:
    webrtc::scoped_refptr<webrtc::AudioDeviceModule> core_adm_;
    webrtc::AudioTransport* real_audio_callback_ = nullptr;
    std::atomic<bool> is_system_audio_shared_{false};

    mutable std::atomic<int> ref_count_{0};

    // WASAPI Loopback Capture
    std::thread loopback_thread_;
    std::atomic<bool> loopback_active_{false};
    std::mutex audio_mutex_;
    std::vector<int16_t> loopback_buffer_;

    void LoopbackThreadMain();
    void StartLoopback();
    void StopLoopback();
    
    // RNNoise (Orbit Zero)
    DenoiseState* rnnoise_state_ = nullptr;
    std::atomic<bool> orbit_zero_enabled_{true};

    // Custom Audio Ducking
    AudioDucker* audio_ducker_ = nullptr;
    std::atomic<int> ducking_holdover_frames_{0};
    std::atomic<float> attenuation_strength_{0.8f};
};

} // namespace orbit
