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
    /** True once the current track finishes playing on its own (not paused/stopped by the user).
     *  Reset by the next markDownloading()/play() call. Drives auto-advance in control.html. */
    val ended: Boolean = false,
    /** 0-100 while downloadState==DOWNLOADING (from pytube_api's /v3/download_progress), -1 if
     *  unknown (e.g. cached file, or pytube_api hasn't reported yet). */
    val downloadPercent: Int = -1,
)
