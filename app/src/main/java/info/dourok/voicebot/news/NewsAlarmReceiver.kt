package info.dourok.voicebot.news

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import info.dourok.voicebot.control.ControlServer
import info.dourok.voicebot.domain.voice.TextCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Fires for the News-bulletin daily alarm (see NewsAlarmScheduler). Sends the bulletin request as
 * a typed query -- the exact path the "Phát thử" button and /api/say use -- so the server's
 * get_news_bulletin tool produces and plays it. Self-reschedules for tomorrow after handling it,
 * so no persistent foreground service is needed to keep the daily cycle alive. */
class NewsAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind") ?: return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (kind) {
                    "trigger" -> {
                        Log.i(TAG, "news alarm -> sending '${ControlServer.NEWS_PHRASE}'")
                        TextCommands.flow.tryEmit(ControlServer.NEWS_PHRASE)
                    }
                    else -> Log.w(TAG, "unknown alarm kind: $kind")
                }
            } catch (e: Exception) {
                Log.e(TAG, "handling '$kind' failed", e)
            } finally {
                NewsAlarmScheduler.rescheduleOne(appContext, kind)
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "NewsAlarmReceiver"
    }
}
