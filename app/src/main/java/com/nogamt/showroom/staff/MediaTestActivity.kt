package com.nogamt.showroom.staff

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nogamt.showroom.Constants
import com.nogamt.showroom.R
import com.nogamt.showroom.media.LocalVideo
import com.nogamt.showroom.media.MediaIndex
import com.nogamt.showroom.media.MediaRepository
import com.nogamt.showroom.media.MediaScanner

/**
 * TEST LOCAL VIDEO / TEST VIDEO BY ID.
 *
 * Deliberately isolated: no WebView, no Lovable, no YouTube, no OPFS. If a video plays here,
 * then storage, SAF permission, the media index, Media3 and the display are all working, and
 * any remaining problem is on the web side.
 */
@UnstableApi
class MediaTestActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var detail: TextView
    private lateinit var state: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_test)
        playerView = findViewById(R.id.test_player)
        detail = findViewById(R.id.test_detail)
        state = findViewById(R.id.test_state)

        val requestedId = intent?.getStringExtra(EXTRA_VIDEO_ID)
        val video = if (requestedId.isNullOrBlank()) {
            MediaIndex.all().firstOrNull { MediaIndex.isPlayable(it.id) }
        } else {
            MediaIndex.find(requestedId)
        }

        if (video == null) {
            detail.text = if (requestedId.isNullOrBlank()) {
                "NOT FOUND · the local media index is empty"
            } else {
                "NOT FOUND · no local copy of \"$requestedId\""
            }
            state.text = "Check Local Media → MEDIA SOURCE and RESCAN VIDEOS"
            return
        }

        describe(video)
        startPlayback(video)
    }

    private fun describe(video: LocalVideo) {
        detail.text = buildString {
            append("FOUND · ${video.id}\n")
            append("${video.fileName}\n")
            append(MediaScanner.formatBytes(video.sizeBytes))
            append(" · match ${video.matchType.name}")
            append(" · state ${MediaIndex.stateOf(video.id).name}")
        }
        state.text = "Source: ${MediaRepository.storageLabel(this)} · " +
            "available ${if (MediaIndex.isPlayable(video.id)) "YES" else "NO"}"
    }

    private fun startPlayback(video: LocalVideo) {
        release()
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state.text = when (playbackState) {
                    Player.STATE_BUFFERING -> "BUFFERING…"
                    Player.STATE_READY -> "PLAYING · native Media3 playback confirmed"
                    Player.STATE_ENDED -> "ENDED · full playback completed successfully"
                    else -> "IDLE"
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(Constants.LOG, "Test playback failed for ${video.id}", error)
                state.text = "ERROR · ${error.errorCodeName}\n" +
                    "The file is indexed but Media3 could not play it."
            }
        })
        exo.setMediaItem(MediaItem.fromUri(Uri.parse(video.uri)))
        exo.playWhenReady = true
        exo.prepare()
        Log.i(Constants.LOG, "Native playback test started for ${video.id}")
    }

    private fun release() {
        val exo = player
        player = null
        playerView.player = null
        runCatching {
            exo?.stop()
            exo?.clearMediaItems()
            exo?.release()
        }
    }

    override fun onPause() {
        player?.playWhenReady = false
        super.onPause()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_VIDEO_ID = "com.nogamt.showroom.TEST_VIDEO_ID"

        /** TEST LOCAL VIDEO - plays the first indexed, readable file. */
        fun firstVideo(context: Context): Intent =
            Intent(context, MediaTestActivity::class.java)

        /** TEST VIDEO BY ID. */
        fun byId(context: Context, videoId: String): Intent =
            Intent(context, MediaTestActivity::class.java).putExtra(EXTRA_VIDEO_ID, videoId)
    }
}
