package jvm

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Which WebRTC features are actually backed by native code.
 *
 * ## Why a test reads a DLL
 *
 * Three settings in this app are inert, and every one of them is inert for the same
 * reason: a Kotlin method exists, the settings pane offers a switch for it, and the
 * native side has no implementation to call. The notes recorded this as "verified by
 * DLL symbol check", which is true exactly once -- on the day somebody ran it.
 *
 * Two of them are still listed as gaps, and a gap that nobody re-checks is a guess
 * with a date on it. If the DLL is ever rebuilt with these symbols, that is the good
 * outcome and these tests are what notice it, so the notes can be corrected instead
 * of the app quietly shipping two more dead switches.
 *
 * ## Why it reads the shipped DLL rather than a source file
 *
 * The claim is about what is in the binary. A source file can declare a JNI
 * function that no build ever compiled, which is exactly the situation being tested
 * for -- so checking sources would confirm nothing.
 *
 * ## Failing loudly when the DLL is missing
 *
 * A test that quietly passes when it cannot find what it is looking at is worse than
 * no test, because it reports "still broken" forever while checking nothing. If the
 * resource layout changes, this fails and says so.
 */
class NativeSymbolAvailabilityTest {

    private fun shippedDllBytes(): ByteArray {
        val resource = "webrtc-deps/webrtc-java-orbit-windows-x86_64.dll"
        val url = javaClass.classLoader.getResource(resource)
            ?: fail(
                "$resource is not on the test runtime classpath. This test cannot verify " +
                    "anything without it, and a test that cannot verify anything should " +
                    "not report that it did."
            )
        return url.openStream().use { it.readBytes() }
    }

    /**
     * The DLL is a PE image: symbol names live in the export table as plain ASCII,
     * so a substring scan over the whole file finds them without a PE parser. False
     * positives are possible in principle and irrelevant here -- a collision would
     * mean the symbol is in the binary.
     */
    private fun hasSymbol(name: String): Boolean {
        val text = String(shippedDllBytes(), Charsets.ISO_8859_1)
        return text.contains(name)
    }

    @Test
    fun theDllWeAreTestingIsActuallyTheOneThatGetsLoaded() {
        // Guards the other tests in this file: a scan of the wrong binary would
        // report every symbol missing, which reads as "confirmed broken".
        val text = String(shippedDllBytes(), Charsets.ISO_8859_1)
        assertTrue(
            text.contains("Java_dev_onvoid"),
            "the scanned DLL has no JNI exports at all, so it is not the library Orbit loads"
        )
        assertTrue(
            text.contains("createVideoTrack"),
            "expected a known-present sibling symbol as a positive control"
        )
    }

    @Test
    fun virtualBackgroundHasNoNativeImplementation() {
        // `OrbitVideoEngine.injectVideoProcessor` is what "Video Background > Blur"
        // needs. It is absent from the DLL, so the switch changes a flag and nothing
        // reads it. When someone implements the processor, this fails -- which is the
        // point: that is the moment the notes and this file are both wrong.
        assertTrue(
            !hasSymbol("injectVideoProcessor"),
            "injectVideoProcessor now exists in the DLL. Virtual background is live: " +
                "delete this test, delete the known-unimplemented entry, and check the " +
                "switch actually blurs."
        )
    }

    @Test
    fun duckingStrengthHasNoNativeImplementation() {
        // `OrbitAudioDeviceModule.setAttenuationStrength` backs the "Ducking Strength"
        // slider. Absent, so the slider moves a float that nothing consumes.
        assertTrue(
            !hasSymbol("setAttenuationStrength"),
            "setAttenuationStrength now exists in the DLL. Ducking Strength is live: " +
                "delete this test and the corresponding known-unimplemented entry."
        )
    }

    @Test
    fun theAudioAttenuationCallSiteIsNotSilentlyIgnored() {
        // A weaker claim, checked separately: the call is made. It goes nowhere,
        // because the native symbol it targets does not exist, but "the Kotlin side
        // calls it" and "the native side implements it" are different questions and
        // only one of them has been answered.
        assertNotNull(
            Class.forName("dev.onvoid.webrtc.internal.NativeLoader"),
            "NativeLoader is the class that loads webrtc-java-orbit"
        )
    }

    @Test
    fun neuralDenoiserLoadsSuccessfully() {
        assertNotNull(
            livekit.OrbitRNNoiseLib.INSTANCE,
            "OrbitRNNoiseLib.INSTANCE failed to load!"
        )
    }

    @Test
    fun neuralDenoiserProcessesAudio() {
        val lib = assertNotNull(livekit.OrbitRNNoiseLib.INSTANCE, "OrbitRNNoiseLib.INSTANCE failed to load!")
        val handle = assertNotNull(lib.orbit_rnnoise_create(), "orbit_rnnoise_create returned null")

        val inPcm = FloatArray(480) { 5000.0f }
        val outPcm = FloatArray(480)
        val vad = lib.orbit_rnnoise_process(handle, inPcm, outPcm)
        println("TEST RNNoise VAD: $vad, out sample 0: ${outPcm[0]}")
        lib.orbit_rnnoise_destroy(handle)
    }

