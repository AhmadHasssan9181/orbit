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

#include "media/video/VideoTrackDeviceSource.h"
#include "Exception.h"

#include "api/video/i420_buffer.h"
#include "modules/video_capture/video_capture_factory.h"
#include "libyuv.h"
#include "rtc_base/logging.h"
#include <opencv2/dnn.hpp>
#include <opencv2/imgproc.hpp>

namespace jni
{
	VideoTrackDeviceSource::VideoTrackDeviceSource() :
		AdaptedVideoTrackSource(),
		VideoTrackDeviceSourceBase(),
		captureModule(nullptr)
	{
	}

	VideoTrackDeviceSource::~VideoTrackDeviceSource()
	{
		destroy();
        if (selfie_net_) {
            delete selfie_net_;
            selfie_net_ = nullptr;
        }
	}

    void VideoTrackDeviceSource::setBackgroundBlur(bool enable) {
        enable_background_blur_.store(enable);
        if (enable && !net_loaded_) {
            try {
                if (!selfie_net_) {
                    selfie_net_ = new cv::dnn::Net();
                }
                *selfie_net_ = cv::dnn::readNetFromONNX("app/src/jvmMain/resources/selfie_segmentation.onnx");
                selfie_net_->setPreferableBackend(cv::dnn::DNN_BACKEND_OPENCV);
                selfie_net_->setPreferableTarget(cv::dnn::DNN_TARGET_CPU);
                net_loaded_ = true;
            } catch (const cv::Exception& e) {
                RTC_LOG(LS_ERROR) << "Failed to load Selfie Segmentation ONNX: " << e.what();
                enable_background_blur_.store(false);
            }
        }
    }

	void VideoTrackDeviceSource::start()
	{
		std::unique_ptr<webrtc::VideoCaptureModule::DeviceInfo> info(webrtc::VideoCaptureFactory::CreateDeviceInfo());

		if (!info) {
			throw new Exception("Create video DeviceInfo failed");
		}

		uint32_t num = info->NumberOfDevices();

		if (num < 1) {
			throw new Exception("No video capture devices available");
		}

		if (device) {
			std::string devUid;
			const uint32_t size = webrtc::kVideoCaptureDeviceNameLength;

			for (uint32_t i = 0; i < num; ++i) {
				char name[size] = { 0 };
				char guid[size] = { 0 };

				int32_t ret = info->GetDeviceName(i, name, size, guid, size);

				if (ret != 0) {
					RTC_LOG(LS_WARNING) << "Get video capture device name failed";
					continue;
				}

				if (device->getName().compare(name) == 0) {
					devUid = guid;
					break;
				}
			}

			if (devUid.empty()) {
				throw new Exception("Device %s not found", device->getName().c_str());
			}

			captureModule = webrtc::VideoCaptureFactory::Create(devUid.c_str());

			if (!captureModule) {
				throw new Exception("Create VideoCaptureModule for UID %s failed", devUid.c_str());
			}

			if (!startCapture()) {
				destroy();

				throw new Exception("Start video capture for UID %s failed", devUid.c_str());
			}
		}

		if (!captureModule) {
			// No user-defined capture device. Select first available device.
			const uint32_t size = webrtc::kVideoCaptureDeviceNameLength;

			for (uint32_t i = 0; i < num; ++i) {
				char name[size] = { 0 };
				char guid[size] = { 0 };

				int32_t ret = info->GetDeviceName(i, name, size, guid, size);

				if (ret != 0) {
					RTC_LOG(LS_WARNING) << "Get video capture device name failed";
					continue;
				}

				captureModule = webrtc::VideoCaptureFactory::Create(guid);

				if (!captureModule) {
					continue;
				}

				if (startCapture()) {
					break;
				}
				else {
					// Clean up resources. Try next device.
					destroy();
				}
			}
		}

		if (!captureModule || !captureModule->CaptureStarted()) {
			throw new Exception("Start video capture failed");
		}
	}

	void VideoTrackDeviceSource::stop()
	{
		destroy();
	}

	void VideoTrackDeviceSource::destroy()
	{
		if (!captureModule) {
			return;
		}
		if (captureModule->CaptureStarted()) {
			captureModule->StopCapture();
		}
		
		captureModule->DeRegisterCaptureDataCallback();
		captureModule = nullptr;
	}

	void VideoTrackDeviceSource::AddOrUpdateSink(webrtc::VideoSinkInterface<webrtc::VideoFrame> * sink, const webrtc::VideoSinkWants & wants)
	{
		broadcaster.AddOrUpdateSink(sink, wants);

		updateVideoAdapter();
	}

	void VideoTrackDeviceSource::RemoveSink(webrtc::VideoSinkInterface<webrtc::VideoFrame> * sink)
	{
		broadcaster.RemoveSink(sink);

		updateVideoAdapter();
	}

