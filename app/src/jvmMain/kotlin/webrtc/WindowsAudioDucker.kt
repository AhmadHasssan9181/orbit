package webrtc

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.FloatByReference
import kotlinx.coroutines.*

val CLSID_MMDeviceEnumerator = Guid.CLSID("BCDE0395-E52F-467C-8E3D-C4579291692E")
val IID_IMMDeviceEnumerator = Guid.IID("A95664D2-9614-4F35-A746-DE8DB63617E6")
val IID_IAudioSessionManager2 = Guid.REFIID(Guid.IID("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"))
val IID_IAudioSessionControl2 = Guid.REFIID(Guid.IID("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"))
val IID_ISimpleAudioVolume = Guid.REFIID(Guid.IID("87CE5498-68D6-44E5-9215-6DA47EF883D8"))

class IMMDeviceEnumerator(pvInstance: Pointer) : Unknown(pvInstance) {
    fun GetDefaultAudioEndpoint(dataFlow: Int, role: Int, ppDevice: PointerByReference): WinNT.HRESULT {
        return _invokeNativeObject(4, arrayOf(this.pointer, dataFlow, role, ppDevice), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }
}

class IMMDevice(pvInstance: Pointer) : Unknown(pvInstance) {
    fun Activate(iid: Guid.REFIID, dwClsCtx: Int, pActivationParams: Pointer?, ppInterface: PointerByReference): WinNT.HRESULT {
        return _invokeNativeObject(3, arrayOf(this.pointer, iid.pointer, dwClsCtx, pActivationParams, ppInterface), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }
}

class IAudioSessionManager2(pvInstance: Pointer) : Unknown(pvInstance) {
    fun GetAudioSessionControl(audioSessionGuid: Pointer?, streamFlags: Int, ppSessionControl: PointerByReference): WinNT.HRESULT {
        return _invokeNativeObject(3, arrayOf(this.pointer, audioSessionGuid, streamFlags, ppSessionControl), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }
    fun GetSessionEnumerator(ppSessionEnum: PointerByReference): WinNT.HRESULT {
        return _invokeNativeObject(5, arrayOf(this.pointer, ppSessionEnum), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }
}

class IAudioSessionEnumerator(pvInstance: Pointer) : Unknown(pvInstance) {
    fun GetCount(sessionCount: IntByReference): WinNT.HRESULT {
        return _invokeNativeObject(3, arrayOf(this.pointer, sessionCount), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }
    fun GetSession(sessionCount: Int, ppSession: PointerByReference): WinNT.HRESULT {
        return _invokeNativeObject(4, arrayOf(this.pointer, sessionCount, ppSession), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }
}

class IAudioSessionControl(pvInstance: Pointer) : Unknown(pvInstance) {
}

class IAudioSessionControl2(pvInstance: Pointer) : Unknown(pvInstance) {
    fun GetProcessId(pRetVal: IntByReference): WinNT.HRESULT {
        return _invokeNativeObject(14, arrayOf(this.pointer, pRetVal), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }
    fun SetDuckingPreference(optOut: Boolean): WinNT.HRESULT {
        return _invokeNativeObject(17, arrayOf(this.pointer, if (optOut) 1 else 0), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }
}

class ISimpleAudioVolume(pvInstance: Pointer) : Unknown(pvInstance) {
    fun SetMasterVolume(fLevel: Float, eventContext: Pointer?): WinNT.HRESULT {
        return _invokeNativeObject(3, arrayOf(this.pointer, fLevel, eventContext), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }
    fun GetMasterVolume(pfLevel: FloatByReference): WinNT.HRESULT {
        return _invokeNativeObject(4, arrayOf(this.pointer, pfLevel), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }
}

object WindowsAudioDucker {
    private var isWindowsDuckingDisabled = false
    // Read from the caller thread while Dispatchers.IO fade loops iterate it, so
    // it has to be concurrent; CopyOnWriteArrayList also gives us a stable
    // snapshot for iteration without ConcurrentModificationException.
    private val duckedSessions = java.util.concurrent.CopyOnWriteArrayList<Pair<ISimpleAudioVolume, Float>>()

    /**
     * Native pointers already captured in [duckedSessions].
     *
     * CopyOnWriteArrayList makes each individual operation safe, not the
     * check-then-add pair -- so without this, two concurrent ducking passes can
     * capture one COM interface twice and `unduckVolumes` releases it twice. See the
     * capture site for why that matters.
     */
    private val duckedKeys = java.util.Collections.synchronizedSet(mutableSetOf<Any>())
    private val duckCaptureLock = Any()
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val fadeLock = Any()
    private var duckJob: Job? = null

    /**
     * Starts a fade, cancelling AND joining the previous one. cancel() alone
     * cannot preempt a running non-suspending forEach, so two fade loops could
     * be inside SetMasterVolume at the same time and the older one would win.
     * COM is also initialized here (not just on the caller thread) because these
     * coroutines run on Dispatchers.IO, which was never COM-initialized.
     */
    private fun replaceFadeJob(block: suspend CoroutineScope.() -> Unit) {
        synchronized(fadeLock) {
            val previous = duckJob
            duckJob = scope.launch {
                previous?.cancel()
                previous?.join()
                val comInited = comInitialize(Ole32.COINIT_APARTMENTTHREADED)
                try {
                    block()
                } finally {
                    // Only balance what we actually took. Uninitializing after a
                    // FAILED CoInitializeEx decrements an apartment count this
                    // coroutine never incremented, which poisons the shared
                    // Dispatchers.IO worker for every other COM user.
                    if (comInited) runCatching { Ole32.INSTANCE.CoUninitialize() }
                }
            }
        }
    }

    /**
     * CoInitializeEx on the CURRENT thread, returning whether it succeeded and
     * therefore whether the caller owes a CoUninitialize.
     *
     * S_OK and S_FALSE both mean "initialized here", so both must be balanced.
     * A failure (RPC_E_CHANGED_MODE on a worker already in another apartment,
     * E_OUTOFMEMORY) must not be followed by CoUninitialize.
     */
    private fun comInitialize(apartment: Int): Boolean {
        val hr = runCatching { Ole32.INSTANCE.CoInitializeEx(null, apartment) }
            .getOrElse { return false }
            .toLong()
        // HRESULT: negative means failure. S_FALSE (1) still counts as success.
        return hr >= 0
    }
    
    fun disableWindowsDucking() {
        if (isWindowsDuckingDisabled) return
        try {
            Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED)
            val currentPid = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentProcessId()
            val ppEnumerator = PointerByReference()
            if (COMUtils.SUCCEEDED(Ole32.INSTANCE.CoCreateInstance(CLSID_MMDeviceEnumerator, null, com.sun.jna.platform.win32.WTypes.CLSCTX_INPROC_SERVER, IID_IMMDeviceEnumerator, ppEnumerator))) {
                val enumerator = IMMDeviceEnumerator(ppEnumerator.value)
                
                // Helper to apply ducking preference to a specific endpoint
                fun applyToEndpoint(dataFlow: Int, role: Int) {
                    val ppDevice = PointerByReference()
                    if (COMUtils.SUCCEEDED(enumerator.GetDefaultAudioEndpoint(dataFlow, role, ppDevice))) {
                        val device = IMMDevice(ppDevice.value)
                        val ppSessionManager = PointerByReference()
                        if (COMUtils.SUCCEEDED(device.Activate(IID_IAudioSessionManager2, com.sun.jna.platform.win32.WTypes.CLSCTX_INPROC_SERVER, null, ppSessionManager))) {
                            val sessionManager = IAudioSessionManager2(ppSessionManager.value)
                            
                            val ppSessionControl = PointerByReference()
                            if (COMUtils.SUCCEEDED(sessionManager.GetAudioSessionControl(null, 0, ppSessionControl))) {
                                val control = Unknown(ppSessionControl.value)
                                val ppControl2 = PointerByReference()
                                if (COMUtils.SUCCEEDED(control.QueryInterface(IID_IAudioSessionControl2, ppControl2))) {
                                    val control2 = IAudioSessionControl2(ppControl2.value)
                                    control2.SetDuckingPreference(true)
                                    println("DEBUG: [Ducker] SetDuckingPreference(true) called on process default audio session (Flow: $dataFlow, Role: $role)")
                                    control2.Release()
                                }
                                control.Release()
                            }
                            sessionManager.Release()
                        }
                        device.Release()
                    }
                }
                
                applyToEndpoint(0, 0) // eRender, eConsole
                applyToEndpoint(0, 2) // eRender, eCommunications
                applyToEndpoint(1, 2) // eCapture, eCommunications
                
                enumerator.Release()
            }
            isWindowsDuckingDisabled = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun duckVolumes(strength: Float) {
        if (strength <= 0.01f) {
            unduckVolumes()
            return
        }
        
        if (duckedSessions.isNotEmpty()) {
            replaceFadeJob {
                val currentFactor = 1.0f - strength
                duckedSessions.forEach { (vol, original) ->
                    vol.SetMasterVolume(original * currentFactor, null)
                }
            }
            return
        }
        
        // COM MUST be initialized on the calling thread before the enumeration
        // below. These callers are UI-driven (EDT / Compose scope) and JNA does
        // not initialize COM for them, so CoCreateInstance failed with
        // CO_E_NOTINITIALIZED, duckedSessions stayed empty, the fade job was
        // never reached — and because the list stayed empty, ducking silently
        // did nothing on every subsequent call too. Only the FADE runs on the
        // COM-initialized Dispatchers.IO worker; the enumeration does not.
        val comInited = comInitialize(Ole32.COINIT_MULTITHREADED)
        try {
            val currentPid = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentProcessId()
            val ppEnumerator = PointerByReference()
            if (COMUtils.SUCCEEDED(Ole32.INSTANCE.CoCreateInstance(CLSID_MMDeviceEnumerator, null, com.sun.jna.platform.win32.WTypes.CLSCTX_INPROC_SERVER, IID_IMMDeviceEnumerator, ppEnumerator))) {
                val enumerator = IMMDeviceEnumerator(ppEnumerator.value)
                val ppDevice = PointerByReference()
                if (COMUtils.SUCCEEDED(enumerator.GetDefaultAudioEndpoint(0 /* eRender */, 0 /* eConsole */, ppDevice))) {
                    val device = IMMDevice(ppDevice.value)
                    val ppSessionManager = PointerByReference()
                    if (COMUtils.SUCCEEDED(device.Activate(IID_IAudioSessionManager2, com.sun.jna.platform.win32.WTypes.CLSCTX_INPROC_SERVER, null, ppSessionManager))) {
                        val sessionManager = IAudioSessionManager2(ppSessionManager.value)
                        val ppSessionEnum = PointerByReference()
                        if (COMUtils.SUCCEEDED(sessionManager.GetSessionEnumerator(ppSessionEnum))) {
                            val sessionEnumerator = IAudioSessionEnumerator(ppSessionEnum.value)
                            val countRef = IntByReference()
                            sessionEnumerator.GetCount(countRef)
                            val count = countRef.value
                            for (i in 0 until count) {
                                val ppSession = PointerByReference()
                                if (COMUtils.SUCCEEDED(sessionEnumerator.GetSession(i, ppSession))) {
                                    val control = Unknown(ppSession.value)
                                    val ppControl2 = PointerByReference()
                                    if (COMUtils.SUCCEEDED(control.QueryInterface(IID_IAudioSessionControl2, ppControl2))) {
                                        val control2 = IAudioSessionControl2(ppControl2.value)
                                        val pidRef = IntByReference()
                                        control2.GetProcessId(pidRef)
                                        val pid = pidRef.value
                                        if (pid != 0 && pid != currentPid) {
                                            val ppVolume = PointerByReference()
                                            if (COMUtils.SUCCEEDED(control.QueryInterface(IID_ISimpleAudioVolume, ppVolume))) {
                                                val volume = ISimpleAudioVolume(ppVolume.value)
                                                val volRef = FloatByReference()
                                                volume.GetMasterVolume(volRef)
                                                // De-duplicate BEFORE capturing.
                                                //
                                                // `duckedSessions` is a CopyOnWriteArrayList, so `add`
                                                // and `isNotEmpty` are individually safe -- which is
                                                // exactly the trap: the pair is not atomic, and two
                                                // concurrent ducking passes can capture the SAME
                                                // ISimpleAudioVolume twice. `unduckVolumes` then
                                                // calls Release() once per entry, and a double COM
                                                // Release decrements the refcount below what the
                                                // audio subsystem still holds -- which surfaces much
                                                // later as an access violation inside WASAPI, in
                                                // code that has nothing to do with ducking.
                                                //
                                                // Keyed on the native pointer, not on equals: JNA
                                                // interface wrappers do not override it, so two
                                                // wrappers for one COM object compare unequal.
                                                synchronized(duckCaptureLock) {
                                                    val key: Any = ppVolume.value
                                                        ?: System.identityHashCode(volume)
                                                    if (duckedKeys.add(key)) {
                                                        duckedSessions.add(Pair(volume, volRef.value))
                                                    }
                                                }
                                            }
                                        }
                                        control2.Release()
                                    }
                                    control.Release()
                                }
                            }
                            sessionEnumerator.Release()

                            replaceFadeJob {
                                // Fade down
                                for (step in 1..10) {
                                    val currentFactor = 1.0f - (strength * (step / 10.0f))
                                    duckedSessions.forEach { (vol, original) ->
                                        vol.SetMasterVolume(original * currentFactor, null)
                                    }
                                    delay(15)
                                }
                            }
                        }
                        sessionManager.Release()
                    }
                    device.Release()
                }
                enumerator.Release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (comInited) runCatching { Ole32.INSTANCE.CoUninitialize() }
        }
    }

    fun unduckVolumes() {
        val sessionsToUnduck = synchronized(duckCaptureLock) {
            if (duckedSessions.isEmpty()) return
            val copy = duckedSessions.toList()
            duckedSessions.clear()
            // Must clear alongside the list, or the next duck pass sees the
            // pointers as "already captured" and captures nothing -- leaving
            // playback permanently ducked.
            duckedKeys.clear()
            copy
        }

        replaceFadeJob {
            try {
                // Restore original volume
                sessionsToUnduck.forEach { (vol, original) ->
                    vol.SetMasterVolume(original, null)
                }
                delay(15) // small delay to ensure COM calls finish
            } finally {
                // Release in a non-cancellable context. These Release calls used
                // to sit after a suspension point, so a duckVolumes() landing
                // inside the 15 ms delay cancelled this job first and leaked
                // every captured ISimpleAudioVolume permanently — one leaked
                // interface per rapid screen-share toggle.
                withContext(kotlinx.coroutines.NonCancellable) {
                    sessionsToUnduck.forEach { runCatching { it.first.Release() } }
                }
            }
        }
    }
}
