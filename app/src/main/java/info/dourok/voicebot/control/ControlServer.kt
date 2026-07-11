package info.dourok.voicebot.control

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.data.maskApiKey
import info.dourok.voicebot.domain.voice.AudioPlayback
import info.dourok.voicebot.domain.voice.ConversationLog
import info.dourok.voicebot.domain.voice.LedIndicator
import info.dourok.voicebot.domain.voice.LedState
import info.dourok.voicebot.domain.voice.MicTest
import info.dourok.voicebot.domain.voice.TextCommands
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device web control panel (like aiboxplus's control center). Serves a single page + a small
 * JSON API on [PORT] so a phone/PC on the same wifi can tweak EQ / volume / wake sensitivity / mic
 * gain, view the chat transcript and restart the app — without rebuilding.
 *   http://<r1-ip>:8088
 */
@Singleton
class ControlServer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playback: AudioPlayback,
    private val led: LedIndicator,
) : NanoHTTPD(PORT) {

    fun startServer() {
        try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "control panel running on :$PORT")
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
        }
    }

    override fun serve(session: IHTTPSession): Response = try {
        when (session.uri) {
            "/", "/index.html" -> serveAsset()
            "/api/state" -> json(buildState())
            "/api/set" -> {
                val ok = handleSet(session)
                json(if (ok) """{"ok":true}""" else """{"ok":false,"error":"missing key or value"}""")
            }
            "/api/say" -> {
                session.parameters["text"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { TextCommands.flow.tryEmit(it.trim()) }
                json("""{"ok":true}""")
            }
            "/api/led" -> {
                session.parameters["state"]?.firstOrNull()?.let { previewLed(it) }
                json("""{"ok":true}""")
            }
            "/api/restart" -> json("""{"ok":true}""").also { scheduleRestart() }
            "/api/mic/start" -> { MicTest.start(session.parameters["agc"]?.firstOrNull() == "1"); json("""{"ok":true}""") }
            "/api/mic/stop" -> { MicTest.stop(); json("""{"ok":true,"bytes":${MicTest.sizeBytes()}}""") }
            "/api/mic/rec.wav" -> serveWav()
            "/api/setup/llm" -> { handleSet(session); json("""{"ok":true}""") }  // legacy alias, unused by control.html
            "/api/setup/wake" -> {
                session.parameters["engine"]?.firstOrNull()?.let { Settings.wakeEngine = it }
                json("""{"ok":true}""")
            }
            "/api/setup/server" -> json(handleSetupServer(session))
            "/api/llm/models" -> json(handleLlmModels(session))
            "/api/llm/test" -> json(handleLlmTest(session))
            "/api/ha/devices" -> json(handleHaDevices(session))
            "/api/ha/test" -> json(handleHaTest(session))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        }
    } catch (e: Exception) {
        Log.e(TAG, "serve ${session.uri}: ${e.message}")
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
    }

    /**
     * Returns false when key/value is missing so the caller can report a real failure instead of
     * the blind {"ok":true} this used to send regardless of outcome.
     *
     * Long values (e.g. the Assistant persona, which can run several KB) are sent in the POST body
     * instead of the query string — a query string carrying a whole system prompt can exceed the
     * request-line/URL length ceiling and silently truncate, while still returning 200. A caller
     * using body-based transport (no `value` in the query string, only `key`) is identified by that
     * absence: for such a POST, an empty/zero-length body legitimately means "save empty" (e.g.
     * clearing the Assistant persona back to the server default), not "value missing" — so it
     * defaults to "" rather than failing. Callers still using the query-string `value` param (the
     * short-field `set()` JS helper) are unaffected.
     */
    private fun handleSet(session: IHTTPSession): Boolean {
        val key = session.parameters["key"]?.firstOrNull() ?: return false
        val queryValue = session.parameters["value"]?.firstOrNull()
        val v: String = if (queryValue != null) {
            queryValue
        } else if (session.method == NanoHTTPD.Method.POST) {
            val body = HashMap<String, String>()
            try {
                session.parseBody(body)
            } catch (e: Exception) {
                Log.e(TAG, "parseBody for /api/set: ${e.message}")
            }
            body["postData"] ?: ""
        } else {
            return false
        }
        when (key) {
            "wake_sensitivity" -> Settings.wakeSensitivity = v
            "wake_sensitivity_speaking" -> Settings.wakeSensitivitySpeaking = v
            "mic_gain" -> v.toFloatOrNull()?.let { Settings.micGain = it }
            "mic_source" -> v.toIntOrNull()?.let { Settings.micSource = it }
            "agc_enabled" -> Settings.agcEnabled = v == "true"
            "agc_target" -> v.toFloatOrNull()?.let { Settings.agcTarget = it }
            "agc_max_gain" -> v.toFloatOrNull()?.let { Settings.agcMaxGain = it }
            "led_idle" -> Settings.ledIdle = v
            "led_listening" -> Settings.ledListening = v
            "led_speaking" -> Settings.ledSpeaking = v
            "led_music" -> Settings.ledMusic = v
            "playback_sr" -> v.toIntOrNull()?.let { Settings.playbackSampleRate = it }
            "eq_enabled" -> { Settings.eqEnabled = v == "true"; playback.applyEq() }
            "eq_bands" -> {
                Settings.eqBands = v.split(",").mapNotNull { it.trim().toIntOrNull() }.toIntArray()
                playback.applyEq()
            }
            "volume" -> v.toIntOrNull()?.let { setVolume(it) }
            "llm_provider" -> Settings.llmProvider = v
            "llm_base_url" -> Settings.llmBaseUrl = v
            "llm_api_key" -> Settings.llmApiKey = v
            "llm_model" -> Settings.llmModel = v
            "llm_transport" -> Settings.llmTransport = v
            "wake_engine" -> Settings.wakeEngine = v
            "ota_url" -> Settings.otaUrl = v
            "ha_url" -> Settings.haUrl = v
            "ha_token" -> Settings.haToken = v
            "ha_devices" -> Settings.haDevices = v
            "custom_prompt" -> Settings.customPrompt = v
        }
        return true
    }

    private fun previewLed(name: String) {
        led.preview(when (name) {
            "listening" -> LedState.LISTENING
            "speaking" -> LedState.SPEAKING
            "music" -> LedState.MUSIC
            else -> LedState.IDLE
        })
    }

    private fun setVolume(percent: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (percent * max / 100).coerceIn(0, max), 0)
        Settings.volume = percent
    }

    private fun buildState(): String {
        val o = JSONObject()
        o.put("wake_sensitivity", Settings.wakeSensitivity)
        o.put("wake_sensitivity_speaking", Settings.wakeSensitivitySpeaking)
        o.put("mic_gain", Settings.micGain.toDouble())
        o.put("mic_source", Settings.micSource)
        o.put("agc_enabled", Settings.agcEnabled)
        o.put("agc_target", Settings.agcTarget.toDouble())
        o.put("agc_max_gain", Settings.agcMaxGain.toDouble())
        o.put("led_idle", Settings.ledIdle)
        o.put("led_listening", Settings.ledListening)
        o.put("led_speaking", Settings.ledSpeaking)
        o.put("led_music", Settings.ledMusic)
        o.put("playback_sr", Settings.playbackSampleRate)
        o.put("eq_enabled", Settings.eqEnabled)
        o.put("eq_bands", JSONArray(Settings.eqBands.toList()))
        o.put("mic_recording", MicTest.recording)   // để UI biết bản ghi 30s tự dừng

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        o.put("volume", if (max > 0) cur * 100 / max else 0)

        playback.eqInfo()?.let { eq ->
            o.put("eq", JSONObject().apply {
                put("freqs", JSONArray(eq.freqsHz.toList()))
                put("min", eq.minMb)
                put("max", eq.maxMb)
            })
        }

        val chat = JSONArray()
        ConversationLog.recent().forEach {
            chat.put(JSONObject().put("sender", it.sender).put("text", it.text).put("time", it.time))
        }
        o.put("chat", chat)

        // ── Generic client config (Setup tab). The API key is NEVER returned raw — masked only. ──
        o.put("llm_provider", Settings.llmProvider)
        o.put("llm_base_url", Settings.llmBaseUrl)
        o.put("llm_api_key_masked", maskApiKey(Settings.llmApiKey))
        o.put("llm_api_key_set", Settings.llmApiKey.isNotEmpty())
        o.put("llm_model", Settings.llmModel)
        o.put("llm_transport", Settings.llmTransport)
        o.put("wake_engine", Settings.wakeEngine)
        o.put("ota_url", Settings.otaUrl)
        o.put("ws_url", Settings.wsUrl)
        // TTS host derived from the configured WS host (de-hardcode); empty until a server is set.
        o.put("tts_host", ttsHostFromWs(Settings.wsUrl))
        // Home Assistant — token NEVER returned raw, masked only.
        o.put("ha_url", Settings.haUrl)
        o.put("ha_token_masked", maskApiKey(Settings.haToken))
        o.put("ha_token_set", Settings.haToken.isNotEmpty())
        o.put("ha_devices", Settings.haDevices)
        // Assistant persona override — plain text, no secret to mask.
        o.put("custom_prompt", Settings.customPrompt)
        return o.toString()
    }

    // ── Generic client config helpers ──────────────────────────────────────
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun ttsHostFromWs(wsUrl: String): String {
        if (wsUrl.isBlank()) return ""
        return try {
            val u = java.net.URI(wsUrl)
            "http://${u.host}:8002"
        } catch (e: Exception) { "" }
    }

    private fun param(session: IHTTPSession, name: String): String =
        session.parameters[name]?.firstOrNull().orEmpty()

    private fun deviceMac(): String =
        android.provider.Settings.Secure.getString(context.contentResolver, "android_id") ?: "r1-client"

    /**
     * Provision the WS endpoint from an OTA URL. Flexible about input: the trailing slash is
     * optional (normalized for the request, since the xiaozhi OTA route is `/xiaozhi/ota/`), and if
     * the OTA response is missing/non-JSON/errored we still derive `ws://<host>:8000/xiaozhi/v1/`
     * from the host so Connect always yields a usable WS url. Uses the OTA-provided url+token when
     * present (e.g. servers not on the MQTT-gateway path).
     */
    private fun handleSetupServer(session: IHTTPSession): String {
        val ota = param(session, "ota").ifBlank { Settings.otaUrl }.trim()
        if (ota.isBlank()) return """{"ok":false,"error":"no ota url"}"""
        Settings.otaUrl = ota
        val host = try { java.net.URI(ota).host } catch (e: Exception) { null }
            ?: return """{"ok":false,"error":"bad ota url (expect http://host:port/xiaozhi/ota/)"}"""
        val otaReq = if (ota.endsWith("/")) ota else "$ota/"   // trailing slash optional for the user
        val derived = "ws://$host:8000/xiaozhi/v1/"
        val fromOta: Pair<String, String>? = try {
            val body = JSONObject().put("mac_address", deviceMac()).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(otaReq).post(body)
                .header("device-id", deviceMac()).header("client-id", deviceMac()).build()
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                val ws = runCatching { JSONObject(txt).optJSONObject("websocket") }.getOrNull()
                if (ws != null) Pair(ws.getString("url"), ws.optString("token", "")) else null
            }
        } catch (e: Exception) { null }
        return if (fromOta != null) {
            Settings.wsUrl = fromOta.first
            Settings.wsToken = fromOta.second
            """{"ok":true,"ws_url":${JSONObject.quote(Settings.wsUrl)},"src":"ota"}"""
        } else {
            Settings.wsUrl = derived
            Settings.wsToken = ""
            """{"ok":true,"ws_url":${JSONObject.quote(Settings.wsUrl)},"src":"derived"}"""
        }
    }

    /** GET <base_url>/models with the given key → return the model id list. */
    private fun handleLlmModels(session: IHTTPSession): String {
        val base = param(session, "base_url").ifBlank { Settings.llmBaseUrl }.trimEnd('/')
        val key = param(session, "api_key").ifBlank { Settings.llmApiKey }
        if (base.isBlank()) return """{"ok":false,"error":"no base_url"}"""
        return try {
            val req = Request.Builder().url("$base/models")
                .header("Authorization", "Bearer $key").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return """{"ok":false,"error":"HTTP ${resp.code}"}"""
                val data = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data")
                val ids = JSONArray()
                if (data != null) for (i in 0 until data.length())
                    data.optJSONObject(i)?.optString("id")?.let { ids.put(it) }
                """{"ok":true,"models":$ids}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"error":${JSONObject.quote(e.message ?: "error")}}"""
        }
    }

    /** Validate creds with a tiny /chat/completions ping. Accepts plain-JSON OR SSE replies. */
    private fun handleLlmTest(session: IHTTPSession): String {
        val base = param(session, "base_url").ifBlank { Settings.llmBaseUrl }.trimEnd('/')
        val key = param(session, "api_key").ifBlank { Settings.llmApiKey }
        val model = param(session, "model")
        if (base.isBlank() || model.isBlank()) return """{"ok":false,"error":"base_url+model required"}"""
        val t0 = System.currentTimeMillis()
        return try {
            // NOTE: do NOT send stream:false — some gateways (e.g. OmniRoute combos) 503 with
            // "combo retry limit" when asked for non-streaming, since their pooled providers only
            // stream. We let the provider stream and parse plain-JSON OR SSE below.
            val payload = JSONObject()
                .put("model", model)
                .put("max_tokens", 16)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
                .toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$base/chat/completions")
                .header("Authorization", "Bearer $key").post(payload).build()
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return """{"ok":false,"error":${JSONObject.quote("HTTP ${resp.code}: ${txt.take(160)}")}}"""
                }
                // Reply may be a plain JSON object or an SSE stream ("data: {chunk}"). Accept both.
                val echoed = extractModelId(txt) ?: model
                val dt = System.currentTimeMillis() - t0
                """{"ok":true,"latency_ms":$dt,"model":${JSONObject.quote(echoed)}}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"error":${JSONObject.quote(e.message ?: "error")}}"""
        }
    }

    /** Extract the "model" field from an OpenAI reply that may be plain JSON or an SSE stream. */
    private fun extractModelId(body: String): String? {
        val t = body.trim()
        if (t.startsWith("{")) {
            return runCatching { JSONObject(t).optString("model", "") }.getOrNull()?.ifBlank { null }
        }
        for (raw in t.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val m = runCatching { JSONObject(payload).optString("model", "") }.getOrNull()
            if (!m.isNullOrBlank()) return m
        }
        return null
    }

    /** GET {ha_url}/api/states → controllable entities: [{entity_id, name, domain}]. */
    private fun handleHaDevices(session: IHTTPSession): String {
        val base = param(session, "ha_url").ifBlank { Settings.haUrl }.trimEnd('/')
        val token = param(session, "token").ifBlank { Settings.haToken }
        if (base.isBlank()) return """{"ok":false,"error":"no ha_url"}"""
        return try {
            val req = Request.Builder().url("$base/api/states")
                .header("Authorization", "Bearer $token").get().build()
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return """{"ok":false,"error":${JSONObject.quote("HTTP ${resp.code}: ${txt.take(120)}")}}"""
                val arr = JSONArray(txt)
                val out = JSONArray()
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val id = e.optString("entity_id", "")
                    if (id.isBlank()) continue
                    val name = e.optJSONObject("attributes")?.optString("friendly_name", "") ?: ""
                    out.put(JSONObject().put("entity_id", id).put("name", name).put("domain", id.substringBefore(".")))
                }
                """{"ok":true,"devices":$out}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"error":${JSONObject.quote(e.message ?: "error")}}"""
        }
    }

    /** GET {ha_url}/api/ → HA ping (validates URL + token). */
    private fun handleHaTest(session: IHTTPSession): String {
        val base = param(session, "ha_url").ifBlank { Settings.haUrl }.trimEnd('/')
        val token = param(session, "token").ifBlank { Settings.haToken }
        if (base.isBlank()) return """{"ok":false,"error":"no ha_url"}"""
        return try {
            val req = Request.Builder().url("$base/api/").header("Authorization", "Bearer $token").get().build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) """{"ok":true}"""
                else """{"ok":false,"error":${JSONObject.quote("HTTP ${resp.code}")}}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"error":${JSONObject.quote(e.message ?: "error")}}"""
        }
    }

    private fun scheduleRestart() {
        Thread {
            try {
                Thread.sleep(400) // let the HTTP response flush first
                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                context.startActivity(intent)
                Runtime.getRuntime().exit(0)
            } catch (e: Exception) {
                Log.e(TAG, "restart: ${e.message}")
            }
        }.start()
    }

    private fun serveAsset(): Response {
        // /sdcard/control.html overrides the bundled page -> tweak UI without rebuilding.
        val override = java.io.File("/sdcard/control.html")
        val bytes = if (override.exists() && override.length() > 0) override.readBytes()
        else context.assets.open("control.html").readBytes()
        return newFixedLengthResponse(
            Response.Status.OK, "text/html", ByteArrayInputStream(bytes), bytes.size.toLong(),
        )
    }

    private fun serveWav(): Response {
        val wav = MicTest.wav()
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no recording")
        return newFixedLengthResponse(
            Response.Status.OK, "audio/wav", ByteArrayInputStream(wav), wav.size.toLong(),
        ).apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun json(s: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", s).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }

    companion object {
        private const val TAG = "ControlServer"
        const val PORT = 8088
    }
}
