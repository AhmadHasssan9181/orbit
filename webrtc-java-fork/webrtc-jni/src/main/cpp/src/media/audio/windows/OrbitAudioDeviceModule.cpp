#include "OrbitAudioDeviceModule.h"
#include <comdef.h>
#include <Functiondiscoverykeys_devpkey.h>
#include <ksmedia.h>
#include <audioclientactivationparams.h>
#include <audiopolicy.h>
#include <algorithm>
#include <thread>
#include <math.h>

namespace orbit {

class AudioDucker {
public:
    AudioDucker() {}
    ~AudioDucker() {
        std::lock_guard<std::mutex> lock(mutex_);
        RestoreVolumes();
    }

    void SetSpeaking(bool is_speaking) {
        if (is_speaking == is_ducked_.load()) return;
        is_ducked_.store(is_speaking);
        
        std::thread([this, is_speaking]() {
            CoInitializeEx(NULL, COINIT_MULTITHREADED);
            std::lock_guard<std::mutex> lock(mutex_);
            
            if (is_speaking != is_ducked_.load()) {
                CoUninitialize();
                return;
            }
            
            if (is_speaking) {
                DuckVolumes();
            } else {
                RestoreVolumes();
            }
            CoUninitialize();
        }).detach();
    }

private:
    std::mutex mutex_;
    std::atomic<bool> is_ducked_{false};
    
    struct DuckedSession {
        ISimpleAudioVolume* volumeControl;
        float originalVolume;
    };
    std::vector<DuckedSession> ducked_sessions_;

    void DuckVolumes() {
        if (!ducked_sessions_.empty()) return;

        IMMDeviceEnumerator* deviceEnumerator = NULL;
        IMMDevice* defaultDevice = NULL;
        IAudioSessionManager2* sessionManager = NULL;
        IAudioSessionEnumerator* sessionEnumerator = NULL;

        if (FAILED(CoCreateInstance(__uuidof(MMDeviceEnumerator), NULL, CLSCTX_INPROC_SERVER, __uuidof(IMMDeviceEnumerator), (void**)&deviceEnumerator))) return;
        
        if (SUCCEEDED(deviceEnumerator->GetDefaultAudioEndpoint(eRender, eConsole, &defaultDevice))) {
            if (SUCCEEDED(defaultDevice->Activate(__uuidof(IAudioSessionManager2), CLSCTX_INPROC_SERVER, NULL, (void**)&sessionManager))) {
                if (SUCCEEDED(sessionManager->GetSessionEnumerator(&sessionEnumerator))) {
                    int count = 0;
                    sessionEnumerator->GetCount(&count);
                    
                    DWORD currentPid = GetCurrentProcessId();
                    
                    for (int i = 0; i < count; i++) {
                        IAudioSessionControl* sessionControl = NULL;
                        if (SUCCEEDED(sessionEnumerator->GetSession(i, &sessionControl))) {
                            IAudioSessionControl2* sessionControl2 = NULL;
                            if (SUCCEEDED(sessionControl->QueryInterface(__uuidof(IAudioSessionControl2), (void**)&sessionControl2))) {
                                DWORD pid = 0;
                                sessionControl2->GetProcessId(&pid);
                                
                                if (pid != currentPid && pid != 0) {
                                    ISimpleAudioVolume* volumeControl = NULL;
                                    if (SUCCEEDED(sessionControl->QueryInterface(__uuidof(ISimpleAudioVolume), (void**)&volumeControl))) {
                                        float currentVolume = 1.0f;
                                        volumeControl->GetMasterVolume(&currentVolume);
                                        
                                        ducked_sessions_.push_back({volumeControl, currentVolume});
                                        volumeControl->SetMasterVolume(currentVolume * 0.2f, NULL);
                                    }
                                }
                                sessionControl2->Release();
                            }
                            sessionControl->Release();
                        }
                    }
                    sessionEnumerator->Release();
                }
                sessionManager->Release();
            }
            defaultDevice->Release();
        }
        deviceEnumerator->Release();
    }

