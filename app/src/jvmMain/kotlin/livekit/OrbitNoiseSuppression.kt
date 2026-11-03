package livekit

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import webrtc.WebrtcNativeDepsLoader
import kotlin.math.roundToInt

/**
 * JNA Native binding for RNNoise (`orbit_neural_denoiser.dll`).
 * Real recurrent neural network (Dense + 2 GRU layers) with embedded Xiph model weights.
 */
internal interface OrbitRNNoiseLib : Library {
    fun orbit_rnnoise_create(): Pointer?
    fun orbit_rnnoise_process(handle: Pointer, inPcm: FloatArray, outPcm: FloatArray): Float
    fun orbit_rnnoise_destroy(handle: Pointer)

    companion object {
        val INSTANCE: OrbitRNNoiseLib? by lazy {
            try {
                WebrtcNativeDepsLoader.load()
                Native.load("orbit_neural_denoiser", OrbitRNNoiseLib::class.java).also {
                    println("DEBUG [OrbitNoiseSuppression] Loaded native RNNoise library successfully!")
                }
            } catch (t: Throwable) {
                println("ERROR [OrbitNoiseSuppression] Failed to load orbit_neural_denoiser.dll: ${t.message}")
                null
            }
        }
    }
}

/**
 * LADSPA C-ABI Descriptor for DeepFilterNet Mono (`deep_filter_ladspa.dll`).
 */
class LadspaDescriptor(p: Pointer) : Structure(p) {
    @JvmField var uniqueId: Int = 0
    @JvmField var label: String? = null
    @JvmField var properties: Int = 0
    @JvmField var name: String? = null
    @JvmField var maker: String? = null
    @JvmField var copyright: String? = null
    @JvmField var portCount: Int = 0
    @JvmField var portDescriptors: Pointer? = null
    @JvmField var portNames: Pointer? = null
    @JvmField var portRangeHints: Pointer? = null
    @JvmField var implementationData: Pointer? = null
    @JvmField var instantiate: Pointer? = null
    @JvmField var connect_port: Pointer? = null
    @JvmField var activate: Pointer? = null
    @JvmField var run: Pointer? = null
    @JvmField var run_adding: Pointer? = null
    @JvmField var set_run_adding_gain: Pointer? = null
    @JvmField var deactivate: Pointer? = null
    @JvmField var cleanup: Pointer? = null

    override fun getFieldOrder(): List<String> = listOf(
        "uniqueId", "label", "properties", "name", "maker", "copyright",
        "portCount", "portDescriptors", "portNames", "portRangeHints",
        "implementationData", "instantiate", "connect_port", "activate",
        "run", "run_adding", "set_run_adding_gain", "deactivate", "cleanup"
    )
}

/**
 * Real DeepFilterNet 3 Mono Engine (50.6 MB Neural Model).
 * Operates on full-band 48 kHz multi-frame deep complex filtering.
 */
private class RealDeepFilterNetEngine {
    private var handle: Pointer? = null
    private val inMem = Memory(480 * 4L)
    private val outMem = Memory(480 * 4L)
    // Port 2: Attenuation limit in dB (-80 dB for Krisp-grade total noise elimination)
    private val pAtten = Memory(4L).apply { setFloat(0, -80.0f) }
    private val pMinThresh = Memory(4L).apply { setFloat(0, -20.0f) }
    private val pMaxErb = Memory(4L).apply { setFloat(0, 20.0f) }
    private val pMaxDf = Memory(4L).apply { setFloat(0, 20.0f) }
    private val pMinBuf = Memory(4L).apply { setFloat(0, 0.0f) }
    private val pPostFilter = Memory(4L).apply { setFloat(0, 0.02f) }

    private var runFunc: Function? = null
    private var cleanupFunc: Function? = null

    init {
        try {
            WebrtcNativeDepsLoader.load()
            val lib = NativeLibrary.getInstance("deep_filter_ladspa")
            val getDescFunc = lib.getFunction("ladspa_descriptor")
            val descPtr = getDescFunc.invokePointer(arrayOf(0L))
            if (descPtr != null) {
                val desc = LadspaDescriptor(descPtr)
                desc.read()

                val instantiateFunc = Function.getFunction(desc.instantiate)
                val connectFunc = Function.getFunction(desc.connect_port)
                val activateFunc = Function.getFunction(desc.activate)
                runFunc = desc.run?.let { Function.getFunction(it) }
                cleanupFunc = desc.cleanup?.let { Function.getFunction(it) }

                val h = instantiateFunc.invokePointer(arrayOf(desc.pointer, 48000))
                handle = h
                if (h != null) {
                    connectFunc.invokeVoid(arrayOf(h, 0, inMem))
                    connectFunc.invokeVoid(arrayOf(h, 1, outMem))
                    connectFunc.invokeVoid(arrayOf(h, 2, pAtten))
                    connectFunc.invokeVoid(arrayOf(h, 3, pMinThresh))
                    connectFunc.invokeVoid(arrayOf(h, 4, pMaxErb))
                    connectFunc.invokeVoid(arrayOf(h, 5, pMaxDf))
                    connectFunc.invokeVoid(arrayOf(h, 6, pMinBuf))
                    connectFunc.invokeVoid(arrayOf(h, 7, pPostFilter))
                    activateFunc.invokeVoid(arrayOf(h))
                    println("DEBUG [OrbitNoiseSuppression] Successfully initialized real DeepFilterNet 3 neural engine!")
                }
            }
        } catch (t: Throwable) {
            println("ERROR [OrbitNoiseSuppression] Failed to initialize DeepFilterNet 3: ${t.message}")
        }
    }

    val isAvailable: Boolean
        get() = handle != null && runFunc != null

