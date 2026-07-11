package info.dourok.voicebot

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.Equalizer
import android.util.Log
import info.dourok.voicebot.data.Settings
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
    }

    private var audioTrack: AudioTrack
    private val playerScope = CoroutineScope(Dispatchers.IO + Job())
    private var isPlaying = false
    private var equalizer: Equalizer? = null

    init {
        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2 // Increase buffer size

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
        try {
            equalizer = Equalizer(0, audioTrack.audioSessionId)
            applyEq()
        } catch (e: Exception) {
            Log.e(TAG, "EQ init failed: ${e.message}")
        }
    }

    /** Re-read Settings.eqEnabled / eqBands and apply to the equalizer (live). */
    fun applyEq() {
        val eq = equalizer ?: return
        try {
            eq.enabled = Settings.eqEnabled
            if (Settings.eqEnabled) {
                val bands = Settings.eqBands
                val lo = eq.bandLevelRange[0].toInt()
                val hi = eq.bandLevelRange[1].toInt()
                for (b in 0 until eq.numberOfBands.toInt()) {
                    val mb = (bands.getOrNull(b) ?: 0).coerceIn(lo, hi)
                    eq.setBandLevel(b.toShort(), mb.toShort())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyEq failed: ${e.message}")
        }
    }

    fun eqInfo(): EqInfo? {
        val eq = equalizer ?: return null
        return try {
            val n = eq.numberOfBands.toInt()
            EqInfo(
                freqsHz = IntArray(n) { eq.getCenterFreq(it.toShort()) / 1000 }, // mHz -> Hz
                minMb = eq.bandLevelRange[0].toInt(),
                maxMb = eq.bandLevelRange[1].toInt(),
            )
        } catch (e: Exception) {
            null
        }
    }

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
                            audioTrack.write(it, 0, it.size)
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

    fun release() {
        stop()
        playerScope.cancel()  // stop the incomingAudioFlow collector coroutine (otherwise it leaks)
        try { equalizer?.release() } catch (_: Exception) {}
        equalizer = null
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