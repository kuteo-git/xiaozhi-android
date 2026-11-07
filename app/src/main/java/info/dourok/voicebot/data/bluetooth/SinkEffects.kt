package info.dourok.voicebot.data.bluetooth

import info.dourok.voicebot.data.voice.PlaybackEffects
import info.dourok.voicebot.domain.bluetooth.sinkPlaybackSession
import info.dourok.voicebot.domain.voice.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Gives a phone's music, received over Bluetooth (A2DP sink), the same equalizer and loudness the
 * app puts on its own playback.
 *
 * The ROM's Bluetooth process plays that audio on its own AudioTrack, near full volume and with no
 * effects, which measured much quieter than the app's tuned sound. The track's session is not
 * available through any API on API 22, so it is found through the R1's shell service and the
 * effects are attached to it from here: an AudioEffect may be created on another process's
 * session.
 *
 * Everything that moves or releases effects runs on one thread, and a new [reconcile] cancels the
 * one before it, so a slow discovery can never attach after the stream has already gone.
 */
class SinkEffects(private val shell: R1Shell = R1Shell()) {

    private val scope = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor { r ->
            Thread(r, "sink-effects").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    )

    private var job: Job? = null

    // Only touched on the scope's thread; the session is also read from the HTTP thread.
    private var effects: PlaybackEffects? = null
    @Volatile private var attachedSession: Int? = null

    /** The session the effects are attached to, or null when none are (nothing streaming, or not found). */
    fun tunedSession(): Int? = attachedSession

    /** Apply the panel's settings to the attached effects now. */
    fun retune() {
        scope.launch { effects?.apply() }
    }

    /** [streaming]: a phone is connected to the sink. Safe to call from any thread, as often as needed. */
    @Synchronized
    fun reconcile(streaming: Boolean) {
        job?.cancel()
        job = scope.launch {
            if (!streaming) {
                detach("không còn nhận nhạc")
                return@launch
            }
            var shellAnswered = false
            var found: Int? = null
            for (wait in RETRY_DELAYS_MS) {
                delay(wait)
                val out = shell.run(DISCOVERY_COMMAND)
                if (out != null) shellAnswered = true
                found = out?.let(::sinkPlaybackSession)
                if (found != null) break
            }
            currentCoroutineContext().ensureActive()
            when {
                found != null -> if (found != attachedSession) attach(found)
                // A shell that did not answer says nothing about the track, so what is attached
                // stays; only an answer without the Bluetooth track proves the session is gone.
                shellAnswered -> detach("không còn track của Bluetooth")
            }
        }
    }

    private fun attach(session: Int) {
        detach(null)
        val fx = PlaybackEffects(session)
        if (!fx.attached) {
            AppLog.w("Bluetooth: không gắn được EQ/loudness vào session $session")
            return
        }
        effects = fx
        attachedSession = session
        AppLog.i("Bluetooth: đã gắn EQ và loudness vào nhạc nhận được (session $session)")
    }

    private fun detach(why: String?) {
        val fx = effects ?: return
        fx.release()
        effects = null
        attachedSession = null
        if (why != null) AppLog.i("Bluetooth: gỡ EQ/loudness khỏi nhạc nhận được ($why)")
    }

    private companion object {
        /** The track may be created a moment after the broadcast that announced the stream. */
        val RETRY_DELAYS_MS = longArrayOf(0, 500, 1500, 3000)

        const val DISCOVERY_COMMAND =
            "ps | grep com.android.bluetooth; " +
                "dumpsys media.audio_flinger | grep -e 'Output thread' -e '^ *[0-9]* *yes' -e '^ *[0-9]* *no'; " +
                "echo __EN''D__"
    }
}
