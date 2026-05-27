#include "OrbitVideoProcessor.h"
#include <opencv2/opencv.hpp>
#include <opencv2/dnn.hpp>
#include "api/video/i420_buffer.h"
#include "rtc_base/ref_counted_object.h"
#include "libyuv/convert_from.h"
#include "libyuv/convert.h"

namespace orbit {

OrbitVideoProcessor::OrbitVideoProcessor() : net_(std::make_unique<cv::dnn::Net>()) {}
OrbitVideoProcessor::~OrbitVideoProcessor() = default;

bool OrbitVideoProcessor::LoadModel(const std::string& model_path) {
    try {
        cv::dnn::Net new_net = cv::dnn::readNetFromONNX(model_path);
        if (new_net.empty()) return false;
        
        new_net.setPreferableBackend(cv::dnn::DNN_BACKEND_OPENCV);
        new_net.setPreferableTarget(cv::dnn::DNN_TARGET_CPU);
        
        net_ = std::make_unique<cv::dnn::Net>(new_net);
        return true;
    } catch (const std::exception& e) {
        return false;
    }
}

webrtc::VideoFrame OrbitVideoProcessor::ProcessFrame(const webrtc::VideoFrame& frame) {
    if (!enabled_.load() || !net_ || net_->empty()) {
        return frame;
    }

    webrtc::scoped_refptr<webrtc::VideoFrameBuffer> buffer = frame.video_frame_buffer();
    webrtc::scoped_refptr<webrtc::I420BufferInterface> i420_buffer = buffer->ToI420();
    
    int width = i420_buffer->width();
    int height = i420_buffer->height();

    // 1. I420 to RGB
    cv::Mat rgb_mat(height, width, CV_8UC3);
    libyuv::I420ToRGB24(
        i420_buffer->DataY(), i420_buffer->StrideY(),
        i420_buffer->DataU(), i420_buffer->StrideU(),
        i420_buffer->DataV(), i420_buffer->StrideV(),
        rgb_mat.data, width * 3,
        width, height
    );
    
    // 2. Selfie Segmentation Inference
    cv::Mat blob = cv::dnn::blobFromImage(rgb_mat, 1.0/255.0, cv::Size(256, 256), cv::Scalar(0,0,0), false, false);
    net_->setInput(blob);
    cv::Mat score = net_->forward(); // Shape [1, 1, 256, 256]

    // 3. Create mask from score
    cv::Mat mask_small(256, 256, CV_32FC1, score.data);
    cv::Mat mask;
    cv::resize(mask_small, mask, cv::Size(width, height));

    // 4. Blur Background
    cv::Mat blurred;
    int ksize = (blur_amount_.load() / 2) * 2 + 1; // Must be odd
    cv::GaussianBlur(rgb_mat, blurred, cv::Size(ksize, ksize), 0);

    // 5. Composite
    cv::Mat output(height, width, CV_8UC3);
    for (int y = 0; y < height; ++y) {
        float* mask_row = mask.ptr<float>(y);
        cv::Vec3b* rgb_row = rgb_mat.ptr<cv::Vec3b>(y);
        cv::Vec3b* blur_row = blurred.ptr<cv::Vec3b>(y);
        cv::Vec3b* out_row = output.ptr<cv::Vec3b>(y);
        
        for (int x = 0; x < width; ++x) {
            float m = mask_row[x];
            if (m > 0.5f) {
                out_row[x] = rgb_row[x];
            } else {
                out_row[x] = blur_row[x];
            }
        }
    }

    // 6. RGB to I420
    webrtc::scoped_refptr<webrtc::I420Buffer> output_buffer = webrtc::I420Buffer::Create(width, height);
    libyuv::RGB24ToI420(
        output.data, width * 3,
        output_buffer->MutableDataY(), output_buffer->StrideY(),
        output_buffer->MutableDataU(), output_buffer->StrideU(),
        output_buffer->MutableDataV(), output_buffer->StrideV(),
        width, height
    );

    return webrtc::VideoFrame::Builder()
        .set_video_frame_buffer(output_buffer)
        .set_rotation(frame.rotation())
        .set_timestamp_us(frame.timestamp_us())
        .set_id(frame.id())
        .build();
}

OrbitVideoProcessorSource::OrbitVideoProcessorSource(webrtc::scoped_refptr<webrtc::VideoTrackSourceInterface> source)
    : source_(std::move(source)) {
    interceptor_ = std::make_unique<FrameInterceptor>(&processor_, this);
    source_->AddOrUpdateSink(interceptor_.get(), webrtc::VideoSinkWants());
}

OrbitVideoProcessorSource::~OrbitVideoProcessorSource() {
    source_->RemoveSink(interceptor_.get());
}

void OrbitVideoProcessorSource::FrameInterceptor::OnFrame(const webrtc::VideoFrame& frame) {
    if (processor_) {
        parent_->OnFrame(processor_->ProcessFrame(frame));
    } else {
        parent_->OnFrame(frame);
    }
}

} // namespace orbit
