package info.dourok.voicebot.protocol
import android.util.Log
import info.dourok.voicebot.data.model.DeviceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// WebsocketProtocol implementation
class WebsocketProtocol(private val deviceInfo: DeviceInfo,
                        private val url: String,
                        private val accessToken: String) : Protocol() {
    companion object {
        private const val TAG = "WS"
        private const val OPUS_FRAME_DURATION_MS = 60
    }

    private var isOpen: Boolean = false
    private var websocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // MUST be reset on every openAudioChannel (reconnect); otherwise a reconnect's await returns
    // immediately (already completed the first time) -> sendStartListening fires before the server
    // is ready -> the first turn is lost.
    private var helloReceived = CompletableDeferred<Boolean>()

    init {
        sessionId = "your_session_id"
    }

    override suspend fun start() {
        // No-op, matches the C++ reference implementation.
    }

    override suspend fun sendAudio(data: ByteArray) {
        // Log.i(TAG, "Sending audio: ${data.size}")
        websocket?.run {
            send(ByteString.of(*data))
        } ?: Log.e(TAG, "WebSocket is null")
    }

    override suspend fun sendText(text: String) {
        Log.i(TAG, "Sending text: $text")
        websocket?.run {
            send(text)
        } ?: Log.e(TAG, "WebSocket is null")
    }

    override fun isAudioChannelOpened(): Boolean {
        return websocket != null && isOpen
    }

    override fun closeAudioChannel() {
        websocket?.close(1000, "Normal closure")
        websocket = null
    }

    override suspend fun openAudioChannel(): Boolean = withContext(Dispatchers.IO) {
        // Close any previous connection.
        closeAudioChannel()
        helloReceived = CompletableDeferred()  // reset -> await waits for the NEW connection's hello

        // Build the WebSocket request.
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Protocol-Version", "1")
            .addHeader("Device-Id", deviceInfo.mac_address) //
            .addHeader("Client-Id", deviceInfo.uuid) //
            .build()
        Log.i(TAG, "WebSocket connecting to $url")
        // Log header
        request.headers.forEach { (name, value) ->
            Log.i(TAG, "Header: $name: $value")
        }

        // Open the WebSocket.
        websocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isOpen = true
                Log.i(TAG, "WebSocket connected")
                scope.launch {
                    audioChannelStateFlow.emit(AudioState.OPENED)
                }

                // Send the Hello handshake.
                val helloMessage = JSONObject().apply {
                    put("type", "hello")
                    put("version", 1)
                    put("transport", "websocket")
                    put("audio_params", JSONObject().apply {
                        put("format", "opus")
                        put("sample_rate", 16000)
                        put("channels", 1)
                        put("frame_duration", OPUS_FRAME_DURATION_MS)
                    })
                    // Per-session BYO LLM: send the client-configured provider so the server builds
                    // a session LLM from it. Only when a provider is actually configured.
                    val base = info.dourok.voicebot.data.Settings.llmBaseUrl
                    val model = info.dourok.voicebot.data.Settings.llmModel
                    if (base.isNotBlank() && model.isNotBlank()) {
                        put("llm_config", JSONObject().apply {
                            put("type", info.dourok.voicebot.data.Settings.llmTransport)
                            put("base_url", base)
                            put("model_name", model)
                            put("api_key", info.dourok.voicebot.data.Settings.llmApiKey)
                        })
                    }
                    // Per-session BYO Home Assistant: send the client-configured HA + device list so
                    // the server injects it into the prompt + hass_* tools use it. Only when configured.
                    val haUrl = info.dourok.voicebot.data.Settings.haUrl
                    val haDevices = info.dourok.voicebot.data.Settings.haDevices
                    if (haUrl.isNotBlank() && haDevices.isNotBlank()) {
                        put("ha_config", JSONObject().apply {
                            put("base_url", haUrl)
                            put("token", info.dourok.voicebot.data.Settings.haToken)
                            put("devices", haDevices)
                        })
                    }
                    // Per-session BYO persona: send the client-configured system-prompt override so
                    // the server substitutes it for its own prompt: for this session. Blank = server default.
                    val customPrompt = info.dourok.voicebot.data.Settings.customPrompt
                    if (customPrompt.isNotBlank()) {
                        put("custom_prompt", customPrompt)
                    }
                }
                Log.i(TAG, "WebSocket hello: $helloMessage")
                webSocket.send(helloMessage.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.i(TAG, "WebSocket message: $text")
                scope.launch {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    when (type) {
                        "hello" -> parseServerHello(json)
                        else -> incomingJsonFlow.emit(json)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Log.i(TAG, "WebSocket binary message: ${bytes.size}")
                scope.launch {
                    incomingAudioFlow.emit(bytes.toByteArray())
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code: $reason")
                super.onClosing(webSocket, code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isOpen = false
                Log.i(TAG, "WebSocket closed: $code: $reason")
                scope.launch {
                    audioChannelStateFlow.emit(AudioState.CLOSED)
                }
                websocket = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isOpen = false
                t.printStackTrace()
                Log.e(TAG, "WebSocket error: ${t.message}")
                scope.launch {
                    networkErrorFlow.emit("Server not found")
                }
                websocket = null
            }
        })
        // Keep the client alive after the connection opens.
        // client.dispatcher.executorService.shutdown()

        // Wait for the server Hello (mirrors the C++ xEventGroupWaitBits).
        try {
            withTimeout(10000) {
                Log.i(TAG, "Waiting for server hello")
                helloReceived.await()
                Log.i(TAG, "Server hello received")
                true
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Failed to receive server hello")
            networkErrorFlow.emit("Server timeout")
            closeAudioChannel()
            false
        }
    }

    private fun parseServerHello(root: JSONObject) {
        val transport = root.optString("transport")
        if (transport != "websocket") {
            Log.e(TAG, "Unsupported transport: $transport")
            return
        }

        val audioParams = root.optJSONObject("audio_params")
        audioParams?.let {
            val sampleRate = it.optInt("sample_rate", -1)
            if (sampleRate != -1) {
                serverSampleRate = sampleRate
            }
        }
        sessionId = root.optString("session_id")


        helloReceived.complete(true)
    }

    // Release resources.
    override fun dispose() {
        scope.cancel()
        closeAudioChannel()
        client.dispatcher.executorService.shutdown()
    }

    private var serverSampleRate: Int = -1
}