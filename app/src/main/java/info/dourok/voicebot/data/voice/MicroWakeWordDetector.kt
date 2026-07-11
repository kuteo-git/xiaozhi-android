package info.dourok.voicebot.data.voice

import android.content.Context
import android.util.Log
import com.aiboxplus.sample_wake_word.MicroWakeWordEngine
import info.dourok.voicebot.domain.voice.WakeWordDetector

/** "OK Nabu" wake detector backed by libmicro_wake_word_jni.so (model embedded in the .so). */
class MicroWakeWordDetector(context: Context) : WakeWordDetector {
    private val engine: MicroWakeWordEngine? = runCatching {
        MicroWakeWordEngine(false).apply {
            if (!initialize()) { Log.e(TAG, "init returned false"); return@runCatching null }
            Log.i(TAG, "microWakeWord (OK Nabu) ready")
        }
    }.onFailure { Log.e(TAG, "init failed: ${it.message}", it) }.getOrNull()

    override val isReady: Boolean get() = engine != null

    // microWakeWord wants 512-sample chunks; feed the frame in 512-sample slices.
    private val CHUNK = 512
    override fun process(pcm: ByteArray): Boolean {
        val e = engine ?: return false
        val total = pcm.size / 2
        val shorts = ShortArray(total)
        for (k in 0 until total) {
            val lo = pcm[k * 2].toInt() and 0xFF
            val hi = pcm[k * 2 + 1].toInt()
            shorts[k] = ((hi shl 8) or lo).toShort()
        }
        var i = 0
        var fired = false
        while (i < total) {
            val n = minOf(CHUNK, total - i)
            if (e.processAudioSamples(shorts.copyOfRange(i, i + n), n)) fired = true
            i += n
        }
        return fired
    }

    override fun reset() { engine?.reset() }
    override fun setStrict(strict: Boolean) { /* microWakeWord has no per-state sensitivity here */ }

    companion object { private const val TAG = "MicroWakeWord" }
}
