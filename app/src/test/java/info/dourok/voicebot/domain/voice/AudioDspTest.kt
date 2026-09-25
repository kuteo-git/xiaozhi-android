package info.dourok.voicebot.domain.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cover for the fault this class was written to end, measured on the live R1 on 2026-09-25.
 *
 * The platform equalizer's bands sit two octaves apart, so its filters reach each other: asking
 * **+4 dB at 230 Hz delivered +7.3 dB**, because the +10 dB asked at 60 Hz arrived there as well. A
 * tone control whose sliders move each other cannot be tuned by ear, and that is what the household
 * was fighting. So the assertions below are not about "does a filter filter" -- they are about **a
 * slider moving the frequency written under it and not its neighbour**, which is the property the
 * platform effect lacked.
 *
 * The response is measured by pushing a sine through the chain rather than by reading coefficients
 * back, because a wrong coefficient and a wrong formula both read as "the numbers I put in". The
 * shelf that shipped in the first draft of this class is exactly what that catches: it measured
 * +5.00 dB at its own labelled corner.
 */
class AudioDspTest {

    private val sr = 48000

    /** Gain the chain applies at [freqHz], in dB, measured on a settled sine. */
    private fun gainAt(chain: AudioDsp.Chain, freqHz: Double): Double {
        val n = sr                        // one second: long enough for the lowest band to settle
        val warm = sr / 4
        val pcm = ByteArray(n * 2)
        var inSum = 0.0
        for (i in 0 until n) {
            val s = 0.25 * sin(2.0 * PI * freqHz * i / sr)
            if (i >= warm) inSum += s * s
            val v = (s * 32767).toInt()
            pcm[i * 2] = (v and 0xFF).toByte()
            pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        chain.reset()
        chain.process(pcm)
        var outSum = 0.0
        for (i in warm until n) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val s = ((hi shl 8) or lo).toShort().toDouble() / 32768.0
            outSum += s * s
        }
        return 20.0 * log10(sqrt(outSum / (n - warm)) / sqrt(inSum / (n - warm)))
    }

    /** The equalizer alone: the protective high-pass is off, so a band's gain is only the band's. */
    private fun eq(vararg gainsMb: Int) =
        AudioDsp.Chain(sr).apply { configure(gainsMb, highPassHz = 0) }

    @Test
    fun `a flat curve with no high-pass changes nothing at all`() {
        val c = eq(0, 0, 0, 0, 0, 0, 0, 0)
        assertTrue(c.isTransparent(), "a flat curve should build no filters")
        val pcm = ByteArray(64) { (it * 7).toByte() }
        val copy = pcm.copyOf()
        c.process(pcm)
        assertTrue(pcm.contentEquals(copy), "a transparent chain must not touch the buffer")
    }

    @Test
    fun `a band delivers the gain asked for at its own centre`() {
        // 1280 Hz is band 4, well inside the voice.
        val got = gainAt(eq(0, 0, 0, 0, 600, 0, 0, 0), 1280.0)
        assertTrue(abs(got - 6.0) < 1.0, "asked +6.0 dB at 1280 Hz, got ${"%.2f".format(got)} dB")
    }

    @Test
    fun `the lowest band delivers its gain at its own centre too`() {
        // The shelf this replaced read +5.00 dB here for the same +10 dB asked, and put the rest of
        // the boost below 80 Hz, where this speaker makes no sound at all.
        val got = gainAt(eq(1000, 0, 0, 0, 0, 0, 0, 0), 80.0)
        assertTrue(abs(got - 10.0) < 1.5, "asked +10.0 dB at 80 Hz, got ${"%.2f".format(got)} dB")
    }

    @Test
    fun `a slider does not move the band two octaves away`() {
        // THE regression. The platform equalizer leaked +3.3 dB across this exact distance.
        val c = eq(1000, 0, 0, 0, 0, 0, 0, 0)     // +10 dB at 80 Hz
        val twoOctavesUp = gainAt(c, 320.0)
        assertTrue(
            twoOctavesUp < 2.0,
            "+10 dB at 80 Hz leaked ${"%.2f".format(twoOctavesUp)} dB into 320 Hz " +
                "(the platform equalizer leaked +3.3 dB here, which is the bug)",
        )
    }

    @Test
    fun `a neighbouring octave is only partly touched`() {
        // One octave away a one-octave filter is expected to reach; two is what must not.
        val c = eq(0, 0, 0, 0, 1000, 0, 0, 0)     // +10 dB at 1280 Hz
        val up = gainAt(c, 2560.0)
        val far = gainAt(c, 5120.0)
        assertTrue(up in 0.5..6.0, "one octave up read ${"%.2f".format(up)} dB")
        assertTrue(far < 2.0, "two octaves up read ${"%.2f".format(far)} dB, should be near nothing")
    }

    @Test
    fun `the high-pass takes the low end the driver cannot play and leaves the voice`() {
        val c = AudioDsp.Chain(sr).apply { configure(intArrayOf(), highPassHz = 60) }
        assertTrue(!c.isTransparent(), "a high-pass is a filter even with a flat curve")
        val rumble = gainAt(c, 30.0)
        val voice = gainAt(c, 300.0)
        assertTrue(rumble < -9.0, "30 Hz should be well down, read ${"%.2f".format(rumble)} dB")
        assertTrue(abs(voice) < 1.0, "300 Hz should be untouched, read ${"%.2f".format(voice)} dB")
    }

    @Test
    fun `a full-scale signal boosted to the ceiling never wraps`() {
        // A bare 16-bit cast turns an overshoot into the opposite sign, which is heard as a crack
        // rather than as distortion. The ceiling is asymptotic, so this can only pass or fail.
        val c = eq(0, 0, 0, 0, 1500, 0, 0, 0)     // +15 dB, the top of the panel
        val n = sr / 4
        val pcm = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (0.99 * 32767 * sin(2.0 * PI * 1280.0 * i / sr)).toInt()
            pcm[i * 2] = (v and 0xFF).toByte()
            pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        c.process(pcm)
        var worst = 0
        for (i in 0 until n) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val s = ((hi shl 8) or lo).toShort().toInt()
            if (abs(s) > worst) worst = abs(s)
        }
        assertTrue(worst <= 32767, "peak came back as $worst -- a wrap, not a ceiling")
        assertTrue(worst > 30000, "a +15 dB boost should reach the ceiling, peaked at $worst")
    }

    @Test
    fun `a band above Nyquist is dropped rather than built`() {
        // 10240 Hz cannot exist at a 16 kHz playback rate, and a biquad designed there comes out
        // unstable rather than merely wrong -- which on the playback path is a scream, not a tone.
        assertTrue(AudioDsp.bandUsable(10240, 48000))
        assertTrue(!AudioDsp.bandUsable(10240, 16000))
        val c = AudioDsp.Chain(16000).apply {
            configure(intArrayOf(0, 0, 0, 0, 0, 0, 0, 1500), highPassHz = 0)
        }
        assertTrue(c.isTransparent(), "the 10 kHz band must not be built at 16 kHz")
    }

    @Test
    fun `a short curve reads the missing bands as flat`() {
        // The panel can hand over fewer values than there are bands (an older stored setting, or the
        // five the platform equalizer used to have).
        val c = eq(0, 0, 600)
        assertTrue(!c.isTransparent())
        val got = gainAt(c, 320.0)
        assertTrue(abs(got - 6.0) < 1.0, "asked +6.0 dB at 320 Hz, got ${"%.2f".format(got)} dB")
    }
}
