package info.dourok.voicebot.data.bluetooth

import android.util.Log
import info.dourok.voicebot.domain.voice.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Runs one command through the R1's own shell service on its port 8080 (uid system, see CLAUDE.md
 * "R1 shell"). Used for the one thing the public API cannot tell this app: which audio session the
 * ROM's Bluetooth process plays on. Only constant commands go through it -- nothing a user types
 * ever reaches the shell.
 */
class R1Shell(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .build(),
) {
    /**
     * The `data` text of every frame, joined, up to the end marker -- or null if the shell was
     * unreachable, the socket failed, or the marker did not arrive within [timeoutMs].
     *
     * The command is expected to finish with `echo` of [END_MARKER]. The marker is only compared
     * against what the shell prints: the first frame echoes the command back, so a command that
     * spells the marker out literally would end the read on its own echo. Spell it split by an
     * empty quote pair (`__EN''D__`) -- the shell joins it, the echo keeps it split.
     */
    suspend fun run(cmd: String, timeoutMs: Long = 3000): String? {
        val done = CompletableDeferred<String?>()
        val buffer = StringBuilder()
        val request = Request.Builder()
            .url(URL)
            .header("Sec-WebSocket-Protocol", "v1")
            .build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val frame = JSONObject()
                    .put("type", "shell")
                    .put("type_id", "myshell")
                    .put("shell", cmd)
                    .toString()
                webSocket.send(frame)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                buffer.append(try { JSONObject(text).optString("data") } catch (_: Exception) { text })
                val end = buffer.indexOf(END_MARKER)
                if (end >= 0) done.complete(buffer.substring(0, end))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "shell socket failed: ${t.message}")
                done.complete(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(null)
            }
        })
        try {
            val out = withTimeoutOrNull(timeoutMs) { done.await() }
            if (out == null) AppLog.w("Shell 8080: không có kết quả (lỗi hoặc quá ${timeoutMs}ms)")
            return out
        } finally {
            socket.cancel()
        }
    }

    companion object {
        private const val TAG = "R1Shell"
        private const val URL = "ws://127.0.0.1:8080"
        const val END_MARKER = "__END__"
    }
}