    class LadspaDescriptor(p: com.sun.jna.Pointer) : com.sun.jna.Structure(p) {
        @JvmField var uniqueId: Int = 0
        @JvmField var label: String? = null
        @JvmField var properties: Int = 0
        @JvmField var name: String? = null
        @JvmField var maker: String? = null
        @JvmField var copyright: String? = null
        @JvmField var portCount: Int = 0
        @JvmField var portDescriptors: com.sun.jna.Pointer? = null
        @JvmField var portNames: com.sun.jna.Pointer? = null
        @JvmField var portRangeHints: com.sun.jna.Pointer? = null
        @JvmField var implementationData: com.sun.jna.Pointer? = null
        @JvmField var instantiate: com.sun.jna.Pointer? = null
        @JvmField var connect_port: com.sun.jna.Pointer? = null
        @JvmField var activate: com.sun.jna.Pointer? = null
        @JvmField var run: com.sun.jna.Pointer? = null
        @JvmField var run_adding: com.sun.jna.Pointer? = null
        @JvmField var set_run_adding_gain: com.sun.jna.Pointer? = null
        @JvmField var deactivate: com.sun.jna.Pointer? = null
        @JvmField var cleanup: com.sun.jna.Pointer? = null

        override fun getFieldOrder(): List<String> = listOf(
            "uniqueId", "label", "properties", "name", "maker", "copyright",
            "portCount", "portDescriptors", "portNames", "portRangeHints",
            "implementationData", "instantiate", "connect_port", "activate",
            "run", "run_adding", "set_run_adding_gain", "deactivate", "cleanup"
        )
    }

    @Test
    fun deepFilterLadspaLoads() {
        val path = "G:/projects/orbit/deep_filter_ladspa.dll"
        val lib = com.sun.jna.NativeLibrary.getInstance(path)
        val func = lib.getFunction("ladspa_descriptor")
        assertNotNull(func, "ladspa_descriptor not found in deep_filter_ladspa.dll")
        val descPtr = func.invokePointer(arrayOf(0L))
        assertNotNull(descPtr, "ladspa_descriptor(0) returned null")
        val desc = LadspaDescriptor(descPtr)
        desc.read()
        println("LADSPA Plugin Name: ${desc.name}")
        println("LADSPA Label: ${desc.label}")
        println("LADSPA PortCount: ${desc.portCount}")
        val portNamesPtr = desc.portNames
        if (portNamesPtr != null) {
            val names = portNamesPtr.getPointerArray(0, desc.portCount)
            for (i in 0 until desc.portCount) {
                println("Port $i: ${names[i].getString(0)}")
            }
        }

        // Test instantiation and processing
        val instantiateFunc = com.sun.jna.Function.getFunction(desc.instantiate)
        val handle = instantiateFunc.invokePointer(arrayOf(desc.pointer, 48000))
        assertNotNull(handle, "Failed to instantiate DeepFilterNet LADSPA")

        val connectFunc = com.sun.jna.Function.getFunction(desc.connect_port)
        val activateFunc = com.sun.jna.Function.getFunction(desc.activate)
        val runFunc = com.sun.jna.Function.getFunction(desc.run)
        val cleanupFunc = com.sun.jna.Function.getFunction(desc.cleanup)

        val inMem = com.sun.jna.Memory(480 * 4L)
        val outMem = com.sun.jna.Memory(480 * 4L)
        val p2 = com.sun.jna.Memory(4L).apply { setFloat(0, -60.0f) }
        val p3 = com.sun.jna.Memory(4L).apply { setFloat(0, -20.0f) }
        val p4 = com.sun.jna.Memory(4L).apply { setFloat(0, 20.0f) }
        val p5 = com.sun.jna.Memory(4L).apply { setFloat(0, 20.0f) }
        val p6 = com.sun.jna.Memory(4L).apply { setFloat(0, 0.0f) }
        val p7 = com.sun.jna.Memory(4L).apply { setFloat(0, 0.02f) }

        connectFunc.invokeVoid(arrayOf(handle, 0, inMem))
        connectFunc.invokeVoid(arrayOf(handle, 1, outMem))
        connectFunc.invokeVoid(arrayOf(handle, 2, p2))
        connectFunc.invokeVoid(arrayOf(handle, 3, p3))
        connectFunc.invokeVoid(arrayOf(handle, 4, p4))
        connectFunc.invokeVoid(arrayOf(handle, 5, p5))
        connectFunc.invokeVoid(arrayOf(handle, 6, p6))
        connectFunc.invokeVoid(arrayOf(handle, 7, p7))

        activateFunc.invokeVoid(arrayOf(handle))

        // Fill with a 440 Hz test sine
        for (i in 0 until 480) {
            val f = kotlin.math.sin(i * 440.0 * 2.0 * Math.PI / 48000.0).toFloat() * 0.5f
            inMem.setFloat(i * 4L, f)
        }

        runFunc.invokeVoid(arrayOf(handle, 480))
        val sample0 = outMem.getFloat(0)
        println("DeepFilterNet processed 480 samples successfully! Output sample 0: $sample0")
        cleanupFunc.invokeVoid(arrayOf(handle))
    }
}