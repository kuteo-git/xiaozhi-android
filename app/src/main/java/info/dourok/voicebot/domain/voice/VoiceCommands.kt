package info.dourok.voicebot.domain.voice

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Session commands from the control panel, consumed by [VoiceAssistant]. Same reason as
 * [MediaCommands]: ControlServer cannot hold the one started VoiceAssistant instance.
 */
object VoiceCommands {
    sealed class Command {
        /** End the current session. A no-op when the runtime is already idle. */
        object Sleep : Command()
    }

    val flow = MutableSharedFlow<Command>(extraBufferCapacity = 4)
}