    void RestoreVolumes() {
        for (auto& session : ducked_sessions_) {
            session.volumeControl->SetMasterVolume(session.originalVolume, NULL);
            session.volumeControl->Release();
        }
        ducked_sessions_.clear();
    }
};

OrbitAudioDeviceModule::OrbitAudioDeviceModule(webrtc::scoped_refptr<webrtc::AudioDeviceModule> core_adm)
    : core_adm_(core_adm) {
    rnnoise_state_ = rnnoise_create(NULL);
    audio_ducker_ = new AudioDucker();

    // Disable Windows Communications Ducking
    IMMDeviceEnumerator* deviceEnumerator = NULL;
    IMMDevice* defaultDevice = NULL;
    IAudioSessionManager2* sessionManager = NULL;
    IAudioSessionControl* sessionControl = NULL;
    IAudioSessionControl2* sessionControl2 = NULL;

    if (SUCCEEDED(CoCreateInstance(__uuidof(MMDeviceEnumerator), NULL, CLSCTX_INPROC_SERVER, __uuidof(IMMDeviceEnumerator), (void**)&deviceEnumerator))) {
        if (SUCCEEDED(deviceEnumerator->GetDefaultAudioEndpoint(eRender, eCommunications, &defaultDevice))) {
            if (SUCCEEDED(defaultDevice->Activate(__uuidof(IAudioSessionManager2), CLSCTX_INPROC_SERVER, NULL, (void**)&sessionManager))) {
                if (SUCCEEDED(sessionManager->GetAudioSessionControl(NULL, 0, &sessionControl))) {
                    if (SUCCEEDED(sessionControl->QueryInterface(__uuidof(IAudioSessionControl2), (void**)&sessionControl2))) {
                        sessionControl2->SetDuckingPreference(TRUE); // TRUE means opt out of ducking
                        sessionControl2->Release();
                    }
                    sessionControl->Release();
                }
                sessionManager->Release();
            }
            defaultDevice->Release();
        }
        deviceEnumerator->Release();
    }
}

OrbitAudioDeviceModule::~OrbitAudioDeviceModule() {
    if (rnnoise_state_) {
        rnnoise_destroy(rnnoise_state_);
        rnnoise_state_ = nullptr;
    }
    if (audio_ducker_) {
        delete audio_ducker_;
        audio_ducker_ = nullptr;
    }
}

void OrbitAudioDeviceModule::SetSystemAudioShareEnabled(bool enabled) { 
    if (enabled == is_system_audio_shared_.load()) return;
    is_system_audio_shared_.store(enabled);
    if (enabled) {
        StartLoopback();
    } else {
        StopLoopback();
    }
}
bool OrbitAudioDeviceModule::IsSystemAudioShareEnabled() const { return is_system_audio_shared_.load(); }

void OrbitAudioDeviceModule::SetOrbitZeroEnabled(bool enabled) {
    orbit_zero_enabled_.store(enabled);
}

bool OrbitAudioDeviceModule::IsOrbitZeroEnabled() const { return orbit_zero_enabled_.load(); }

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

int32_t OrbitAudioDeviceModule::ActiveAudioLayer(AudioLayer* audioLayer) const { return core_adm_->ActiveAudioLayer(audioLayer); }

int32_t OrbitAudioDeviceModule::RegisterAudioCallback(webrtc::AudioTransport* audioCallback) { 
    real_audio_callback_ = audioCallback;
    return core_adm_->RegisterAudioCallback(this);
}

int32_t OrbitAudioDeviceModule::RecordedDataIsAvailable(
    const void* audioSamples,
    const size_t nSamples,
    const size_t nBytesPerSample,
    const size_t nChannels,
    const uint32_t samplesPerSec,
    const uint32_t totalDelayMS,
    const int32_t clockDrift,
    const uint32_t currentMicLevel,
    const bool keyPressed,
    uint32_t& newMicLevel) {

    // RMS Calculation for Audio Ducking
    const int16_t* pcm_data = static_cast<const int16_t*>(audioSamples);
    size_t total_samples = nSamples * nChannels;
    double sum_squares = 0.0;
    for (size_t i = 0; i < total_samples; ++i) {
        sum_squares += pcm_data[i] * pcm_data[i];
    }
    double rms = sqrt(sum_squares / total_samples);
    
    // Threshold is arbitrary, approx -40dBFS for 16-bit PCM is 327.6
    if (rms > 500.0) {
        ducking_holdover_frames_.store(50); // 50 frames * 10ms = 500ms holdover
        if (audio_ducker_) audio_ducker_->SetSpeaking(true);
    } else {
        int holdover = ducking_holdover_frames_.load();
        if (holdover > 0) {
            ducking_holdover_frames_.store(holdover - 1);
        } else {
            if (audio_ducker_) audio_ducker_->SetSpeaking(false);
        }
    }

    if (!is_system_audio_shared_.load() && !orbit_zero_enabled_.load()) {
        return real_audio_callback_ ? real_audio_callback_->RecordedDataIsAvailable(audioSamples, nSamples, nBytesPerSample, nChannels, samplesPerSec, totalDelayMS, clockDrift, currentMicLevel, keyPressed, newMicLevel) : 0;
    }

    std::vector<int16_t> mixed_buffer((const int16_t*)audioSamples, ((const int16_t*)audioSamples) + nSamples * nChannels);

    // Orbit Zero AI Noise Suppression
    if (orbit_zero_enabled_.load() && rnnoise_state_ && samplesPerSec == 48000 && nSamples == 480) {
        // RNNoise is mono, we apply it to the first channel
        float in_float[480];
        float out_float[480];
        
        for (size_t i = 0; i < 480; ++i) {
            in_float[i] = (float)mixed_buffer[i * nChannels];
        }
        
        rnnoise_process_frame(rnnoise_state_, out_float, in_float);
        
        for (size_t i = 0; i < 480; ++i) {
            // Write processed data back to all channels to ensure mono expansion if stereo mic
            int16_t out_pcm = (int16_t)out_float[i];
            for (size_t c = 0; c < nChannels; ++c) {
                mixed_buffer[i * nChannels + c] = out_pcm;
            }
        }
    }

    if (is_system_audio_shared_.load()) {
        std::lock_guard<std::mutex> lock(audio_mutex_);
        if (loopback_buffer_.size() >= nSamples * 2) { // Assume WASAPI loopback is always stereo (2 channels)
            // Simple mixing: add loopback samples to mic samples and clamp to 16-bit range
            for (size_t i = 0; i < nSamples; ++i) {
                for (size_t c = 0; c < nChannels; ++c) {
                    // Loopback is stereo. If mic is mono (nChannels == 1), average loopback L+R, else match channel
                    int32_t lb_sample = 0;
                    if (nChannels == 1) {
                        lb_sample = ((int32_t)loopback_buffer_[i * 2] + (int32_t)loopback_buffer_[i * 2 + 1]) / 2;
                    } else {
                        lb_sample = loopback_buffer_[i * 2 + (c % 2)];
                    }
                    int32_t mixed = (int32_t)mixed_buffer[i * nChannels + c] + lb_sample;
                    if (mixed > 32767) mixed = 32767;
                    if (mixed < -32768) mixed = -32768;
                    mixed_buffer[i * nChannels + c] = (int16_t)mixed;
                }
            }
            // Erase consumed samples (always stereo)
            loopback_buffer_.erase(loopback_buffer_.begin(), loopback_buffer_.begin() + nSamples * 2);
        }
    }

    if (real_audio_callback_) {
        return real_audio_callback_->RecordedDataIsAvailable(mixed_buffer.data(), nSamples, nBytesPerSample, nChannels, samplesPerSec, totalDelayMS, clockDrift, currentMicLevel, keyPressed, newMicLevel);
    }
    return 0;
}

int32_t OrbitAudioDeviceModule::NeedMorePlayData(
    const size_t nSamples,
    const size_t nBytesPerSample,
    const size_t nChannels,
    const uint32_t samplesPerSec,
    void* audioSamples,
    size_t& nSamplesOut,
    int64_t* elapsed_time_ms,
    int64_t* ntp_time_ms) {
    if (real_audio_callback_) {
        return real_audio_callback_->NeedMorePlayData(nSamples, nBytesPerSample, nChannels, samplesPerSec, audioSamples, nSamplesOut, elapsed_time_ms, ntp_time_ms);
    }
    return 0;
}

void OrbitAudioDeviceModule::PullRenderData(
    int bits_per_sample,
    int sample_rate,
    size_t number_of_channels,
    size_t number_of_frames,
    void* audio_data,
    int64_t* elapsed_time_ms,
    int64_t* ntp_time_ms) {
    if (real_audio_callback_) {
        real_audio_callback_->PullRenderData(bits_per_sample, sample_rate, number_of_channels, number_of_frames, audio_data, elapsed_time_ms, ntp_time_ms);
    }
}

void OrbitAudioDeviceModule::StartLoopback() {
    if (loopback_active_) return;
    loopback_active_ = true;
    loopback_thread_ = std::thread(&OrbitAudioDeviceModule::LoopbackThreadMain, this);
}

void OrbitAudioDeviceModule::StopLoopback() {
    if (!loopback_active_) return;
    loopback_active_ = false;
    if (loopback_thread_.joinable()) {
        loopback_thread_.join();
    }
}

#include <mmdeviceapi.h>
#include <audioclient.h>
#include <objbase.h>

class AudioClientActivator : public IActivateAudioInterfaceCompletionHandler {
public:
    HANDLE m_hEvent;
    IAudioClient* m_pAudioClient;
    HRESULT m_hr;

