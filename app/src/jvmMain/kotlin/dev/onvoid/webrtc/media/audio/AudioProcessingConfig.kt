package dev.onvoid.webrtc.media.audio

class AudioProcessingConfig {
    @JvmField val pipeline = Pipeline()
    @JvmField val echoCanceller = EchoCanceller()
    @JvmField val gainController = GainController()
    @JvmField val gainControllerDigital = GainControllerDigital()
    @JvmField val highPassFilter = HighPassFilter()
    @JvmField val noiseSuppression = NoiseSuppression()
    @JvmField val captureLevelAdjustment = CaptureLevelAdjustment()

    class Pipeline {
        enum class DownmixMethod {
            AverageChannels, UseFirstChannel
        }
        @JvmField var maximumInternalProcessingRate: Int = 48000
        @JvmField var multiChannelRender: Boolean = false
        @JvmField var multiChannelCapture: Boolean = false
        @JvmField var captureDownmixMethod: DownmixMethod = DownmixMethod.AverageChannels
    }

    class EchoCanceller {
        @JvmField var enabled: Boolean = false
        @JvmField var enforceHighPassFiltering: Boolean = true
    }

    class GainControllerDigital {
        class InputVolumeController {
            @JvmField var enabled: Boolean = false
        }
        class FixedDigital {
            @JvmField var gainDb: Float = 0.0f
        }
        class AdaptiveDigital {
            @JvmField var enabled: Boolean = false
            @JvmField var headroomDb: Float = 5.0f
            @JvmField var maxGainDb: Float = 50.0f
            @JvmField var initialGainDb: Float = 15.0f
            @JvmField var maxGainChangeDbPerSecond: Float = 6.0f
            @JvmField var maxOutputNoiseLevelDbfs: Float = -50.0f
        }
        @JvmField val fixedDigital = FixedDigital()
        @JvmField val adaptiveDigital = AdaptiveDigital()
        @JvmField val inputVolumeController = InputVolumeController()
        @JvmField var enabled: Boolean = false
    }

    class GainController {
        enum class Mode {
            AdaptiveAnalog, AdaptiveDigital, FixedDigital
        }
        @JvmField var enabled: Boolean = false
        @JvmField var targetLevelDbfs: Int = 3
        @JvmField var compressionGainDb: Int = 9
        @JvmField var enableLimiter: Boolean = true
        @JvmField var mode: Mode = Mode.AdaptiveAnalog
        @JvmField val analogGainController = AnalogGainController()

        class AnalogGainController {
            @JvmField var enabled: Boolean = true
            @JvmField var startupMinVolume: Int = 0
            @JvmField var clippedLevelMin: Int = 70
            @JvmField var enableDigitalAdaptive: Boolean = true
            @JvmField var clippedLevelStep: Int = 15
            @JvmField var clippedRatioThreshold: Float = 0.1f
            @JvmField var clippedWaitFrames: Int = 300
            @JvmField val clippingPredictor = ClippingPredictor()

            class ClippingPredictor {
                enum class Mode {
                    ClippingEventPrediction, AdaptiveStepClippingPeakPrediction, FixedStepClippingPeakPrediction
                }
                @JvmField var enabled: Boolean = false
                @JvmField var mode: Mode = Mode.ClippingEventPrediction
                @JvmField var windowLength: Int = 5
                @JvmField var referenceWindowLength: Int = 5
                @JvmField var referenceWindowDelay: Int = 5
                @JvmField var clippingThreshold: Float = -1.0f
                @JvmField var crestFactorMargin: Float = 3.0f
                @JvmField var usePredictedStep: Boolean = true
            }
        }
    }

    class CaptureLevelAdjustment {
        @JvmField var enabled: Boolean = false
        @JvmField var preGainFactor: Float = 1.0f
        @JvmField var postGainFactor: Float = 1.0f
        @JvmField val analogMicGainEmulation = AnalogMicGainEmulation()

        class AnalogMicGainEmulation {
            @JvmField var enabled: Boolean = false
            @JvmField var initialLevel: Int = 255
        }
    }

    class HighPassFilter {
        @JvmField var enabled: Boolean = false
        @JvmField var applyInFullBand: Boolean = true
    }

    class LevelEstimation {
        @JvmField var enabled: Boolean = false
    }

    class NoiseSuppression {
        enum class Level {
            LOW, MODERATE, HIGH, VERY_HIGH
        }
        @JvmField var enabled: Boolean = false
        @JvmField var level: Level? = null
    }
}
