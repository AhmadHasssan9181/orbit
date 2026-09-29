package util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import repository.CredentialManager
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.AudioPlayerComponent

/**
 * Voice-message playback, backed by libVLC.
 *
 * ## This used to be a stub
 *
 * `play()` set `lastError = "Audio playback is not implemented on Desktop JVM"`
 * and returned. The play button in a voice message therefore did nothing at all,
 * on any platform, for the life of the feature -- no error, no sound, no state
 * change. It was not a bug that appeared under load; the code path simply did not
 * exist.
 *
 * ## Why libVLC and not javax.sound
 *
 * Matrix voice messages arrive as `.ogg` (Opus) or `.m4a`/`.mp3` in practice.
 * `javax.sound.sampled` can read WAV and AIFF and nothing else without extra
 * service providers, so it would work for roughly none of the real formats.
 * libVLC is already a dependency for video, plays every one of them, and means one
 * media stack instead of two.
 *
 * ## Authentication
 *
 * The media endpoint requires an Authorization header, which VLC will not send for
 * a URL. Putting the token in the query string would leak the long-lived account
 * credential into the homeserver's logs, so authenticated media is fetched here
 * with the header attached and the resulting TEMP FILE is handed to VLC. That also
 * gives VLC a seekable local source, so the scrubber can seek.
 */
actual class AudioPlayer actual constructor() {

    private val _isPlaying = MutableStateFlow(false)
    actual val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    actual val progress: StateFlow<Float> = _progress.asStateFlow()

    @Volatile
    var lastError: String? = null
        private set

    /**
     * `NativeDiscovery().discover()` is a blocking filesystem scan for libvlc.
     * Done once, lazily, outside composition, because running it on the Compose
     * thread freezes the window -- which is exactly what happened in the video
     * player.
     */
    private val vlcAvailable: Boolean by lazy {
        runCatching { NativeDiscovery().discover() }.isSuccess
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playerComponent: AudioPlayerComponent? = null
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var tempFile: java.io.File? = null

    /**
     * Serialises play/pause/stop. VLC's MediaPlayer is not thread-safe and these
     * all arrive from the UI thread while `release()` can arrive from a disposal
     * during composition.
     */
    private val lock = Any()

    actual fun play(url: String) {
        synchronized(lock) {
            stopLocked()
            lastError = null

            if (!vlcAvailable) {
                lastError = "libVLC is not available on this machine"
                println("WARN [AudioPlayer] $lastError")
                return
            }

            scope.launch {
                // Matrix media needs an auth header VLC will not send, so it is
                // fetched here and the TEMP FILE handed over -- which also gives VLC
                // a seekable local source for the scrubber. Anything else is a plain
                // URL VLC can fetch by itself.
                val isMatrixMedia = url.contains("/_matrix/")
                var localFile: java.io.File? = null
                val source: String = if (isMatrixMedia) {
                    when (val fetched = fetchAuthenticatedMedia(url)) {
                        is MediaFetch.LocalFile -> {
                            localFile = fetched.file
                            fetched.file.toURI().toString()
                        }
                        is MediaFetch.Failure -> {
                            lastError = "Audio fetch failed (status=${fetched.status}): ${fetched.detail}"
                            println("WARN [AudioPlayer] $lastError url=$url")
                            return@launch
                        }
                    }
                } else {
                    url
                }

                val ok = runCatching {
                    val component = AudioPlayerComponent()
                    val mediaPlayer = component.mediaPlayer()
                    mediaPlayer.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                        override fun finished(mediaPlayer: MediaPlayer) {
                            _isPlaying.value = false
                            _progress.value = 0f
                        }
                        override fun error(mediaPlayer: MediaPlayer) {
                            // vlcj 4.8 splits the API and there is no
                            // `mediaPlayer.media().lastError()`; the error string
                            // only exists on the native instance. The player's
                            // StateApi state() is the part readable from managed
                            // code, so that plus the stopped state is our signal.
                            lastError = "Playback failed (player state: ${runCatching {
                                mediaPlayer.status().state()
                            }.getOrNull()})"
                            println("WARN [AudioPlayer] $lastError")
                            _isPlaying.value = false
                            _progress.value = 0f
                        }
                    })
                    mediaPlayer.media().play(source)
                    _isPlaying.value = true
                    synchronized(lock) {
                        playerComponent = component
                        player = mediaPlayer
                        tempFile = localFile
                    }
                    startTicker()
                    true
                }.getOrElse { e ->
                    lastError = "Could not start playback: ${e.message}"
                    println("WARN [AudioPlayer] $lastError")
                    _isPlaying.value = false
                    false
                }
                if (!ok) runCatching { localFile?.delete() }
            }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val mp = player ?: break
                // vlcj 4.8: StatusApi carries all three, and `position()` is
                // already a 0..1 fraction, so no division by a duration that may
                // not be known yet.
                _progress.value = runCatching { mp.status().position() }
                    .getOrNull()?.takeIf { !it.isNaN() }?.coerceIn(0f, 1f) ?: 0f
                delay(200)
            }
        }
    }

    actual fun pause() {
        synchronized(lock) {
            runCatching { player?.controls()?.pause() }
            _isPlaying.value = false
        }
    }

    actual fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        ticker?.cancel()
        ticker = null
        runCatching { player?.controls()?.stop() }
        // Release, not just stop: each AudioPlayerComponent holds an audio output
        // and a libVLC instance reference. Voice messages scroll in and out of a
        // LazyColumn, so leaking one per row exhausts the audio subsystem.
        runCatching { playerComponent?.release() }
        playerComponent = null
        player = null
        runCatching { tempFile?.delete() }
        tempFile = null
        _isPlaying.value = false
        _progress.value = 0f
    }

    actual fun release() {
        synchronized(lock) { stopLocked() }
    }
}