    AudioClientActivator() : m_hEvent(NULL), m_pAudioClient(NULL), m_hr(E_FAIL) {
        m_hEvent = CreateEvent(NULL, FALSE, FALSE, NULL);
    }
    ~AudioClientActivator() {
        if (m_hEvent) CloseHandle(m_hEvent);
        if (m_pAudioClient) m_pAudioClient->Release();
    }

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (riid == __uuidof(IUnknown) || riid == __uuidof(IActivateAudioInterfaceCompletionHandler)) {
            *ppv = static_cast<IActivateAudioInterfaceCompletionHandler*>(this);
            return S_OK;
        }
        *ppv = NULL;
        return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() override { return 1; }
    STDMETHODIMP_(ULONG) Release() override { return 1; }

    STDMETHODIMP ActivateCompleted(IActivateAudioInterfaceAsyncOperation* pOperation) override {
        HRESULT hrActivateResult = E_FAIL;
        IUnknown* pUnk = NULL;
        m_hr = pOperation->GetActivateResult(&hrActivateResult, &pUnk);
        if (SUCCEEDED(m_hr) && SUCCEEDED(hrActivateResult) && pUnk != NULL) {
            m_hr = pUnk->QueryInterface(__uuidof(IAudioClient), (void**)&m_pAudioClient);
            pUnk->Release();
        } else {
            m_hr = hrActivateResult;
        }
        SetEvent(m_hEvent);
        return S_OK;
    }
};

void OrbitAudioDeviceModule::LoopbackThreadMain() {
    CoInitializeEx(NULL, COINIT_MULTITHREADED);
    
    IMMDeviceEnumerator* pEnumerator = NULL;
    HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), NULL, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator), (void**)&pEnumerator);
    if (FAILED(hr)) return;

    IMMDevice* pDevice = NULL;
    hr = pEnumerator->GetDefaultAudioEndpoint(eRender, eConsole, &pDevice);
    pEnumerator->Release();
    if (FAILED(hr)) return;

    IAudioClient* pAudioClient = NULL;
    
    // Windows 10 Build 20348+ specific: Exclude current process from loopback
    AUDIOCLIENT_ACTIVATION_PARAMS params = {};
    params.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK;
    params.ProcessLoopbackParams.ProcessLoopbackMode = PROCESS_LOOPBACK_MODE_EXCLUDE_TARGET_PROCESS_TREE;
    params.ProcessLoopbackParams.TargetProcessId = GetCurrentProcessId();

    PROPVARIANT activateParams = {};
    activateParams.vt = VT_BLOB;
    activateParams.blob.cbSize = sizeof(params);
    activateParams.blob.pBlobData = (BYTE*)&params;

    LPWSTR pwszID = NULL;
    hr = pDevice->GetId(&pwszID);
    if (SUCCEEDED(hr)) {
        AudioClientActivator activator;
        IActivateAudioInterfaceAsyncOperation* asyncOp = NULL;
        
        HRESULT asyncHr = ActivateAudioInterfaceAsync(pwszID, __uuidof(IAudioClient), &activateParams, &activator, &asyncOp);
        if (SUCCEEDED(asyncHr) && asyncOp) {
            WaitForSingleObject(activator.m_hEvent, 2000); // Wait up to 2 seconds
            if (SUCCEEDED(activator.m_hr) && activator.m_pAudioClient != NULL) {
                pAudioClient = activator.m_pAudioClient;
                pAudioClient->AddRef();
            }
            asyncOp->Release();
        }
        CoTaskMemFree(pwszID);
    }

    // Fallback: If process loopback failed or isn't supported, use normal system loopback
    if (pAudioClient == NULL) {
        hr = pDevice->Activate(__uuidof(IAudioClient), CLSCTX_ALL, NULL, (void**)&pAudioClient);
        if (FAILED(hr)) {
            pDevice->Release();
            return;
        }
    }
    
    pDevice->Release();

    WAVEFORMATEX* pwfx = NULL;
    hr = pAudioClient->GetMixFormat(&pwfx);
    if (FAILED(hr)) return;

    hr = pAudioClient->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, 0, 0, pwfx, NULL);
    if (FAILED(hr)) {
        CoTaskMemFree(pwfx);
        pAudioClient->Release();
        return;
    }

    IAudioCaptureClient* pCaptureClient = NULL;
    hr = pAudioClient->GetService(__uuidof(IAudioCaptureClient), (void**)&pCaptureClient);
    if (FAILED(hr)) return;

    pAudioClient->Start();

    int sample_rate = pwfx->nSamplesPerSec;
    int channels = pwfx->nChannels;

    while (loopback_active_) {
        Sleep(10); 

        UINT32 packetLength = 0;
        hr = pCaptureClient->GetNextPacketSize(&packetLength);
        if (FAILED(hr)) break;

        while (packetLength != 0) {
            BYTE* pData;
            UINT32 numFramesAvailable;
            DWORD flags;
            
            hr = pCaptureClient->GetBuffer(&pData, &numFramesAvailable, &flags, NULL, NULL);
            if (FAILED(hr)) break;

            std::vector<int16_t> pcm16_buffer;
            
            if (pwfx->wFormatTag == WAVE_FORMAT_EXTENSIBLE) {
                WAVEFORMATEXTENSIBLE* pEx = (WAVEFORMATEXTENSIBLE*)pwfx;
                if (pEx->SubFormat == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT) {
                    float* floatData = (float*)pData;
                    for (UINT32 i = 0; i < numFramesAvailable * channels; i++) {
                        float sample = pData ? floatData[i] : 0.0f;
                        if (sample > 1.0f) sample = 1.0f;
                        if (sample < -1.0f) sample = -1.0f;
                        pcm16_buffer.push_back((int16_t)(sample * 32767.0f));
                    }
                }
            }
            
            if (!pcm16_buffer.empty()) {
                // Naive nearest-neighbor resampler from `sample_rate` to 48000
                int target_rate = 48000;
                int target_frames = (int)((long long)numFramesAvailable * target_rate / sample_rate);
                std::vector<int16_t> resampled_buffer(target_frames * channels);
                for (int i = 0; i < target_frames; i++) {
                    int src_idx = (int)((long long)i * sample_rate / target_rate);
                    if (src_idx >= (int)numFramesAvailable) src_idx = numFramesAvailable - 1;
                    for (int c = 0; c < channels; c++) {
                        resampled_buffer[i * channels + c] = pcm16_buffer[src_idx * channels + c];
                    }
                }
                
                std::lock_guard<std::mutex> lock(audio_mutex_);
                loopback_buffer_.insert(loopback_buffer_.end(), resampled_buffer.begin(), resampled_buffer.end());
                
                if (loopback_buffer_.size() > 48000 * channels * 2) {
                    loopback_buffer_.erase(loopback_buffer_.begin(), loopback_buffer_.begin() + loopback_buffer_.size() - 48000 * channels);
                }
            }

            hr = pCaptureClient->ReleaseBuffer(numFramesAvailable);
            if (FAILED(hr)) break;

            hr = pCaptureClient->GetNextPacketSize(&packetLength);
        }
    }

    pAudioClient->Stop();
    pCaptureClient->Release();
    pAudioClient->Release();
    CoTaskMemFree(pwfx);
    CoUninitialize();
}

