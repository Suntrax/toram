package com.blissless.toram

import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
@Composable
fun ExoPlayerVideoPlayer(
    url: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val player = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 30000,
                /* maxBufferMs */ 120000,
                /* bufferForPlaybackMs */ 3000,
                /* bufferForPlaybackAfterRebufferMs */ 10000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(120_000)
            .setAllowCrossProtocolRedirects(true)

        val extractorsFactory = DefaultExtractorsFactory()
            .setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES) // FLAG_DISABLE_SEEKING_CUES = 1

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
            .build()
            .also { exoPlayer ->
                val mediaItem = MediaItem.fromUri(url)
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> Log.d(TAG, "STATE_READY")
                            Player.STATE_BUFFERING -> Log.d(TAG, "STATE_BUFFERING")
                            Player.STATE_ENDED -> Log.d(TAG, "STATE_ENDED")
                            Player.STATE_IDLE -> Log.d(TAG, "STATE_IDLE")
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Player error", error)
                    }
                })
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            Log.d(TAG, "Releasing ExoPlayer")
            player.run {
                playWhenReady = false
                stop()
                release()
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                keepScreenOn = true
            }
        },
        modifier = modifier
    )
}

private const val TAG = "ExoPlayerVideoPlayer"
