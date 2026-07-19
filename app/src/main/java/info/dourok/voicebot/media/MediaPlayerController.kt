package info.dourok.voicebot.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import dagger.hilt.android.qualifiers.ApplicationContext
import info.dourok.voicebot.domain.voice.MediaCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device playback engine for the Media Player tab. Wraps ExoPlayer + a MediaSession so
 * playback also surfaces as Android's own quick-settings media card, fully independent from the
 * voice pipeline's TTS-interleaved audio queue (play_youtube.py streams over the xiaozhi
 * WebSocket; this streams straight from pytube_api's /v3/mp3/<id> via HTTP).
 *
 * ExoPlayer requires all calls to happen on the thread that created it (normally main). This
 * class is Hilt-constructed from VApplication.onCreate() (main thread) alongside ControlServer,
 * so construction is safe; every call coming from ControlServer's NanoHTTPD worker threads is
 * hopped onto the main thread internally so callers never need to think about it.
 */
@Singleton
class MediaPlayerController @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob())
    private val player = ExoPlayer.Builder(context).build()
    private val session = MediaSession.Builder(context, player).build()

    private val _state = MutableStateFlow(MediaPlayerState())
    val state: StateFlow<MediaPlayerState> = _state.asStateFlow()

    // ExoPlayer doesn't push position changes; poll it on the main thread every 500ms while
    // playing so /api/media/state (read from _state, never from `player` directly) stays fresh.
    private val positionTicker = object : Runnable {
        override fun run() {
            if (_state.value.playing) {
                _state.value = _state.value.copy(positionMs = player.currentPosition.coerceAtLeast(0))
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(playing = isPlaying)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> _state.value = _state.value.copy(
                        downloadState = DownloadState.READY,
                        durationMs = player.duration.coerceAtLeast(0),
                    )
                    // Track finished on its own (not paused/stopped) -> control.html auto-advances
                    // to the next item in its currently displayed list.
                    Player.STATE_ENDED -> _state.value = _state.value.copy(ended = true, playing = false)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                _state.value = _state.value.copy(
                    downloadState = DownloadState.ERROR,
                    errorMessage = error.message,
                    playing = false,
                )
            }
        })
        mainHandler.post(positionTicker)
        // Voice-triggered music (play_youtube) always wins over a web-triggered track.
        scope.launch {
            MediaCoordinator.voiceMusicActive.collect { active ->
                if (active) mainHandler.post { player.pause() }
            }
        }
    }

    /** Called by ControlServer right before it asks pytube_api to ensure [videoId] is cached. */
    fun markDownloading(videoId: String, title: String, artist: String, coverUrl: String) {
        _state.value = MediaPlayerState(
            videoId = videoId, title = title, artist = artist, coverUrl = coverUrl,
            downloadState = DownloadState.DOWNLOADING,
        )
    }

    /** Called by ControlServer if pytube_api's lookup/download for [videoId] failed. */
    fun markError(videoId: String, message: String) {
        val cur = _state.value
        if (cur.videoId != videoId) return  // a newer play() already superseded this one
        _state.value = cur.copy(downloadState = DownloadState.ERROR, errorMessage = message, playing = false)
    }

    /** Starts playback once pytube_api has confirmed [streamUrl] (its /v3/mp3/<id> URL) is ready. */
    fun play(videoId: String, streamUrl: String, title: String, artist: String, coverUrl: String) {
        MediaCoordinator.webPlayRequested.tryEmit(Unit)  // interrupt whatever the voice pipeline is doing
        mainHandler.post {
            val item = MediaItem.Builder()
                .setUri(streamUrl)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).build())
                .build()
            player.setMediaItem(item)
            player.prepare()
            player.play()
        }
    }

    fun pause() { mainHandler.post { player.pause() } }
    fun resume() { mainHandler.post { player.play() } }

    fun seekTo(positionMs: Long) {
        mainHandler.post { player.seekTo(positionMs) }
        _state.value = _state.value.copy(positionMs = positionMs)  // optimistic, avoids a stale poll flash
    }
}