int32_t OrbitAudioDeviceModule::Init() { return core_adm_->Init(); }
int32_t OrbitAudioDeviceModule::Terminate() { return core_adm_->Terminate(); }
bool OrbitAudioDeviceModule::Initialized() const { return core_adm_->Initialized(); }

int16_t OrbitAudioDeviceModule::PlayoutDevices() { return core_adm_->PlayoutDevices(); }
int16_t OrbitAudioDeviceModule::RecordingDevices() { return core_adm_->RecordingDevices(); }
int32_t OrbitAudioDeviceModule::PlayoutDeviceName(uint16_t index, char name[webrtc::kAdmMaxDeviceNameSize], char guid[webrtc::kAdmMaxGuidSize]) { return core_adm_->PlayoutDeviceName(index, name, guid); }
int32_t OrbitAudioDeviceModule::RecordingDeviceName(uint16_t index, char name[webrtc::kAdmMaxDeviceNameSize], char guid[webrtc::kAdmMaxGuidSize]) { return core_adm_->RecordingDeviceName(index, name, guid); }

int32_t OrbitAudioDeviceModule::SetPlayoutDevice(uint16_t index) { return core_adm_->SetPlayoutDevice(index); }
int32_t OrbitAudioDeviceModule::SetPlayoutDevice(WindowsDeviceType device) { return core_adm_->SetPlayoutDevice(device); }
int32_t OrbitAudioDeviceModule::SetRecordingDevice(uint16_t index) { return core_adm_->SetRecordingDevice(index); }
int32_t OrbitAudioDeviceModule::SetRecordingDevice(WindowsDeviceType device) { return core_adm_->SetRecordingDevice(device); }

