package info.dourok.voicebot.domain.voice

import android.util.Log
import info.dourok.voicebot.OpusEncoder
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.domain.voice.model.ChatMessage
import info.dourok.voicebot.domain.voice.model.VoiceState
import info.dourok.voicebot.protocol.AbortReason
import info.dourok.voicebot.protocol.ListeningMode
import info.dourok.voicebot.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/**
 * Core voice-assistant runtime. Drives the wake -> listen -> speak loop using the injected
 * audio/wake/sound/led ports and a [Protocol] channel. Keeps the presentation layer thin.
 *
 * Behaviour:
 *  - Idle: not connected, the wake detector runs on the mic; saying "Alexa" wakes it.
 *  - Listening: mic is streamed to the server (until the server's VAD stops the turn).
 *  - Speaking (TTS / music): wake detection keeps running so "Alexa" interrupts; mic is NOT
 *    streamed (to avoid the speaker echoing back into the recognizer).
 *  - The session ends when the server closes the channel -> back to wake.
 */
class VoiceAssistant @Inject constructor(
    private val wakeWord: WakeWordDetector,
    private val capture: AudioCapture,
    private val playback: AudioPlayback,
    private val sounds: SoundEffects,
    private val led: LedIndicator,
) {
    val state = MutableStateFlow(VoiceState.IDLE)
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val emotion = MutableStateFlow("neutral")

    @Volatile private var isAwake = false
    @Volatile private var isMusic = false   // server báo play_youtube đang phát -> LED nhạc
    private var aborted = false
    private var encoder: OpusEncoder? = null
    private val agc = SttAgc()   // AGC CHỈ cho luồng STT (trước opus), không đụng wake-detect

    // After an interrupt, ignore stray buffered playback frames for a moment so they don't flip the
    // state back to SPEAKING (which would stop streaming the mic and lose the user's question).
    @Volatile private var suppressSpeakingUntil = 0L

    // While one of our own chimes (wake/stop) is physically still playing out the speaker, don't run
    // wake-word detection at all -- this device has no AEC, so the mic hears its own chime (the stop
    // chime is 2.3s, boosted 25% gain) and the model can score that as a real wake, firing playWake()
    // on top of the still-playing chime. Window = chime's real duration + echo-tail margin.
    @Volatile private var suppressWakeUntil = 0L
    private fun chimeGuard(durationMs: Long) {
        suppressWakeUntil = System.currentTimeMillis() + durationMs + 400
    }

    private lateinit var protocol: Protocol
    private lateinit var scope: CoroutineScope

    fun start(protocol: Protocol, scope: CoroutineScope) {
        this.protocol = protocol
        this.scope = scope
        scope.launch {
            protocol.start()
            // Playback is wired once; incomingAudioFlow is a shared flow that survives reconnects.
            playback.start(protocol.incomingAudioFlow) {
                if (System.currentTimeMillis() > suppressSpeakingUntil) state.value = VoiceState.SPEAKING
            }
            // Do NOT open the channel yet -> connect only on wake (avoids idle timeout).
            launch { runAudioLoop() }
            launch { protocol.incomingJsonFlow.collect(::handleServerMessage) }
            launch { TextCommands.flow.collect { onTextCommand(it) } }
            launch { state.collect { refreshLed() } }   // LED bám theo trạng thái
        }
    }

    /** Cập nhật LED theo (awake, music, state). LISTENING ưu tiên hơn MUSIC (đang hỏi xen nhạc). */
    private fun refreshLed() {
        led.setState(when {
            !isAwake -> LedState.IDLE
            state.value == VoiceState.LISTENING -> LedState.LISTENING
            isMusic -> LedState.MUSIC
            state.value == VoiceState.SPEAKING -> LedState.SPEAKING
            else -> LedState.IDLE
        })
    }

    /** Typed query from the control-panel chat: connect (if needed) + send it as a user turn. */
    private suspend fun onTextCommand(text: String) {
        if (text.isBlank()) return
        ConversationLog.add("user", text)
        if (!protocol.isAudioChannelOpened()) protocol.openAudioChannel()
        isAwake = true
        protocol.sendTextQuery(text)
        state.value = VoiceState.SPEAKING
    }

    private suspend fun runAudioLoop() {
        encoder = OpusEncoder(16000, 1, 60)
        capture.start().collect { pcm ->
            try {
                // Test mic từ control panel: bơm frame THÔ (trước AGC) ra buffer để nghe lại.
                if (MicTest.recording) MicTest.feed(pcm, pcm.size)

                // Server closed the channel (e.g. "goodbye" / no-voice timeout) -> back to wake.
                if (isAwake && !protocol.isAudioChannelOpened()) backToWake()

                if (isAwake && state.value == VoiceState.LISTENING) {
                    if (Settings.agcEnabled) agc.process(pcm, pcm.size)  // kéo giọng xa lên TRƯỚC opus
                    encoder?.encode(pcm)?.let { protocol.sendAudio(it) }
                } else if (wakeWord.isReady && System.currentTimeMillis() >= suppressWakeUntil) {
                    // Wait-for-wake (idle) OR interrupt-while-speaking. Use a stricter threshold
                    // while speaking so the speaker output doesn't false-trigger; AEC keeps a real
                    // "Alexa" audible.
                    wakeWord.setStrict(state.value == VoiceState.SPEAKING)
                    if (wakeWord.process(pcm)) onWake()
                }
            } catch (e: Exception) {
                Log.e(TAG, "audio loop error (frame skipped): ${e.message}")
            }
        }
    }

    private suspend fun onWake() {
        Log.i(TAG, ">>> wake word detected")
        chimeGuard(sounds.playWake())
        if (isAwake) {
            // Speaking / music -> interrupt: flush buffered audio + suppress the SPEAKING override so
            // the mic keeps streaming and the user's next command is actually captured.
            interruptPlayback()
        } else {
            isAwake = true
            if (!protocol.isAudioChannelOpened()) protocol.openAudioChannel()
        }
        protocol.sendStartListening(ListeningMode.AUTO_STOP)
        startAgc()
        state.value = VoiceState.LISTENING
        wakeWord.reset()
    }

    /** Nạp tham số AGC (chỉnh live qua panel) + reset envelope cho phiên nghe mới. */
    private fun startAgc() {
        agc.target = Settings.agcTarget
        agc.maxGain = Settings.agcMaxGain
        agc.reset()
    }

    /** Interrupt current playback: drop buffered audio + tell the server to stop. */
    private suspend fun interruptPlayback() {
        suppressSpeakingUntil = System.currentTimeMillis() + 1500
        playback.flush()
        protocol.sendAbortSpeaking(AbortReason.NONE)
        aborted = true
    }

    private fun backToWake() {
        Log.i(TAG, "channel closed -> waiting for wake")
        isAwake = false
        isMusic = false
        chimeGuard(sounds.playStop())   // chuông kết thúc phiên (timeout / tạm biệt) — server không gọi AI chào nữa
        state.value = VoiceState.IDLE
        wakeWord.reset()
    }

    private fun handleServerMessage(json: JSONObject) {
        when (json.optString("type")) {
            "tts" -> when (json.optString("state")) {
                "start" -> {
                    aborted = false
                    if (state.value != VoiceState.LISTENING || state.value == VoiceState.IDLE) {
                        state.value = VoiceState.SPEAKING
                    }
                }
                "stop" -> scope.launch {
                    if (state.value == VoiceState.SPEAKING) {
                        playback.awaitCompletion()
                        // Mở nghe lại (hội thoại liên tục). KHÔNG phát âm ở đây — tts-stop xảy ra cả sau
                        // thinking-filler nên sẽ chồng với audio câu trả lời thật. Start-sound chỉ ở wake.
                        protocol.sendStartListening(ListeningMode.AUTO_STOP)
                        state.value = VoiceState.LISTENING
                    }
                }
                "sentence_start" -> json.optString("text").takeIf { it.isNotEmpty() }?.let { addMessage("assistant", it) }
            }
            "stt" -> json.optString("text").takeIf { it.isNotEmpty() }?.let { addMessage("user", it) }
            "llm" -> json.optString("emotion").takeIf { it.isNotEmpty() }?.let { emotion.value = it }
            "music" -> { isMusic = json.optString("state") == "start"; refreshLed() }  // play_youtube -> LED nhạc
        }
    }

    /** Hardware button: idle -> wake; speaking -> interrupt + listen; listening -> stop. */
    fun onButtonPress() {
        scope.launch {
            when {
                !isAwake -> {
                    chimeGuard(sounds.playWake())
                    if (!protocol.isAudioChannelOpened()) protocol.openAudioChannel()
                    protocol.sendStartListening(ListeningMode.AUTO_STOP)
                    isAwake = true
                    startAgc()
                    state.value = VoiceState.LISTENING
                }
                state.value == VoiceState.SPEAKING -> {
                    interruptPlayback()
                    chimeGuard(sounds.playWake())
                    protocol.sendStartListening(ListeningMode.AUTO_STOP)
                    startAgc()
                    state.value = VoiceState.LISTENING
                }
                else -> {
                    chimeGuard(sounds.playStop())
                    isAwake = false
                    isMusic = false
                    protocol.closeAudioChannel()
                    state.value = VoiceState.IDLE
                    // Matches backToWake()'s reset -- without it, a wake detector with real
                    // cross-call state (e.g. MaiOiWakeWordDetector's frame accumulator + native
                    // frontend buffers) can carry stale partial state into the next listening
                    // session and misfire immediately on resume.
                    wakeWord.reset()
                }
            }
        }
    }

    private fun addMessage(sender: String, text: String) {
        if (text.trimStart().startsWith("%")) return  // skip server tool-call markers ("% get_weather")
        messages.value = messages.value + ChatMessage(sender, text)
        ConversationLog.add(sender, text)  // for the web control panel's chat view
    }

    fun dispose() {
        try {
            protocol.dispose()
            encoder?.release()
            playback.release()
            capture.stop()
        } catch (e: Exception) {
            Log.e(TAG, "dispose error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "VoiceAssistant"
    }
}
