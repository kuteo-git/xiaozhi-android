package info.dourok.voicebot.domain.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MediaPlaybackState { IDLE, DOWNLOADING, PLAYING, PAUSED, STOPPED }

data class MediaQueueItem(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnail: String,
    val duration: String,
)

data class MediaNowPlaying(
    val state: MediaPlaybackState = MediaPlaybackState.IDLE,
    val videoId: String? = null,
    val title: String = "",
    val artist: String = "",
    val thumbnail: String = "",
    val durationS: Int = 0,
    val positionS: Int = 0,
)

/**
 * Single source of truth for the unified media session. Server-driven (play_youtube.py pushes
 * media_queue / media_now_playing over the WS) -- written by [VoiceAssistant]'s
 * handleServerMessage, read by ControlServer for /api/media/state. Replaces the old on-device
 * MediaPlayerController + MediaCoordinator now that there's only one playback engine (the voice
 * pipeline's), so there's nothing left to coordinate between two players.
 */
object MediaSessionState {
    private val _queue = MutableStateFlow<List<MediaQueueItem>>(emptyList())
    val queue: StateFlow<List<MediaQueueItem>> = _queue.asStateFlow()

    private val _nowPlaying = MutableStateFlow(MediaNowPlaying())
    val nowPlaying: StateFlow<MediaNowPlaying> = _nowPlaying.asStateFlow()

    fun updateQueue(items: List<MediaQueueItem>) { _queue.value = items }
    fun updateNowPlaying(np: MediaNowPlaying) { _nowPlaying.value = np }

    /**
     * Drop everything when the channel to the server closes. This state is only ever pushed BY the
     * server, so a disconnect (server restart, wifi drop) would otherwise freeze the last snapshot
     * on screen -- the panel would keep showing a song as "playing" with nothing coming out of the
     * speaker, which is indistinguishable from a real playback bug.
     */
    fun clear() {
        _queue.value = emptyList()
        _nowPlaying.value = MediaNowPlaying()
    }
}
