package info.dourok.voicebot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Launches the app on device boot (replaces aiboxplus auto-start). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i("BootReceiver", "Boot completed -> launch MainActivity")
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(launch)
            } catch (e: Exception) {
                Log.e("BootReceiver", "launch failed", e)
            }
        }
    }
}