    fun process(buffer: ByteArray, frameSize: Int, channels: Int) {
        val h = handle ?: return
        val r = runFunc ?: return

        try {
            // DeepFilterNet expects float in [-1.0 .. 1.0]
            for (i in 0 until frameSize) {
                val byteIdx = i * channels * 2
                if (byteIdx + 1 >= buffer.size) break
                val s = ((buffer[byteIdx + 1].toInt() shl 8) or (buffer[byteIdx].toInt() and 0xFF)).toShort()
                inMem.setFloat(i * 4L, s / 32768.0f)
            }

            // Execute deep complex filtering forward pass
            r.invokeVoid(arrayOf(h, frameSize))

            // Write enhanced float back to 16-bit PCM buffer
            for (i in 0 until frameSize) {
                val outFloat = outMem.getFloat(i * 4L)
                val outShort = (outFloat * 32767.0f).roundToInt().coerceIn(-32768, 32767)
                for (c in 0 until channels) {
                    val byteIdx = (i * channels + c) * 2
                    if (byteIdx + 1 >= buffer.size) break
                    buffer[byteIdx] = (outShort and 0xFF).toByte()
                    buffer[byteIdx + 1] = ((outShort ushr 8) and 0xFF).toByte()
                }
            }
        } catch (t: Throwable) {
            println("WARN [OrbitNoiseSuppression] DeepFilterNet process caught: ${t.message}")
        }
    }

    fun release() {
        val h = handle ?: return
        cleanupFunc?.invokeVoid(arrayOf(h))
        handle = null
    }
}

/**
 * Orbit Neural Noise Suppression Processor.
 *
 * Runs real neural network inference:
 * - "DeepFilterNet": Full-band 48 kHz Deep Complex Filtering (DCF) model with -80 dB suppression.
 * - "RNNoise": 22 Bark-band Recurrent Neural Network (Dense + 2 GRU layers).
 * - "Off": Direct bypass.
 */
object OrbitNoiseSuppression {
    @Volatile
    var currentEngine: String = "DeepFilterNet"
        private set

    private val processLock = Any()
    private var rnnoiseHandle: Pointer? = null
    private var deepFilterEngine: RealDeepFilterNetEngine? = null

    // Pre-allocated frame buffers for RNNoise (480 samples @ 48 kHz = 10 ms)
    private val frameSize = 480
    private val inFloat = FloatArray(frameSize)
    private val outFloat = FloatArray(frameSize)

    init {
        ensureEngineInitialized(currentEngine)
    }

    fun setEngine(engine: String) {
        val normalized = when (engine.trim().lowercase()) {
            "rnnoise" -> "RNNoise"
            "off", "none", "false" -> "Off"
            else -> "DeepFilterNet"
        }
        if (currentEngine != normalized) {
            synchronized(processLock) {
                currentEngine = normalized
                ensureEngineInitialized(normalized)
            }
            println("DEBUG [OrbitNoiseSuppression] Engine switched to: $normalized")
        }
    }

    private fun ensureEngineInitialized(engine: String) {
        when (engine) {
            "RNNoise" -> {
                val lib = OrbitRNNoiseLib.INSTANCE ?: return
                if (rnnoiseHandle == null) {
                    rnnoiseHandle = lib.orbit_rnnoise_create()
                    println("DEBUG [OrbitNoiseSuppression] Initialized real RNNoise neural network")
                }
            }
            "DeepFilterNet" -> {
                if (deepFilterEngine == null) {
                    deepFilterEngine = RealDeepFilterNetEngine()
                }
            }
        }
    }

    fun process(buffer: ByteArray, length: Int, sampleRate: Int = 48000, channels: Int = 1) {
        if (currentEngine == "Off" || length <= 0) return

        val totalSamples = length / 2
        val samplesPerChannel = totalSamples / channels
        if (samplesPerChannel != frameSize) return

        synchronized(processLock) {
            when (currentEngine) {
                "DeepFilterNet" -> {
                    val df = deepFilterEngine ?: RealDeepFilterNetEngine().also { deepFilterEngine = it }
                    if (df.isAvailable) {
                        df.process(buffer, frameSize, channels)
                    }
                }
                "RNNoise" -> {
                    val lib = OrbitRNNoiseLib.INSTANCE ?: return
                    val handle = rnnoiseHandle ?: lib.orbit_rnnoise_create().also { rnnoiseHandle = it }
                    if (handle == null) return

                    // RNNoise operates on 16-bit float scale [-32768.0 .. 32767.0]
                    for (i in 0 until frameSize) {
                        val byteIdx = i * channels * 2
                        val s = ((buffer[byteIdx + 1].toInt() shl 8) or (buffer[byteIdx].toInt() and 0xFF)).toShort()
                        inFloat[i] = s.toFloat()
                    }

                    // Forward pass through real neural network (Dense + GRU + Dense)
                    lib.orbit_rnnoise_process(handle, inFloat, outFloat)

                    // Write enhanced audio back to PCM buffer
                    for (i in 0 until frameSize) {
                        val outShort = outFloat[i].roundToInt().coerceIn(-32768, 32767)
                        for (c in 0 until channels) {
                            val byteIdx = (i * channels + c) * 2
                            buffer[byteIdx] = (outShort and 0xFF).toByte()
                            buffer[byteIdx + 1] = ((outShort ushr 8) and 0xFF).toByte()
                        }
                    }
                }
            }
        }
    }

    fun release() {
        synchronized(processLock) {
            val lib = OrbitRNNoiseLib.INSTANCE
            if (lib != null && rnnoiseHandle != null) {
                lib.orbit_rnnoise_destroy(rnnoiseHandle!!)
            }
            rnnoiseHandle = null
            deepFilterEngine?.release()
            deepFilterEngine = null
        }
    }
}
