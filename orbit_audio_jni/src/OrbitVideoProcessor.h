#pragma once

#include <memory>
#include <string>
#include <vector>
#include <atomic>

#include "api/scoped_refptr.h"
#include "api/video/video_frame.h"
#include "api/video/video_sink_interface.h"
#include "api/media_stream_interface.h"
#include "media/base/adapted_video_track_source.h"
#include "absl/types/optional.h"

// Include OpenCV DNN header directly to avoid definition issues
#include <opencv2/dnn.hpp>

namespace orbit {

class OrbitVideoProcessor {
public:
    OrbitVideoProcessor();
    ~OrbitVideoProcessor();

    bool LoadModel(const std::string& model_path);
    
    // Process a frame: returns a new frame with background blurred/replaced
    webrtc::VideoFrame ProcessFrame(const webrtc::VideoFrame& frame);

    void SetBlurAmount(int amount) { blur_amount_.store(amount); }
    void SetEnabled(bool enabled) { enabled_.store(enabled); }

private:
    std::unique_ptr<cv::dnn::Net> net_;
    std::atomic<int> blur_amount_{20};
    std::atomic<bool> enabled_{true};
};

class OrbitVideoProcessorSource : public webrtc::AdaptedVideoTrackSource {
public:
    OrbitVideoProcessorSource(webrtc::scoped_refptr<webrtc::VideoTrackSourceInterface> source);
    ~OrbitVideoProcessorSource() override;

    // From VideoTrackSourceInterface
    SourceState state() const override { return source_->state(); }
    bool remote() const override { return source_->remote(); }
    bool is_screencast() const override { return source_->is_screencast(); }
    absl::optional<bool> needs_denoising() const override { return source_->needs_denoising(); }
    bool GetStats(Stats* stats) override { return source_->GetStats(stats); }

    // Custom control
    OrbitVideoProcessor* GetProcessor() { return &processor_; }

private:
    class FrameInterceptor : public webrtc::VideoSinkInterface<webrtc::VideoFrame> {
    public:
        FrameInterceptor(OrbitVideoProcessor* processor, OrbitVideoProcessorSource* parent)
            : processor_(processor), parent_(parent) {}
        
        void OnFrame(const webrtc::VideoFrame& frame) override;

    private:
        OrbitVideoProcessor* processor_;
        OrbitVideoProcessorSource* parent_;
    };

    webrtc::scoped_refptr<webrtc::VideoTrackSourceInterface> source_;
    OrbitVideoProcessor processor_;
    std::unique_ptr<FrameInterceptor> interceptor_;
};

} // namespace orbit