int32_t OrbitAudioDeviceModule::PlayoutIsAvailable(bool* available) { return core_adm_->PlayoutIsAvailable(available); }
int32_t OrbitAudioDeviceModule::InitPlayout() { return core_adm_->InitPlayout(); }
bool OrbitAudioDeviceModule::PlayoutIsInitialized() const { return core_adm_->PlayoutIsInitialized(); }
int32_t OrbitAudioDeviceModule::RecordingIsAvailable(bool* available) { return core_adm_->RecordingIsAvailable(available); }
int32_t OrbitAudioDeviceModule::InitRecording() { return core_adm_->InitRecording(); }
bool OrbitAudioDeviceModule::RecordingIsInitialized() const { return core_adm_->RecordingIsInitialized(); }

int32_t OrbitAudioDeviceModule::StartPlayout() { return core_adm_->StartPlayout(); }
int32_t OrbitAudioDeviceModule::StopPlayout() { return core_adm_->StopPlayout(); }
bool OrbitAudioDeviceModule::Playing() const { return core_adm_->Playing(); }
int32_t OrbitAudioDeviceModule::StartRecording() { return core_adm_->StartRecording(); }
int32_t OrbitAudioDeviceModule::StopRecording() { return core_adm_->StopRecording(); }
bool OrbitAudioDeviceModule::Recording() const { return core_adm_->Recording(); }

