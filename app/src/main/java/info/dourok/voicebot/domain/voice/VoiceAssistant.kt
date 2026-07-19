package info.dourok.voicebot.domain.voice

import android.util.Log
import info.dourok.voicebot.OpusEncoder
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.domain.voice.model.ChatMessage
import info.dourok.voicebot.domain.voice.model.VoiceState
import info.dourok.voicebot.protocol.AbortReason
import info.dourok.voicebot.protocol.AudioState
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

    // The R1 has a 4-mic array in hardware, but that array's beamforming + echo cancellation lived in
    // PHICOMM's vendor DSP firmware. This app reads the raw Android mic (VOICE_RECOGNITION source, one
    // downmixed channel, no working AEC against our own speaker), so it cannot stream the mic to the
    // server while its own speaker is emitting TTS, or the STT transcribes the robot's own voice (it
    // then answers itself, looping). The SPEAKING state approximates this, but leaks: on `tts start`
    // the state stays LISTENING (willSet=false), and it only flips on the first playback chunk -- so
    // the gap before the first chunk, and every between-segment LISTENING window (e.g. after the
    // thinking-filler's `tts stop`), streams our own audio. This is the authoritative guard, keyed on
    // real speaker output: bump it on every played chunk, and refuse to stream the mic until it lapses.
    @Volatile private var micGateUntil = 0L
    private val MIC_GATE_TAIL_MS = 350L  // reverb/echo tail after the last played chunk

    // While one of our own chimes (wake/stop) is physically still playing out the speaker, don't run
    // wake-word detection at all -- this device has no AEC, so the mic hears its own chime (the stop
    // chime is 2.3s, boosted 25% gain) and the model can score that as a real wake. The "Mai ơi"
    // model is small and can't reliably reject the stop chime acoustically (it scores ~0.98 even
    // after retraining against it -- see robot-esp32 wakeword_training), so gating it out is the
    // deterministic fix, not the model. Window = chime's real duration + echo-tail margin.
    //
    // The margin differs by chime. The WAKE chime is followed immediately by LISTENING (wake
    // detection is off while listening), so it only needs a short margin. The STOP chime returns us
    // to IDLE with wake detection back ON, so its reverb/decay tail can self-trigger the session-end
    // re-wake loop the user hit -- and being deaf for a few extra seconds right after a session ends
    // is harmless (the user just finished). So the stop chime gets a much larger margin.
    @Volatile private var suppressWakeUntil = 0L
    private val WAKE_CHIME_MARGIN_MS = 1200L
    private val STOP_CHIME_MARGIN_MS = 3500L

    // Blast-radius cap for false wakes. A stray wake (e.g. on TV) otherwise becomes an endless
    // listen<->reply loop: the server keeps auto-reopening the mic and ambient TV speech never lets
    // the session idle out, so the robot ends up "chatting with the TV". Bound how many back-to-back
    // auto-listen turns a single wake can spawn; after the cap it sleeps and waits for a fresh wake.
    // A real hands-free conversation just re-says "Mai ơi" to keep going.
    // Blast-radius backstop: robot replies since the user last actually spoke (reset on real STT).
    // A live conversation resets this every turn, so it only trips on a genuine runaway (many replies
    // with zero user speech). Set above the max TTS segments one long answer can emit (filler + a
    // multi-segment answer) so a single reply can never trip it.
    @Volatile private var autoTurns = 0
    private val MAX_AUTO_TURNS = 8
    private fun chimeGuard(durationMs: Long, marginMs: Long = WAKE_CHIME_MARGIN_MS) {
        val until = System.currentTimeMillis() + durationMs + marginMs
        suppressWakeUntil = until
        Log.i(TAG, "chimeGuard: duration=$durationMs margin=$marginMs until=$until")
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
                val now = System.currentTimeMillis()
                if (state.value != VoiceState.SPEAKING) {
                    Log.i(TAG, "playback audio chunk (not SPEAKING yet): now=$now suppressSpeakingUntil=$suppressSpeakingUntil willSetSpeaking=${now > suppressSpeakingUntil}")
                }
                // Only guard the mic when we're NOT in a post-interrupt suppression window: there we
                // deliberately keep streaming so the user's barge-in command is captured.
                if (now > suppressSpeakingUntil) {
                    micGateUntil = now + MIC_GATE_TAIL_MS  // speaker is emitting -> mute mic stream
                    state.value = VoiceState.SPEAKING
                }
            }
            // Do NOT open the channel yet -> connect only on wake (avoids idle timeout).
            launch { runAudioLoop() }
            launch { protocol.incomingJsonFlow.collect(::handleServerMessage) }
            launch { TextCommands.flow.collect { onTextCommand(it) } }
            launch { MediaCommands.flow.collect { onMediaCommand(it) } }
            // Media state is server-pushed only, so a dropped channel would freeze the last
            // snapshot on the control panel (a song stuck "playing" with silence). Clear it.
            launch {
                protocol.audioChannelStateFlow.collect {
                    if (it == AudioState.CLOSED) MediaSessionState.clear()
                }
            }
            launch { state.collect { refreshLed() } }   // LED bám theo trạng thái
            launch { state.collect { Log.i(TAG, "state -> $it isAwake=$isAwake t=${System.currentTimeMillis()}") } }
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
        autoTurns = 0
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
                    // No-AEC single mic: never stream while our own speaker is (still) emitting, or the
                    // STT transcribes the robot's own TTS as a user turn (see micGateUntil).
                    if (System.currentTimeMillis() < micGateUntil) {
                        // robot audio still audible -> drop this frame instead of feeding it back
                    } else {
                        if (Settings.agcEnabled) agc.process(pcm, pcm.size)  // kéo giọng xa lên TRƯỚC opus
                        encoder?.encode(pcm)?.let { protocol.sendAudio(it) }
                    }
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
        Log.i(TAG, ">>> wake word detected: isAwake=$isAwake state=${state.value} t=${System.currentTimeMillis()}")
        autoTurns = 0
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
        Log.i(TAG, "interruptPlayback: flushed local buffer + sent abort t=${System.currentTimeMillis()}")
    }

    private fun backToWake() {
        Log.i(TAG, "channel closed -> waiting for wake")
        isAwake = false
        isMusic = false
        chimeGuard(sounds.playStop(), STOP_CHIME_MARGIN_MS)   // chuông kết thúc phiên (timeout / tạm biệt) — server không gọi AI chào nữa
        state.value = VoiceState.IDLE
        wakeWord.reset()
    }

    private fun handleServerMessage(json: JSONObject) {
        when (json.optString("type")) {
            "tts" -> when (json.optString("state")) {
                "start" -> {
                    aborted = false
                    val willSet = state.value != VoiceState.LISTENING || state.value == VoiceState.IDLE
                    Log.i(TAG, "tts start: state=${state.value} willSetSpeaking=$willSet t=${System.currentTimeMillis()}")
                    if (willSet) {
                        state.value = VoiceState.SPEAKING
                    }
                }
                "stop" -> scope.launch {
                    Log.i(TAG, "tts stop: state=${state.value} t=${System.currentTimeMillis()}")
                    if (state.value == VoiceState.SPEAKING) {
                        playback.awaitCompletion()
                        if (++autoTurns > MAX_AUTO_TURNS) {
                            // Many replies with no real user speech in between (STT resets this) ->
                            // almost certainly a false wake feeding on ambient. Sleep, don't re-listen.
                            Log.i(TAG, "auto-listen turn cap ($MAX_AUTO_TURNS) reached -> sleep")
                            backToWake()               // isAwake=false first, so the audio loop won't double-fire
                            protocol.closeAudioChannel()
                        } else {
                            // Mở nghe lại (hội thoại liên tục). KHÔNG phát âm ở đây — tts-stop xảy ra cả sau
                            // thinking-filler nên sẽ chồng với audio câu trả lời thật. Start-sound chỉ ở wake.
                            protocol.sendStartListening(ListeningMode.AUTO_STOP)
                            state.value = VoiceState.LISTENING
                        }
                    }
                }
                "sentence_start" -> json.optString("text").takeIf { it.isNotEmpty() }?.let { addMessage("assistant", it) }
            }
            "stt" -> json.optString("text").takeIf { it.isNotEmpty() }?.let {
                Log.i(TAG, "stt: text=$it t=${System.currentTimeMillis()}")
                // A real user utterance means the conversation is alive -> reset the auto-turn cap.
                // Without this the cap counts TTS segments (filler + answer + each answer segment all
                // fire 'tts stop'), not conversation turns, and ends the session mid-chat after ~2 turns.
                autoTurns = 0
                addMessage("user", it)
            }
            "llm" -> json.optString("emotion").takeIf { it.isNotEmpty() }?.let { emotion.value = it }
            "media_queue" -> {
                val items = json.optJSONArray("items")
                val list = mutableListOf<MediaQueueItem>()
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val it = items.optJSONObject(i) ?: continue
                        list.add(MediaQueueItem(
                            videoId = it.optString("video_id", ""),
                            title = it.optString("title", ""),
                            artist = it.optString("artist", ""),
                            thumbnail = it.optString("thumbnail", ""),
                            duration = it.optString("duration", ""),
                        ))
                    }
                }
                MediaSessionState.updateQueue(list)
            }
            "media_now_playing" -> {
                val playbackState = when (json.optString("state")) {
                    "downloading" -> MediaPlaybackState.DOWNLOADING
                    "playing" -> MediaPlaybackState.PLAYING
                    else -> MediaPlaybackState.STOPPED
                }
                isMusic = playbackState == MediaPlaybackState.DOWNLOADING || playbackState == MediaPlaybackState.PLAYING
                refreshLed()  // play_youtube (voice OR web-triggered) -> LED nhạc
                val vid = json.optString("video_id", "")
                MediaSessionState.updateNowPlaying(MediaNowPlaying(
                    state = playbackState,
                    videoId = vid.ifEmpty { null },
                    title = json.optString("title", ""),
                    artist = json.optString("artist", ""),
                    thumbnail = json.optString("thumbnail", ""),
                    durationS = json.optInt("duration_s", 0),
                    positionS = json.optInt("position_s", 0),
                ))
            }
        }
    }

    /**
     * Media Player tab (web control panel) command, arriving via [MediaCommands] (see that file for
     * why ControlServer can't call this instance directly).
     *
     * Play opens the audio channel if needed and marks the session awake, exactly like
     * [onTextCommand] -- otherwise pressing Play while the robot is idle would send into a closed
     * channel and do nothing. The others only make sense against an already-running session, so
     * they're dropped when the channel is closed.
     */
    private suspend fun onMediaCommand(cmd: MediaCommands.Command) {
        Log.i(TAG, "media command: $cmd t=${System.currentTimeMillis()}")
        if (cmd is MediaCommands.Command.Play) {
            if (!protocol.isAudioChannelOpened()) protocol.openAudioChannel()
            isAwake = true
            autoTurns = 0
            protocol.sendMediaPlay(cmd.itemsJson, cmd.startIndex)
            return
        }
        if (!protocol.isAudioChannelOpened()) {
            Log.i(TAG, "media command dropped (channel closed): $cmd")
            return
        }
        when (cmd) {
            is MediaCommands.Command.Next -> protocol.sendMediaNext()
            is MediaCommands.Command.Pause -> protocol.sendMediaPause()
            is MediaCommands.Command.Resume -> protocol.sendMediaResume()
            is MediaCommands.Command.Stop -> protocol.sendMediaStop()
            is MediaCommands.Command.Play -> {}  // handled above
        }
    }

    /** Hardware button: idle -> wake; awake (listening OR speaking) -> sleep. */
    fun onButtonPress() {
        Log.i(TAG, "onButtonPress: isAwake=$isAwake state=${state.value} t=${System.currentTimeMillis()}")
        scope.launch {
            autoTurns = 0  // explicit user intent -> reset the false-wake blast-radius cap
            if (!isAwake) {
                chimeGuard(sounds.playWake())
                if (!protocol.isAudioChannelOpened()) protocol.openAudioChannel()
                protocol.sendStartListening(ListeningMode.AUTO_STOP)
                isAwake = true
                startAgc()
                state.value = VoiceState.LISTENING
            } else {
                // Awake (LISTENING or SPEAKING) -> always SLEEP. The button is a reliable "turn off".
                // Previously a press while SPEAKING interrupted-and-kept-listening (barge-in), so
                // during a conversation loop (e.g. with the TV) the press rarely landed on LISTENING
                // and couldn't put it to sleep. Stop playback, close the channel, go idle.
                playback.flush()
                chimeGuard(sounds.playStop(), STOP_CHIME_MARGIN_MS)
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
