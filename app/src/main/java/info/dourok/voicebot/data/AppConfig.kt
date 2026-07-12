package info.dourok.voicebot.data

/**
 * Central place for tweakable settings. Change a value here, then rebuild.
 * (Kept in one object so the knobs aren't scattered across the codebase.)
 */
object AppConfig {

    /** Server WebSocket URL. Empty by default (generic build): set at runtime via the Setup tab
     *  (OTA URL → provisioned ws url/token). No deployment-specific endpoint is baked in. */
    const val WS_URL = ""

    /**
     * Playback (server -> device TTS/music) sample rate. MUST match the server's
     * xiaozhi.audio_params.sample_rate. Higher = better music/voice quality (16k=8kHz band,
     * 24k=12kHz, 48k=full). Mic stays 16k (STT). Lower if the device stutters at 48k.
     */
    const val PLAYBACK_SAMPLE_RATE = 48000

    /**
     * Snowboy "Alexa" sensitivity while idle, 0..1 (higher = easier to trigger).
     * 2026-06-28: 0.8 -> 0.5 — 0.8 + boost gain 3x làm TV/tiếng ồn tự kích wake. Gain không cải
     * thiện SNR (khuếch đại cả giọng lẫn ồn) nên hạ gain + hạ sensitivity. Far Alexa khó thì
     * tăng SnowboyDetect.SetAudioGain (chỉ khuếch đại TRONG snowboy, không đụng STT), đừng tăng cái này.
     */
    const val WAKE_SENSITIVITY = "0.5"

    /**
     * "Mai ơi" detection threshold, 0..1. Real-hardware evaluation showed a wide, clean
     * separation — positives scored ~0.95-1.0, hard negatives ~0.00-0.02 — so 0.5 is a safe,
     * well-centered default with substantial margin on both sides; no need for asymmetric
     * normal/speaking defaults the way Snowboy's 0.5/0.4 split needed tuning.
     */
    const val MAI_OI_THRESHOLD = "0.5"
    const val MAI_OI_THRESHOLD_SPEAKING = "0.5"

    /**
     * Software mic gain applied to captured PCM. 2026-06-28: 3.0 -> 1.0 (TẮT) — 3.0 + tanh làm
     * MÉO sóng (clip/nén) khiến PhoWhisper đọc sai, và khuếch đại ồn gây false-wake. Để AGC (HAL +
     * AutomaticGainControl) lo far-field thay vì boost cứng. 1.0 = transparent (applyGain bỏ qua).
     * Far-field còn yếu thì nhích ~1.5 và CÂN NHẮC tách wake-detect khỏi luồng đã gain.
     */
    const val MIC_GAIN = 1.0f

    /**
     * AudioRecord input source — đổi để thử far-field (mỗi source HAL tuning/gain khác nhau):
     * 6=VOICE_RECOGNITION (mic-array, sạch — đang dùng), 7=VOICE_COMMUNICATION (call path: AEC+AGC,
     * thường THU TO hơn), 1=MIC (thô), 0=DEFAULT, 5=CAMCORDER. Đổi runtime qua panel key `mic_source`
     * + restart (không cần build lại).
     */
    const val MIC_SOURCE = 6

    /**
     * AGC luồng STT (áp TRƯỚC opus, KHÔNG đụng wake): kéo giọng xa/nhỏ lên mức chuẩn cho opus+Whisper.
     * AGC_TARGET = đỉnh mục tiêu (0..1); AGC_MAX_GAIN = trần khuếch đại. Bật/tắt + chỉnh runtime qua
     * panel (agc_enabled / agc_target / agc_max_gain) khỏi build lại. Tắt -> STT nhận audio thô.
     */
    const val AGC_ENABLED = true
    const val AGC_TARGET = 0.35f
    const val AGC_MAX_GAIN = 30f

    /**
     * Mã LED theo trạng thái — sendMsg(4096, code). Mỗi trạng thái 1 DÃY code CSV (gửi tuần tự, làm
     * hiệu ứng nhiều bước). Mặc định từ aiboxplus (501=listening, 204=speaking, 504=idle). Vì không
     * nhìn thấy đèn từ máy build -> sweep code qua panel (led_*) rồi quan sát. Hiệu ứng có sẵn aiboxplus:
     * breathing "505,309,503", lỗi "309,210". Áp dụng từ lần đổi trạng thái sau.
     */
    const val LED_IDLE = "504"
    const val LED_LISTENING = "501"
    const val LED_SPEAKING = "204"
    const val LED_MUSIC = "309"

    /**
     * Sensitivity used while the assistant is speaking / playing music. Kept LOW so the assistant's
     * own TTS voice / music doesn't echo back into the mic and false-trigger a wake — that would
     * cause an abort-resume feedback loop. Barge-in still works with a clear, loud "Alexa" (or just
     * use the hardware button, which always interrupts). Raise carefully if barge-in is too hard.
     * 2026-06-28: 0.6 -> 0.4 cho khớp việc hạ sensitivity idle.
     */
    const val WAKE_SENSITIVITY_SPEAKING = "0.4"
}
