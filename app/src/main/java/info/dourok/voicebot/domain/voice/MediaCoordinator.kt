package info.dourok.voicebot.domain.voice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Cross-component signal so the web-triggered MediaPlayerController (info.dourok.voicebot.media)
 * and the voice pipeline's [VoiceAssistant] never play audio at the same time. They're otherwise
 * fully independent (different transports: HTTP mp3 stream vs. WS opus frames), so without this
 * both can end up "playing" simultaneously -- audible as two overlapping songs.
 */
object MediaCoordinator {
    /** Emitted right before MediaPlayerController starts a web-triggered track. VoiceAssistant
     *  reacts by interrupting (flush + abort) any in-progress voice/TTS/music playback. */
    val webPlayRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** True while the server-driven voice pipeline is playing music (play_youtube). MediaPlayerController
     *  reacts by pausing itself, and ControlServer surfaces this in /api/media/state so the web UI's
     *  Now Playing card reflects voice-triggered playback too. */
    val voiceMusicActive = MutableStateFlow(false)
}
