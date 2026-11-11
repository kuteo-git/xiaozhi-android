package info.dourok.voicebot.domain.voice

/** Whether a wake-word detection becomes a wake. Other wake paths (button, text, Play, alarm) never ask. */
object WakeGate {
    fun accepts(detected: Boolean, wakeEnabled: Boolean): Boolean = detected && wakeEnabled
}
