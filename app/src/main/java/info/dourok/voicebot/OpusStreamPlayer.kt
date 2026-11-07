package info.dourok.voicebot

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import android.os.SystemClock
import info.dourok.voicebot.data.voice.PlaybackEffects
import info.dourok.voicebot.domain.voice.AppLog
import info.dourok.voicebot.domain.voice.EqInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class OpusStreamPlayer(
    private val sampleRate: Int,
    private val channels: Int,
    frameSizeMs: Int
) {
    companion object {
        private const val TAG = "OpusStreamPlayer"
        /** One rebuild per second at most: an output that is dead for good must not be rebuilt per frame. */
        private const val REBUILD_MIN_GAP_MS = 1000L
    }

    private lateinit var audioTrack: AudioTrack
    private val playerScope = CoroutineScope(Dispatchers.IO + Job())
    private var isPlaying = false
    private var effects: PlaybackEffects? = null
    private var channelConfig = 0
    private var bufferSize = 0
    /** elapsedRealtime of the last rebuild, so a permanently dead output cannot spin (see [onWriteFailed]). */
    private var lastRebuildAt = 0L

    init {
        channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2 // Increase buffer size
        buildTrack()
    }

    /**
     * Builds the track and attaches the effects to it. Factored out of `init` because the track
     * is also rebuilt when a write fails -- and the effects have to come with it, since it is
     * bound to the track's audio session id and that id changes.
     */
    private fun buildTrack() {
        // R1 = Android 5.1.1 (API 22): AudioTrack.Builder is API 23+ -> ClassNotFound CRASH.
        // Use the legacy constructor (API 3+, deprecated but works).
        @Suppress("DEPRECATION")
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
            AudioTrack.MODE_STREAM
        )
        effects = PlaybackEffects(audioTrack.audioSessionId)
    }

    /** Re-read the playback tuning and apply it live. */
    fun applyAudioSettings() {
        effects?.apply()
    }

    fun eqInfo(): EqInfo? = effects?.eqInfo()

    fun start(pcmFlow: Flow<ByteArray?>) {
        if (!isPlaying) {
            isPlaying = true
            if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack.play()
            }

            playerScope.launch {
                pcmFlow.collect { pcmData ->
                    pcmData?.let {
                        try {
                            // write() reports failure by RETURN CODE, not by throwing, so the catch
                            // below never saw a dead track: it returned -6 for every frame and the
                            // speaker went quiet with nothing anywhere saying why. The moment that
                            // becomes likely is a route change -- an A2DP speaker connecting or
                            // dropping tears down the output thread this track was mixed on.
                            val written = audioTrack.write(it, 0, it.size)
                            if (written < 0) onWriteFailed(written)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error writing to AudioTrack", e)
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        if (isPlaying) {
            isPlaying = false
            if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack.stop()
            }
        }
    }

    /** Discard buffered-but-unplayed audio immediately (on interrupt) so playback stops at once. */
    fun flush() {
        try {
            if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack.pause()
                audioTrack.flush()
                audioTrack.play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "flush: ${e.message}")
        }
    }

    /**
     * A negative write is the track saying it cannot be written to again, so it is rebuilt rather
     * than logged and retried for ever. ERROR_DEAD_OBJECT is documented from API 24 and this device
     * is API 22 -- safe to name anyway, because a `static final int` is inlined into the dex at
     * compile time and nothing is looked up on the platform at runtime.
     */
    private fun onWriteFailed(code: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRebuildAt < REBUILD_MIN_GAP_MS) return
        lastRebuildAt = now
        AppLog.w("Âm thanh: AudioTrack trả lỗi $code, dựng lại")
        Log.e(TAG, "write() returned $code, rebuilding the track")
        effects?.release()
        effects = null
        try { audioTrack.release() } catch (e: Exception) { Log.e(TAG, "rebuild release: ${e.message}") }
        buildTrack()
        if (isPlaying && audioTrack.state == AudioTrack.STATE_INITIALIZED) audioTrack.play()
    }

    fun release() {
        stop()
        playerScope.cancel()  // stop the incomingAudioFlow collector coroutine (otherwise it leaks)
        effects?.release()
        effects = null
        try { audioTrack.release() } catch (e: Exception) { Log.e(TAG, "release: ${e.message}") }
    }

    suspend fun waitForPlaybackCompletion() {
        var position = 0
        while (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING && audioTrack.playbackHeadPosition != position) {
            Log.i(TAG, "audioTrack.playState: ${audioTrack.playState}, playbackHeadPosition: ${audioTrack.playbackHeadPosition}")
            position = audioTrack.playbackHeadPosition
            delay(100) // poll interval
        }
    }

    protected fun finalize() {
        release()
    }
}