package info.dourok.voicebot.news

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.data.model.fromJsonToDeviceInfo
import java.util.Calendar

/**
 * Holds the News bulletin's daily clock ON-DEVICE (AlarmManager), not server-side: the R1's WS
 * connection is connect-on-wake, not persistent (see WebsocketProtocol.isAudioChannelOpened --
 * openAudioChannel tears down and reconnects), so a server-side scheduler would find the device
 * offline at the trigger time almost every day. One alarm per day, "trigger": fires at the
 * configured time and emits [info.dourok.voicebot.domain.voice.NewsCommands], which
 * VoiceAssistant.onNewsBulletin turns into a WS connect tagged ?trigger=news_bulletin -- the
 * server does the ENTIRE fetch+LLM-rewrite+play live on that connection (see xiaozhi-server's
 * core/news package). No separate prewarm ping: the LLM rewrite step needs the device's real,
 * live conn.llm (a per-session BYO override applied from the WS "hello" message), which only
 * exists on an actual connection -- a headless HTTP ping ahead of time has no way to get it right,
 * so there's nothing a prewarm step could safely precompute beyond the raw fetch, and that alone
 * wasn't worth the extra moving part.
 * Self-reschedules for the next day from [NewsAlarmReceiver] instead of relying on AlarmManager's
 * inexact setRepeating.
 */
object NewsAlarmScheduler {
    private const val TAG = "NewsAlarmScheduler"
    private const val REQ_TRIGGER = 4202

    /** Called after every control-panel save and on boot: cancels + re-derives the alarm fresh
     * from the current Settings (enabled/time). */
    fun reschedule(context: Context) {
        cancel(context)
        if (!Settings.newsEnabled) {
            Log.i(TAG, "News disabled -> no alarm scheduled")
            return
        }
        val (hour, minute) = parseTime(Settings.newsTime) ?: run {
            Log.w(TAG, "Bad news_time '${Settings.newsTime}' -> no alarm scheduled")
            return
        }
        val triggerAt = nextOccurrence(hour, minute)
        scheduleAt(context, REQ_TRIGGER, "trigger", triggerAt)
        Log.i(TAG, "News alarm scheduled: trigger=$triggerAt")
    }

    /** Reschedules the alarm for its next daily occurrence -- called by [NewsAlarmReceiver] right
     * after it fires, so the daily cycle perpetuates without a persistent foreground service. */
    fun rescheduleOne(context: Context, kind: String) {
        if (kind != "trigger" || !Settings.newsEnabled) return
        val (hour, minute) = parseTime(Settings.newsTime) ?: return
        scheduleAt(context, REQ_TRIGGER, "trigger", nextOccurrence(hour, minute))
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, REQ_TRIGGER, "trigger"))
    }

    /**
     * setExactAndAllowWhileIdle/setAndAllowWhileIdle only EXIST from API 23 (M) -- calling either
     * on a lower API throws NoSuchMethodError, not just a permission problem (hit exactly this on
     * the R1: Android 5.1 / API 22 -- see commit history). Runs on NanoHTTPD's request thread (via
     * ControlServer.handleNewsSave), so an uncaught throw here crashes the WHOLE app, not just this
     * save -- hence the outer catch(Throwable) belt-and-suspenders on top of the proper API tiers.
     */
    private fun scheduleAt(context: Context, reqCode: Int, kind: String, atMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, reqCode, kind)
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms() -> {
                    Log.w(TAG, "Exact-alarm permission not granted -> inexact ($kind)")
                    am.set(AlarmManager.RTC_WAKEUP, atMillis, pi)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ->
                    am.setExact(AlarmManager.RTC_WAKEUP, atMillis, pi)
                else ->
                    am.set(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "scheduleAt($kind) failed, falling back to plain set()", e)
            try {
                am.set(AlarmManager.RTC_WAKEUP, atMillis, pi)
            } catch (e2: Throwable) {
                Log.e(TAG, "even plain set() failed for $kind", e2)
            }
        }
    }

    private fun pendingIntent(context: Context, reqCode: Int, kind: String): PendingIntent {
        val intent = Intent(context, NewsAlarmReceiver::class.java).putExtra("kind", kind)
        return PendingIntent.getBroadcast(
            context, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun nextOccurrence(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun parseTime(s: String): Pair<Int, Int>? {
        val parts = s.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    /** Server's HTTP API host:8003, derived from ws_url the same way ControlServer's
     * ttsHostFromWs/pytubeBase derive their ports -- one source of truth instead of a separately
     * user-typed host. */
    fun serverHttpBase(): String {
        val ws = Settings.wsUrl
        if (ws.isBlank()) return ""
        return try {
            "http://${java.net.URI(ws).host}:8003"
        } catch (e: Exception) {
            ""
        }
    }

    /** MUST match the WS connection's Device-Id header exactly (see AppModule.provideDeviceInfo /
     * WebsocketProtocol: `.addHeader("Device-Id", deviceInfo.mac_address)`), NOT the android_id
     * ControlServer.deviceMac() uses elsewhere -- those are two different identifiers, and the
     * server keys the News schedule store by conn.device_id, which comes from that WS header. */
    fun deviceId(context: Context): String {
        val sp = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val json = sp.getString("device_id", null) ?: return "unknown"
        return try {
            fromJsonToDeviceInfo(json).mac_address
        } catch (e: Exception) {
            "unknown"
        }
    }
}
