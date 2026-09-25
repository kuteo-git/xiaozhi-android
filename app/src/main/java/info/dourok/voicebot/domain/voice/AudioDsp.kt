package info.dourok.voicebot.domain.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The playback tone control, done on the decoded PCM here rather than by the platform's Equalizer.
 *
 * Measured on the R1 (2026-09-25), and this is the whole reason the platform effect was dropped.
 * AudioFlinger on this box offers only the stock AOSP effect bundle -- `/system/etc/audio_effects.conf`
 * registers nothing else, there is no vendor effects file and the HW effect proxy is commented out --
 * and that equalizer is fixed at 60/230/910/3600/14000 Hz. Two octaves between bands means every
 * filter is broad enough to reach its neighbours: asking **+4 dB at 230 Hz delivered +7.3 dB**,
 * because the +10 dB asked at 60 Hz arrived there too. A slider that moves a frequency it is not
 * labelled with cannot be tuned by ear, and that is what "chỉnh mãi không hay" was.
 *
 * The two outer bands were worse than useless. The TTS voice measures **-45 dB at 60 Hz** and
 * **-68 dB at 14 kHz** against its own peak, so those sliders had nothing to act on -- applying the
 * shipped curve to a real TTS file moved its peak from 0.796 only to 0.812. And the speaker sits at
 * the mic's own noise floor below 100 Hz while reading +35 dB at 120 Hz, so it rolls off under about
 * a hundred: asking for 60 Hz spends excursion on heat and, through that broad skirt, pays for it in
 * boom at 230 Hz.
 *
 * So, three decisions, each of them that measurement:
 *  - **One band per octave** ([BAND_FREQS_HZ]), which is what makes a slider mean the frequency
 *    written under it.
 *  - **Every band is peaking, none is a shelf.** A shelf was written first and measured +5.00 dB at
 *    its own 80 Hz corner while delivering the asked +10 dB *below* it -- the outer-band fault again,
 *    one level milder, and on this speaker the region a low shelf reaches is exactly the region the
 *    driver cannot play.
 *  - **A high-pass in front** ([DEFAULT_HIGH_PASS_HZ]), because what the driver cannot turn into
 *    sound it turns into excursion, and that excursion modulates the band the voice actually lives
 *    in. Settable, and 0 turns it off: it is a decision about this cabinet, not a law.
 *
 * Dynamics are deliberately NOT here. [ceiling] is a ceiling and nothing else -- it cannot touch a
 * sample that is not about to wrap -- and loudness belongs to the platform's `LoudnessEnhancer`,
 * which is tuned and already on the device. One owner per job: this class decides frequency, that
 * effect decides level.
 */
object AudioDsp {

    /**
     * One band per octave. These are the numbers a panel slider is labelled with, so they are the
     * frequencies that must actually move -- see the class note for what two-octave spacing cost.
     */
    val BAND_FREQS_HZ = intArrayOf(80, 160, 320, 640, 1280, 2560, 5120, 10240)

    /** Q for a one-octave-wide peaking filter: sqrt(2^n)/(2^n - 1) with n = 1. */
    const val BAND_Q = 1.4142136f

    /** Butterworth Q: the flattest second-order high-pass, no peak at the corner. */
    private const val HIGH_PASS_Q = 0.70710678f

    /**
     * Where the protective high-pass sits. Below this the R1's driver was measured at the mic's own
     * noise floor, so everything under it is excursion that never becomes sound.
     */
    const val DEFAULT_HIGH_PASS_HZ = 60

    /** Panel range, in millibels, matching what the old platform equalizer reported. */
    const val MIN_MB = -1500
    const val MAX_MB = 1500

    /**
     * Where the soft knee starts. Above it the output is shaped asymptotically toward full scale, so
     * a boosted band can never wrap to the opposite sign -- which is what a bare 16-bit cast does,
     * and it is heard as a crack rather than as distortion.
     */
    private const val KNEE = 0.85f

    private fun ceiling(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val shaped = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
        return if (x < 0f) -shaped else shaped
    }

    /** Clamp a caller's millibel value into the range the panel offers. */
    fun clampMb(mb: Int): Int = mb.coerceIn(MIN_MB, MAX_MB)

