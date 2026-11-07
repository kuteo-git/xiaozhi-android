package info.dourok.voicebot.data.voice

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.domain.voice.EqInfo

/**
 * The equalizer and loudness boost the panel tunes, attached to one audio session: the app's own
 * AudioTrack, or (see SinkEffects) the session the ROM's Bluetooth process plays a phone's music on.
 *
 * Holds the effect objects for as long as it lives, because an AudioEffect left to the garbage
 * collector is released by its finalizer and the sound silently loses its tuning.
 */
class PlaybackEffects(sessionId: Int) {
    private var equalizer: Equalizer? = null
    private var loudness: LoudnessEnhancer? = null

    init {
        try {
            equalizer = Equalizer(0, sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "EQ init failed: ${e.message}")
        }
        try {
            loudness = LoudnessEnhancer(sessionId)
        } catch (e: Exception) {
            // Registered in audio_effects.conf on this device, but a box without libldnhncr.so is a
            // box that still has to play -- so this is a missing improvement, not a failure to start.
            Log.e(TAG, "LoudnessEnhancer unavailable: ${e.message}")
        }
        apply()
    }

    /** False when neither effect could be created, so there is nothing to tune. */
    val attached: Boolean get() = equalizer != null || loudness != null

    /**
     * Re-read the playback tuning and apply it live.
     *
     * Loudness is **not** gated on [Settings.eqEnabled], deliberately: level and tone are two
     * different questions with two different answers, and the case that produced this — a Bluetooth
     * speaker that was too quiet with the equalizer switched off — is exactly the one a shared
     * switch would have made unreachable.
     */
    fun apply() {
        applyLoudness()
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

    private fun applyLoudness() {
        try {
            loudness?.let {
                val mb = Settings.loudnessMb
                it.setTargetGain(mb)
                it.enabled = mb > 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "loudness failed: ${e.message}")
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

    fun release() {
        try { equalizer?.release() } catch (_: Exception) {}
        equalizer = null
        try { loudness?.release() } catch (_: Exception) {}
        loudness = null
    }

    private companion object {
        const val TAG = "PlaybackEffects"
    }
}
