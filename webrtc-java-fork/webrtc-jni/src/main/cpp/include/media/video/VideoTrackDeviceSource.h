/*
 * Copyright 2019 Alex Andres
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef JNI_WEBRTC_MEDIA_VIDEO_TRACK_DEVICE_SOURCE_H_
#define JNI_WEBRTC_MEDIA_VIDEO_TRACK_DEVICE_SOURCE_H_

#include "api/create_peerconnection_factory.h"
#include "api/video/video_frame.h"
#include "api/video/video_sink_interface.h"
#include "media/base/adapted_video_track_source.h"
#include "media/base/video_adapter.h"
#include "media/base/video_broadcaster.h"
#include "modules/video_capture/video_capture.h"
#include "modules/video_capture/video_capture_defines.h"

#include "media/video/VideoTrackDeviceSourceBase.h"
#include "media/video/VideoDevice.h"
#include <atomic>

namespace cv {
namespace dnn {
    class Net;
}
}

namespace jni
{
	class VideoTrackDeviceSource : public webrtc::AdaptedVideoTrackSource, public webrtc::VideoSinkInterface<webrtc::VideoFrame>, public VideoTrackDeviceSourceBase
	{
		public:
			VideoTrackDeviceSource();
			~VideoTrackDeviceSource();

            // VideoTrackDeviceSourceBase implementation.
            void start() override;
            void stop() override;

            void setBackgroundBlur(bool enable);

			// VideoSourceInterface implementation.
			void AddOrUpdateSink(webrtc::VideoSinkInterface<webrtc::VideoFrame> * sink, const webrtc::VideoSinkWants & wants) override;
			void RemoveSink(webrtc::VideoSinkInterface<webrtc::VideoFrame> * sink) override;

			// VideoSinkInterface implementation.
			void OnFrame(const webrtc::VideoFrame & frame) override;

			// VideoTrackSourceInterface implementation.
			webrtc::MediaSourceInterface::SourceState state() const override { return webrtc::MediaSourceInterface::kLive; }
			bool remote() const override { return false; }
			bool is_screencast() const override { return false; }
			std::optional<bool> needs_denoising() const override { return std::nullopt; }

		private:
			bool startCapture();
			void updateVideoAdapter();
			void destroy();

		private:
			webrtc::scoped_refptr<webrtc::VideoCaptureModule> captureModule;
			webrtc::VideoBroadcaster broadcaster;
			webrtc::VideoAdapter videoAdapter;

            std::atomic<bool> enable_background_blur_{false};
            cv::dnn::Net* selfie_net_ = nullptr;
            bool net_loaded_ = false;
	};
}

#endif