    /**
     * A band at or above Nyquist cannot be realised, and a biquad designed there comes out unstable
     * rather than merely wrong -- which on the playback path is a scream, not a tone.
     */
    fun bandUsable(freqHz: Int, sampleRate: Int): Boolean = freqHz < sampleRate * 0.45f

    /** One direct-form-1 biquad. Stateful, so one instance per band per stream. */
    class Biquad(
        private val b0: Float, private val b1: Float, private val b2: Float,
        private val a1: Float, private val a2: Float,
    ) {
        private var x1 = 0f; private var x2 = 0f
        private var y1 = 0f; private var y2 = 0f

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }

        fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }

        companion object {
            /** RBJ cookbook peaking EQ. */
            fun peaking(freqHz: Float, gainDb: Float, q: Float, sampleRate: Int): Biquad {
                val a = dbToAmp(gainDb / 2f)          // sqrt of the linear gain, as the cookbook has it
                val w0 = 2f * PI.toFloat() * freqHz / sampleRate
                val alpha = sin(w0) / (2f * q)
                val c = cos(w0)
                val a0 = 1f + alpha / a
                return Biquad(
                    (1f + alpha * a) / a0, (-2f * c) / a0, (1f - alpha * a) / a0,
                    (-2f * c) / a0, (1f - alpha / a) / a0,
                )
            }

            /** RBJ cookbook second-order high-pass. */
            fun highPass(freqHz: Float, sampleRate: Int): Biquad {
                val w0 = 2f * PI.toFloat() * freqHz / sampleRate
                val alpha = sin(w0) / (2f * HIGH_PASS_Q)
                val c = cos(w0)
                val a0 = 1f + alpha
                return Biquad(
                    ((1f + c) / 2f) / a0, (-(1f + c)) / a0, ((1f + c) / 2f) / a0,
                    (-2f * c) / a0, (1f - alpha) / a0,
                )
            }

            fun dbToAmp(db: Float): Float = Math.pow(10.0, (db / 20f).toDouble()).toFloat()
        }
    }

    /**
     * The filter chain for one stream. Coefficients are rebuilt by [configure]; the filter *state*
     * survives that on purpose, because a curve is changed while audio is running and resetting the
     * history mid-stream is a click.
     */
    class Chain(private val sampleRate: Int) {
        private var filters: Array<Biquad> = emptyArray()

        /**
         * @param gainsMb one entry per [BAND_FREQS_HZ]; missing or short reads as 0.
         * @param highPassHz 0 to leave the low end alone.
         */
        fun configure(gainsMb: IntArray, highPassHz: Int = DEFAULT_HIGH_PASS_HZ) {
            val built = ArrayList<Biquad>(BAND_FREQS_HZ.size + 1)
            if (highPassHz > 0 && bandUsable(highPassHz, sampleRate)) {
                built += Biquad.highPass(highPassHz.toFloat(), sampleRate)
            }
            for (i in BAND_FREQS_HZ.indices) {
                val f = BAND_FREQS_HZ[i]
                val db = clampMb(gainsMb.getOrElse(i) { 0 }) / 100f
                if (db == 0f || !bandUsable(f, sampleRate)) continue
                built += Biquad.peaking(f.toFloat(), db, BAND_Q, sampleRate)
            }
            filters = built.toTypedArray()
        }

        /** True when the chain would not change a single sample, so the caller can skip the walk. */
        fun isTransparent(): Boolean = filters.isEmpty()

        /**
         * Filter 16-bit little-endian mono PCM in place. In place because this runs once per Opus
         * frame on the playback path, and a fresh array per frame is garbage the R1 has to collect
         * while it is decoding the next one.
         */
        fun process(pcm: ByteArray, length: Int = pcm.size) {
            if (filters.isEmpty()) return
            var i = 0
            val end = length - 1
            while (i < end) {
                val lo = pcm[i].toInt() and 0xFF
                val hi = pcm[i + 1].toInt()
                var s = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                for (f in filters) s = f.process(s)
                val out = (ceiling(s) * 32767f).toInt()
                pcm[i] = (out and 0xFF).toByte()
                pcm[i + 1] = ((out shr 8) and 0xFF).toByte()
                i += 2
            }
        }

        fun reset() { for (f in filters) f.reset() }
    }
}
