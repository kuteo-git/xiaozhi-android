package info.dourok.voicebot.domain.voice

import kotlinx.coroutines.flow.Flow

/**
 * Equalizer band layout (centre frequencies in Hz + the gain range in millibels).
 *
 * The control panel builds its sliders from whatever this reports, which is why the band layout
 * crossing this seam is worth the data class: moving from the platform's five fixed bands to
 * [AudioDsp]'s eight needed no change to `control.html` at all.
 */
data class EqInfo(val freqsHz: IntArray, val minMb: Int, val maxMb: Int)

/** Plays audio (TTS / music) streamed from the server as a flow of Opus frames. */
interface AudioPlayback {
    /**
     * Start consuming [opusFrames], decoding and playing them.
     * @param onPlaying invoked for each frame received (e.g. to mark the SPEAKING state).
     */
    fun start(opusFrames: Flow<ByteArray>, onPlaying: () -> Unit)

    /** Suspend until the currently buffered audio has finished playing. */
    suspend fun awaitCompletion()

    /** Discard any buffered audio immediately (used when the user interrupts playback). */
    fun flush()

    /**
     * Re-read the playback tuning from Settings -- the equalizer curves, the high-pass and the
     * loudness target -- and apply it live. One call rather than one per knob: they are all "what
     * the panel has just changed about how playback sounds", and a second entry point is a second
     * chance to forget one.
     */
    fun applyAudioSettings()

    /** Equalizer band layout for the control panel. */
    fun eqInfo(): EqInfo

    /** Stop playback and release native resources. */
    fun release()
}
