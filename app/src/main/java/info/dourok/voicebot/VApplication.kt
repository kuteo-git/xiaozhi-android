package info.dourok.voicebot

import android.app.Application
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import info.dourok.voicebot.control.ControlServer
import info.dourok.voicebot.data.Settings
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class VApplication : Application() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ControlEntryPoint {
        fun controlServer(): ControlServer
    }

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        // Start the on-device control panel web server (http://<r1-ip>:8088).
        try {
            EntryPointAccessors.fromApplication(this, ControlEntryPoint::class.java)
                .controlServer().startServer()
        } catch (e: Exception) {
            Log.e("VApplication", "control server start failed: ${e.message}")
        }
        // Log every Java/Kotlin crash to /sdcard/voicebot-crash.log for easy tracing (see tools/crashlog.sh).
        // (Native crashes inside .so produce a tombstone and are not caught here.)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File("/sdcard/voicebot-crash.log").appendText(
                    "\n===== CRASH $ts (thread=${thread.name}) =====\n$sw\n"
                )
                Log.e("VApplication", "CRASH", e)
            } catch (_: Exception) {
            }
            prev?.uncaughtException(thread, e)  // let the system handle it (app still crashes as usual)
        }
    }
}
