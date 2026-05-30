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
    private val duckedSessions = mutableListOf<Pair<ISimpleAudioVolume, Float>>()
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var duckJob: Job? = null
    
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
            duckJob?.cancel()
            duckJob = scope.launch {
                val currentFactor = 1.0f - strength
                duckedSessions.forEach { (vol, original) ->
                    vol.SetMasterVolume(original * currentFactor, null)
                }
            }
            return
        }
        
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
                                                duckedSessions.add(Pair(volume, volRef.value))
                                            }
                                        }
                                        control2.Release()
                                    }
                                    control.Release()
                                }
                            }
                            sessionEnumerator.Release()
                            
                            duckJob?.cancel()
                            duckJob = scope.launch {
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
        }
    }

    fun unduckVolumes() {
        if (duckedSessions.isEmpty()) return
        
        val sessionsToUnduck = duckedSessions.toList()
        duckedSessions.clear()
        
        duckJob?.cancel()
        duckJob = scope.launch {
            // Restore original volume
            sessionsToUnduck.forEach { (vol, original) ->
                vol.SetMasterVolume(original, null)
            }
            delay(15) // small delay to ensure COM calls finish
            sessionsToUnduck.forEach { it.first.Release() }
        }
    }
}
