package info.dourok.voicebot.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject

// Enums and constants for the Protocol base class.
enum class AbortReason { WAKE_WORD_DETECTED, NONE }
enum class ListeningMode { ALWAYS_ON, AUTO_STOP, MANUAL }
enum class AudioState { OPENED, CLOSED }

// Protocol abstract base (mirrors the C++ Protocol).
abstract class Protocol {
    protected var sessionId: String = "" // session ID
    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Callbacks are exposed as Flow.
    val incomingJsonFlow = MutableSharedFlow<JSONObject>()
    val incomingAudioFlow = MutableSharedFlow<ByteArray>()
    val audioChannelStateFlow = MutableSharedFlow<AudioState>()
    val networkErrorFlow = MutableSharedFlow<String>()

    abstract suspend fun start()
    abstract suspend fun sendAudio(data: ByteArray)
    abstract suspend fun openAudioChannel(): Boolean
    abstract fun closeAudioChannel()
    abstract fun isAudioChannelOpened(): Boolean
    abstract suspend fun sendText(text: String)

    suspend fun sendAbortSpeaking(reason: AbortReason) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "abort")
            if (reason == AbortReason.WAKE_WORD_DETECTED) put("reason", "wake_word_detected")
        }
        sendText(json.toString())
    }

    suspend fun sendWakeWordDetected(wakeWord: String) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "detect")
            put("text", wakeWord)
        }
        sendText(json.toString())
    }

    /** Inject a typed query (from the control panel chat) as if the user had spoken it. */
    suspend fun sendTextQuery(text: String) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "detect")
            put("mode", "manual")
            put("text", text)
        }
        sendText(json.toString())
    }

    suspend fun sendStartListening(mode: ListeningMode) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "start")
            put("mode", when (mode) {
                ListeningMode.ALWAYS_ON -> "realtime"
                ListeningMode.AUTO_STOP -> "auto"
                ListeningMode.MANUAL -> "manual"
            })
        }
        sendText(json.toString())
    }

    suspend fun sendStopListening() {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "stop")
        }
        sendText(json.toString())
    }

    suspend fun sendIotDescriptors(descriptors: String) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "iot")
            put("descriptors", JSONObject(descriptors))
        }
        sendText(json.toString())
    }

    suspend fun sendIotStates(states: String) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "iot")
            put("states", JSONObject(states))
        }
        sendText(json.toString())
    }

    abstract fun dispose()
}

