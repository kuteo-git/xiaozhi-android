package info.dourok.voicebot.media

enum class DownloadState { IDLE, DOWNLOADING, READY, ERROR }

/** Snapshot of the Media Player tab's playback state, polled by ControlServer's
 *  `/api/media/state` and rendered by control.html's Now Playing card. */
data class MediaPlayerState(
    val playing: Boolean = false,
    val videoId: String? = null,
    val title: String = "",
    val artist: String = "",
    val coverUrl: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val downloadState: DownloadState = DownloadState.IDLE,
    val errorMessage: String? = null,
)