	bool VideoTrackDeviceSource::startCapture()
	{
		captureModule->RegisterCaptureDataCallback(this);

		capability.videoType = webrtc::VideoType::kI420;

		return captureModule->StartCapture(capability) == 0;
	}

	void VideoTrackDeviceSource::updateVideoAdapter()
	{
		webrtc::VideoSinkWants wants = broadcaster.wants();

		videoAdapter.OnOutputFormatRequest(std::make_pair(capability.width, capability.height), wants.max_pixel_count, wants.max_framerate_fps);
	}

	void VideoTrackDeviceSource::OnFrame(const webrtc::VideoFrame & frame)
	{
		int croppedWidth = 0;
		int croppedHeight = 0;
		int outWidth = 0;
		int outHeight = 0;

		if (!videoAdapter.AdaptFrameResolution(frame.width(), frame.height(), frame.timestamp_us() * 1000,
			&croppedWidth, &croppedHeight, &outWidth, &outHeight)) {
			// Drop frame in order to respect frame rate constraint.
			return;
		}

        webrtc::scoped_refptr<webrtc::I420BufferInterface> buffer = frame.video_frame_buffer()->ToI420();

        // Check if Background Blur is enabled
        if (enable_background_blur_.load() && net_loaded_ && selfie_net_) {
            cv::Mat imgRgb(buffer->height(), buffer->width(), CV_8UC3);
            libyuv::I420ToRGB24(
                buffer->DataY(), buffer->StrideY(),
                buffer->DataU(), buffer->StrideU(),
                buffer->DataV(), buffer->StrideV(),
                imgRgb.data, imgRgb.step[0],
                buffer->width(), buffer->height()
            );

            cv::Mat resizedRgb;
            cv::resize(imgRgb, resizedRgb, cv::Size(256, 256));

            cv::Mat blob = cv::dnn::blobFromImage(resizedRgb, 1.0 / 255.0, cv::Size(256, 256), cv::Scalar(), true, false);
            selfie_net_->setInput(blob);

            cv::Mat out;
            try {
                out = selfie_net_->forward();
                cv::Mat mask(256, 256, CV_32F, out.ptr<float>());
                
                cv::Mat maskOriginal;
                cv::resize(mask, maskOriginal, cv::Size(imgRgb.cols, imgRgb.rows));

                cv::Mat blurredRgb;
                cv::GaussianBlur(imgRgb, blurredRgb, cv::Size(51, 51), 0);

                cv::Mat result(imgRgb.size(), CV_8UC3);
                for (int y = 0; y < imgRgb.rows; ++y) {
                    for (int x = 0; x < imgRgb.cols; ++x) {
                        float m = maskOriginal.at<float>(y, x);
                        cv::Vec3b bg = blurredRgb.at<cv::Vec3b>(y, x);
                        cv::Vec3b fg = imgRgb.at<cv::Vec3b>(y, x);
                        
                        cv::Vec3b& out_px = result.at<cv::Vec3b>(y, x);
                        out_px[0] = static_cast<uchar>(fg[0] * m + bg[0] * (1.0f - m));
                        out_px[1] = static_cast<uchar>(fg[1] * m + bg[1] * (1.0f - m));
                        out_px[2] = static_cast<uchar>(fg[2] * m + bg[2] * (1.0f - m));
                    }
                }

                webrtc::scoped_refptr<webrtc::I420Buffer> processed_buffer = webrtc::I420Buffer::Create(imgRgb.cols, imgRgb.rows);
                libyuv::RGB24ToI420(
                    result.data, result.step[0],
                    processed_buffer->MutableDataY(), processed_buffer->StrideY(),
                    processed_buffer->MutableDataU(), processed_buffer->StrideU(),
                    processed_buffer->MutableDataV(), processed_buffer->StrideV(),
                    imgRgb.cols, imgRgb.rows
                );
                
                buffer = processed_buffer;
            } catch (...) {
                RTC_LOG(LS_ERROR) << "Exception during Selfie Segmentation inference.";
            }
        }

		if (outHeight != buffer->height() || outWidth != buffer->width()) {
			webrtc::scoped_refptr<webrtc::I420Buffer> scaled_buffer = webrtc::I420Buffer::Create(outWidth, outHeight);
			scaled_buffer->ScaleFrom(*buffer);
			
			broadcaster.OnFrame(webrtc::VideoFrame::Builder()
				.set_video_frame_buffer(scaled_buffer)
				.set_rotation(frame.rotation())
				.set_timestamp_us(frame.timestamp_us())
				.set_id(frame.id())
				.build());
		}
		else {
			broadcaster.OnFrame(webrtc::VideoFrame::Builder()
				.set_video_frame_buffer(buffer)
				.set_rotation(frame.rotation())
				.set_timestamp_us(frame.timestamp_us())
				.set_id(frame.id())
				.build());
		}
	}
}