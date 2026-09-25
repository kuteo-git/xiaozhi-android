package info.dourok.voicebot

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.domain.voice.AudioDsp
import info.dourok.voicebot.domain.voice.EqInfo
import info.dourok.voicebot.domain.voice.MediaSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Plays decoded Opus PCM, tuned on the way out.
 *
 * The tone control is [AudioDsp] on the PCM rather than the platform `Equalizer` on the session --
 * that effect's five bands sit two octaves apart and reach each other, so its sliders did not mean
 * the frequencies they were labelled with. [AudioDsp]'s own note carries the measurements.
 *
 * What the platform still owns is **loudness**: `LoudnessEnhancer` is the one effect registered on
 * this box that raises level with a limiter under it (`/system/etc/audio_effects.conf` ->
 * `libldnhncr.so`), and it had never been used. Frequency here, level there, one owner each.
 */
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
    private var loudness: LoudnessEnhancer? = null

    // Two chains rather than one reconfigured on the fly: each keeps its own filter history, so a
    // song starting mid-session does not make the voice's filters restart from a stale state, and
    // there is no coefficient swap inside a frame to click.
    private val speechChain = AudioDsp.Chain(sampleRate)
    private val musicChain = AudioDsp.Chain(sampleRate)
    @Volatile private var tuningOn = false

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
            loudness = LoudnessEnhancer(audioTrack.audioSessionId)
        } catch (e: Exception) {
            // Registered in audio_effects.conf on this device, but a box without libldnhncr.so is a
            // box that still has to play -- so this is a missing improvement, not a failure to start.
            Log.e(TAG, "LoudnessEnhancer unavailable: ${e.message}")
        }
        applyAudioSettings()
    }

    /** Re-read the playback tuning from [Settings] and apply it live. */
    fun applyAudioSettings() {
        tuningOn = Settings.eqEnabled
        try {
            val hp = if (tuningOn) Settings.dspHighPassHz else 0
            speechChain.configure(if (tuningOn) Settings.eqBandsSpeech else IntArray(0), hp)
            musicChain.configure(if (tuningOn) Settings.eqBandsMusic else IntArray(0), hp)
        } catch (e: Exception) {
            Log.e(TAG, "dsp configure failed: ${e.message}")
        }
        try {
            loudness?.let {
                val mb = if (tuningOn) Settings.loudnessMb else 0
                it.setTargetGain(mb)
                it.enabled = mb > 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "loudness failed: ${e.message}")
        }
    }

    /** Band layout for the control panel, which builds its sliders from whatever this reports. */
    fun eqInfo() = EqInfo(
        freqsHz = AudioDsp.BAND_FREQS_HZ.copyOf(),
        minMb = AudioDsp.MIN_MB,
        maxMb = AudioDsp.MAX_MB,
    )

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
                            // Per frame, because one pipeline carries both a spoken reply and a
                            // song and the two want different curves -- a volatile read, no more.
                            if (tuningOn) {
                                val chain =
                                    if (MediaSessionState.isMusicPlaying) musicChain else speechChain
                                chain.process(it)
                            }
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
            // The filters hold a tail of audio that was thrown away. Carrying it into the next
            // utterance is a fragment of the interrupted one, filtered -- a thump at the start of
            // the reply that replaced it.
            speechChain.reset()
            musicChain.reset()
        } catch (e: Exception) {
            Log.e(TAG, "flush: ${e.message}")
        }
    }

    fun release() {
        stop()
        playerScope.cancel()  // stop the incomingAudioFlow collector coroutine (otherwise it leaks)
        try { loudness?.release() } catch (_: Exception) {}
        loudness = null
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