int32_t OrbitAudioDeviceModule::InitSpeaker() { return core_adm_->InitSpeaker(); }
bool OrbitAudioDeviceModule::SpeakerIsInitialized() const { return core_adm_->SpeakerIsInitialized(); }
int32_t OrbitAudioDeviceModule::InitMicrophone() { return core_adm_->InitMicrophone(); }
bool OrbitAudioDeviceModule::MicrophoneIsInitialized() const { return core_adm_->MicrophoneIsInitialized(); }

int32_t OrbitAudioDeviceModule::SpeakerVolumeIsAvailable(bool* available) { return core_adm_->SpeakerVolumeIsAvailable(available); }
int32_t OrbitAudioDeviceModule::SetSpeakerVolume(uint32_t volume) { return core_adm_->SetSpeakerVolume(volume); }
int32_t OrbitAudioDeviceModule::SpeakerVolume(uint32_t* volume) const { return core_adm_->SpeakerVolume(volume); }
int32_t OrbitAudioDeviceModule::MaxSpeakerVolume(uint32_t* maxVolume) const { return core_adm_->MaxSpeakerVolume(maxVolume); }
int32_t OrbitAudioDeviceModule::MinSpeakerVolume(uint32_t* minVolume) const { return core_adm_->MinSpeakerVolume(minVolume); }

