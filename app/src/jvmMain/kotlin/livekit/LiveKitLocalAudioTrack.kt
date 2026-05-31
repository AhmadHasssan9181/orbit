package livekit

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.*
import kotlinx.coroutines.*

object LiveKitLocalAudioTrack {
    private var sourceHandle: Long = 0
    private var isStarted = false
    var isMuted = false
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startCapture(sampleRate: Int = 48000, numChannels: Int = 1) {
        if (isStarted) return
        
        // 1. Create native source in LiveKit FFI
        val sourceReq = livekit.proto.AudioFrame.NewAudioSourceRequest.newBuilder()
            .setType(livekit.proto.AudioFrame.AudioSourceType.AUDIO_SOURCE_NATIVE)
            .setSampleRate(sampleRate)
            .setNumChannels(numChannels)
            .build()
            
        val request = livekit.proto.Ffi.FfiRequest.newBuilder()
            .setNewAudioSource(sourceReq)
            .build()
            
        val response = LiveKitClient.request(request)
        if (response?.hasNewAudioSource() == true) {
            sourceHandle = response.newAudioSource.source.handle.id
            println("DEBUG [LiveKitLocalAudioTrack] Created native audio source with handle: $sourceHandle")
            isStarted = true
            
            startRecordingLoop(sampleRate, numChannels)
        } else {
            println("ERROR [LiveKitLocalAudioTrack] Failed to create native audio source")
        }
    }

    private fun startRecordingLoop(sampleRate: Int, numChannels: Int) {
        captureJob = scope.launch {
            val format = AudioFormat(sampleRate.toFloat(), 16, numChannels, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            
            if (!AudioSystem.isLineSupported(info)) {
                println("ERROR [LiveKitLocalAudioTrack] Audio line not supported")
                return@launch
            }
            
            val line = AudioSystem.getLine(info) as TargetDataLine
            line.open(format)
            line.start()
            
            // LiveKit FFI expects frames in 10ms chunks usually
            val frameSize = (sampleRate * 0.01).toInt() * numChannels * 2
            val buffer = ByteArray(frameSize)
            
            println("DEBUG [LiveKitLocalAudioTrack] Recording loop started")
            
            try {
                val silentBuffer = ByteArray(frameSize)
                while (isActive && isStarted) {
                    val available = line.available()
                    if (available >= frameSize) {
                        val read = line.read(buffer, 0, frameSize)
                        if (read > 0 && !isMuted) {
                            // Add tiny dither noise to prevent WebRTC DTX from dropping packets
                            for (i in 0 until read step 2) {
                                var sample = (buffer[i].toInt() and 0xFF) or (buffer[i+1].toInt() shl 8)
                                sample += kotlin.random.Random.nextInt(-50, 50)
                                if (sample > 32767) sample = 32767
                                if (sample < -32768) sample = -32768
                                buffer[i] = sample.toByte()
                                buffer[i+1] = (sample shr 8).toByte()
                            }
                            pushAudioFrame(buffer, read, sampleRate, numChannels)
                        }
                    } else {
                        if (!isMuted) {
                            // Inject noise to keep connection alive when no audio is coming from line
                            for (i in 0 until frameSize step 2) {
                                val noise = kotlin.random.Random.nextInt(-50, 50)
                                silentBuffer[i] = noise.toByte()
                                silentBuffer[i+1] = (noise shr 8).toByte()
                            }
                            pushAudioFrame(silentBuffer, frameSize, sampleRate, numChannels)
                        }
                        delay(10)
                    }
                }
            } finally {
                line.stop()
                line.close()
            }
        }
    }

    private fun pushAudioFrame(data: ByteArray, length: Int, sampleRate: Int, numChannels: Int) {
        val directBuffer = ByteBuffer.allocateDirect(length)
        directBuffer.order(ByteOrder.nativeOrder())
        directBuffer.put(data, 0, length)
        
        val addr = getBufferAddress(directBuffer)
        if (addr == 0L) return

        val audioBufferInfo = livekit.proto.AudioFrame.AudioFrameBufferInfo.newBuilder()
            .setDataPtr(addr)
            .setSampleRate(sampleRate)
            .setNumChannels(numChannels)
            .setSamplesPerChannel(length / (2 * numChannels))
            .build()

        val captureReq = livekit.proto.AudioFrame.CaptureAudioFrameRequest.newBuilder()
            .setSourceHandle(sourceHandle)
            .setBuffer(audioBufferInfo)
            .build()
            
        val request = livekit.proto.Ffi.FfiRequest.newBuilder()
            .setCaptureAudioFrame(captureReq)
            .build()
            
        LiveKitClient.request(request)
    }

    fun stopCapture() {
        isStarted = false
        captureJob?.cancel()
        captureJob = null
    }
    
    fun getSourceHandle(): Long = sourceHandle

    private fun getBufferAddress(buffer: ByteBuffer): Long {
        return LiveKitLocalTrack.getBufferAddress(buffer)
    }
}
