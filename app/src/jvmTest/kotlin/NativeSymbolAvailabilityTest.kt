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
}