int32_t OrbitAudioDeviceModule::MicrophoneVolumeIsAvailable(bool* available) { return core_adm_->MicrophoneVolumeIsAvailable(available); }
int32_t OrbitAudioDeviceModule::SetMicrophoneVolume(uint32_t volume) { return core_adm_->SetMicrophoneVolume(volume); }
int32_t OrbitAudioDeviceModule::MicrophoneVolume(uint32_t* volume) const { return core_adm_->MicrophoneVolume(volume); }
int32_t OrbitAudioDeviceModule::MaxMicrophoneVolume(uint32_t* maxVolume) const { return core_adm_->MaxMicrophoneVolume(maxVolume); }
int32_t OrbitAudioDeviceModule::MinMicrophoneVolume(uint32_t* minVolume) const { return core_adm_->MinMicrophoneVolume(minVolume); }

int32_t OrbitAudioDeviceModule::SpeakerMuteIsAvailable(bool* available) { return core_adm_->SpeakerMuteIsAvailable(available); }
int32_t OrbitAudioDeviceModule::SetSpeakerMute(bool enable) { return core_adm_->SetSpeakerMute(enable); }
int32_t OrbitAudioDeviceModule::SpeakerMute(bool* enabled) const { return core_adm_->SpeakerMute(enabled); }

int32_t OrbitAudioDeviceModule::MicrophoneMuteIsAvailable(bool* available) { return core_adm_->MicrophoneMuteIsAvailable(available); }
int32_t OrbitAudioDeviceModule::SetMicrophoneMute(bool enable) { return core_adm_->SetMicrophoneMute(enable); }
int32_t OrbitAudioDeviceModule::MicrophoneMute(bool* enabled) const { return core_adm_->MicrophoneMute(enabled); }

int32_t OrbitAudioDeviceModule::StereoPlayoutIsAvailable(bool* available) const { return core_adm_->StereoPlayoutIsAvailable(available); }
int32_t OrbitAudioDeviceModule::SetStereoPlayout(bool enable) { return core_adm_->SetStereoPlayout(enable); }
int32_t OrbitAudioDeviceModule::StereoPlayout(bool* enabled) const { return core_adm_->StereoPlayout(enabled); }
int32_t OrbitAudioDeviceModule::StereoRecordingIsAvailable(bool* available) const { return core_adm_->StereoRecordingIsAvailable(available); }
int32_t OrbitAudioDeviceModule::SetStereoRecording(bool enable) { return core_adm_->SetStereoRecording(enable); }
int32_t OrbitAudioDeviceModule::StereoRecording(bool* enabled) const { return core_adm_->StereoRecording(enabled); }

int32_t OrbitAudioDeviceModule::PlayoutDelay(uint16_t* delayMS) const { return core_adm_->PlayoutDelay(delayMS); }

bool OrbitAudioDeviceModule::BuiltInAECIsAvailable() const { return core_adm_->BuiltInAECIsAvailable(); }
int32_t OrbitAudioDeviceModule::EnableBuiltInAEC(bool enable) { return core_adm_->EnableBuiltInAEC(enable); }

bool OrbitAudioDeviceModule::BuiltInAGCIsAvailable() const { return core_adm_->BuiltInAGCIsAvailable(); }
int32_t OrbitAudioDeviceModule::EnableBuiltInAGC(bool enable) { return core_adm_->EnableBuiltInAGC(enable); }

bool OrbitAudioDeviceModule::BuiltInNSIsAvailable() const { return core_adm_->BuiltInNSIsAvailable(); }
int32_t OrbitAudioDeviceModule::EnableBuiltInNS(bool enable) { return core_adm_->EnableBuiltInNS(enable); }


} // namespace orbit
