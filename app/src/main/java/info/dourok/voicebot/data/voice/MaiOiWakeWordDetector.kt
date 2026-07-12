package info.dourok.voicebot.data.voice

import android.content.Context
import android.util.Log
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.domain.voice.WakeWordDetector
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * "Mai ơi" wake word detector. Runs the trained mai_oi.tflite model (standard TFLite, quantized
 * streaming graph) via MicroFeaturesEngine for spectrogram feature extraction. Model input is
 * [1, 3, 40] int8 (scale=0.10196078568696976, zero_point=-128); output is [1, 1] uint8
 * (scale=0.00390625, zero_point=0) -- values read directly from the trained model, not guessed.
 */
class MaiOiWakeWordDetector(context: Context) : WakeWordDetector {

    private val features: MicroFeaturesEngine? = runCatching { MicroFeaturesEngine() }
        .onFailure { Log.e(TAG, "features init failed: ${it.message}", it) }.getOrNull()

    private val interpreter: Interpreter? = runCatching {
        val dir = File(context.filesDir, "mai_oi").apply { mkdirs() }
        val modelFile = copyAsset(context, "mai_oi/mai_oi.tflite", File(dir, "mai_oi.tflite"))
        Interpreter(File(modelFile)).also {
            Log.i(TAG, "mai_oi ready (inputs=${it.inputTensorCount}, outputs=${it.outputTensorCount})")
        }
    }.onFailure { Log.e(TAG, "init failed: ${it.message}", it) }.getOrNull()

    override val isReady: Boolean get() = interpreter != null && features?.isReady == true

    // Model input quantization (read directly from the trained model).
    private val inputScale = 0.10196078568696976f
    private val inputZeroPoint = -128
    private val outputScale = 0.00390625f
    private val outputZeroPoint = 0

    // Accumulates 3 incoming 40-dim feature frames to match the model's [1, 3, 40] streaming
    // input. Matches vendor/microWakeWord/microwakeword/inference.py's predict_spectrogram,
    // which (with input_feature_slices=3, stride=3) feeds NON-overlapping chunks: frames[0:3],
    // then frames[3:6], etc. -- inference runs once every 3 NEW frames, never on a sliding
    // window, because the model has internal streaming state (Stream layers) that persists
    // across interpreter.run() calls and expects each frame exactly once, in order.
    private val frameBuffer = mutableListOf<FloatArray>()

    override fun process(pcm: ByteArray): Boolean {
        val interp = interpreter ?: return false
        val total = pcm.size / 2
        val shorts = ShortArray(total)
        for (k in 0 until total) {
            val lo = pcm[k * 2].toInt() and 0xFF
            val hi = pcm[k * 2 + 1].toInt()
            shorts[k] = ((hi shl 8) or lo).toShort()
        }

        var fired = false
        for (frame in features?.processSamples(shorts) ?: emptyArray()) {
            frameBuffer.add(frame)
            if (frameBuffer.size == 3) {
                if (runInference(interp, frameBuffer)) fired = true
                frameBuffer.clear()
            }
        }
        return fired
    }

    private fun runInference(interp: Interpreter, frames: List<FloatArray>): Boolean {
        val input = ByteBuffer.allocateDirect(1 * 3 * 40).order(ByteOrder.nativeOrder())
        for (frame in frames) {
            for (v in frame) {
                val quantized = Math.round(v / inputScale) + inputZeroPoint
                input.put(quantized.coerceIn(-128, 127).toByte())
            }
        }
        input.rewind()
        val output = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
        interp.run(input, output)
        output.rewind()
        val rawOutput = output.get().toInt() and 0xFF
        val score = (rawOutput - outputZeroPoint) * outputScale
        return score >= threshold
    }

    override fun reset() {
        features?.reset()
        frameBuffer.clear()
        interpreter?.resetVariableTensors()
    }

    private var strict = false
    private var threshold: Float = Settings.maiOiThreshold.toFloatOrNull() ?: 0.5f
    override fun setStrict(strict: Boolean) {
        if (this.strict == strict) return
        this.strict = strict
        threshold = (if (strict) Settings.maiOiThresholdSpeaking else Settings.maiOiThreshold)
            .toFloatOrNull() ?: 0.5f
    }

    private fun copyAsset(context: Context, assetPath: String, dest: File): String {
        if (!dest.exists() || dest.length() == 0L) {
            context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
        }
        return dest.absolutePath
    }

    companion object {
        private const val TAG = "MaiOiWakeWord"
    }
}
