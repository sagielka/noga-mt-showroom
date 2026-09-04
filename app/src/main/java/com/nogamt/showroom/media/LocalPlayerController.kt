package com.nogamt.showroom.media

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nogamt.showroom.Constants

/**
 * Native local playback with Media3 / ExoPlayer.
 *
 * SAF and USB content URIs are far more reliable through the native media stack than through
 * WebView file access, so anything the Lovable playlist has a local copy of is played here and
 * the result is reported back to the web app as a DOM event.
 */
@UnstableApi
class LocalPlayerController(
    private val context: Context,
    private val playerView: PlayerView,
    private val events: Events
) {

    interface Events {
        fun onLocalVideoStarted(id: String)
        fun onLocalVideoEnded(id: String)
        fun onLocalVideoStopped(id: String)
        fun onLocalVideoError(id: String, message: String)
    }

    private var player: ExoPlayer? = null
    private var currentId: String? = null
    private var startedReported = false

    val isActive: Boolean get() = currentId != null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val id = currentId ?: return
            when (state) {
                Player.STATE_READY -> {
                    if (!startedReported) {
                        startedReported = true
                        playerView.visibility = View.VISIBLE
                        events.onLocalVideoStarted(id)
                    }
                }
                Player.STATE_ENDED -> {
                    Log.i(Constants.LOG, "Local video ended: $id")
                    teardown()
                    events.onLocalVideoEnded(id)
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = currentId ?: "unknown"
            Log.e(Constants.LOG, "Local playback error for $id", error)
            teardown()
            events.onLocalVideoError(id, error.errorCodeName)
        }
    }

    /** Starts playback. Returns false if the file could not be opened at all. */
    fun play(video: LocalVideo): Boolean {
        return try {
            stopInternal(notifyId = currentId, notify = true)

            val exo = ExoPlayer.Builder(context).build().also { player = it }
            exo.addListener(listener)
            exo.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            playerView.player = exo
            playerView.keepScreenOn = true

            currentId = video.id
            startedReported = false

            exo.setMediaItem(MediaItem.fromUri(Uri.parse(video.uri)))
            exo.repeatMode = Player.REPEAT_MODE_OFF
            exo.playWhenReady = true
            exo.prepare()
            Log.i(Constants.LOG, "Local playback requested: ${video.id} (${video.fileName})")
            true
        } catch (t: Throwable) {
            Log.e(Constants.LOG, "Could not start local playback for ${video.id}", t)
            val id = video.id
            teardown()
            events.onLocalVideoError(id, t.message ?: "playback_failed")
            false
        }
    }

    /** Staff / web requested stop. Emits the stopped event. */
    fun stop() {
        val id = currentId ?: return
        stopInternal(notifyId = id, notify = true)
    }

    fun pause() {
        player?.playWhenReady = false
    }

    fun resume() {
        player?.playWhenReady = true
    }

    fun togglePlayPause(): Boolean {
        val p = player ?: return false
        p.playWhenReady = !p.playWhenReady
        return true
    }

    /** Full release - call from Activity.onDestroy. Emits nothing. */
    fun release() {
        stopInternal(notifyId = null, notify = false)
    }

    private fun stopInternal(notifyId: String?, notify: Boolean) {
        val hadPlayer = player != null
        teardown()
        if (notify && notifyId != null && hadPlayer) {
            events.onLocalVideoStopped(notifyId)
        }
    }

    /** Detaches and releases the player + surface without emitting events. */
    private fun teardown() {
        currentId = null
        startedReported = false
        val p = player
        player = null
        playerView.player = null
        playerView.keepScreenOn = false
        playerView.visibility = View.GONE
        if (p != null) {
            runCatching {
                p.removeListener(listener)
                p.stop()
                p.clearMediaItems()
                p.release()
            }.onFailure { Log.w(Constants.LOG, "Player release issue", it) }
        }
    }
}
