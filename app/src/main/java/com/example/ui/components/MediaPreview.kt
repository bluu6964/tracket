package com.example.ui.components

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@Composable
fun MediaPreview(
    sourceUri: String?,
    positionMs: Long,
    isPlaying: Boolean,
    muted: Boolean,
    modifier: Modifier = Modifier,
    onDuration: (Long) -> Unit = {}
) {
    if (sourceUri == null) {
        Box(modifier = modifier.background(Color(0xFF090A0D)), contentAlignment = Alignment.Center) {
            Text("Import a video to start editing", color = Color.White)
        }
        return
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(sourceUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(sourceUri))
            prepare()
        }
    }

    LaunchedEffect(positionMs, isPlaying) {
        if (kotlin.math.abs(player.currentPosition - positionMs) > 120L) {
            player.seekTo(positionMs)
        }
        player.playWhenReady = isPlaying
    }

    LaunchedEffect(player) {
        while (true) {
            val duration = player.duration
            if (duration > 0) onDuration(duration)
            kotlinx.coroutines.delay(250)
        }
    }

    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            PlayerView(it).apply {
                useController = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                this.player = player
            }
        },
        update = { it.player = player }
    )
}
