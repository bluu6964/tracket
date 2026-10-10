package com.example.engine

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.example.model.TimelineClip

/**
 * Real preview of the main video track.
 * The main track is played gapless, in order; ExoPlayer is the clock while playing.
 */
class EditorPlayer(context: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setSeekBackIncrementMs(1000)
        .setSeekForwardIncrementMs(1000)
        .build()
        .also {
            // Prefer nearest keyframe while scrubbing; exact seek only on settle / play.
            it.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            // Keep a modest buffer so seeks don't wait on large pre-rolls.
            it.playWhenReady = false
        }

    var onEnded: (() -> Unit)? = null

    private var clips: List<TimelineClip> = emptyList()
    private var signature: List<Any> = emptyList()
    private var isScrubbing = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
            }
        })
    }

    /** Rebuilds the playlist only when something that affects playback changed. */
    fun setClips(newClips: List<TimelineClip>, resumeAtMs: Long) {
        val sig = newClips.map {
            listOf(it.id, it.uri.orEmpty(), it.trimStartMs, it.durationMs, it.isImage)
        }
        if (sig == signature) return
        signature = sig
        clips = newClips

        if (newClips.isEmpty()) {
            player.clearMediaItems()
            return
        }
        player.setMediaItems(newClips.map(::toMediaItem), /* resetPosition = */ true)
        player.prepare()
        seekTo(resumeAtMs, force = true)
    }

    private fun toMediaItem(c: TimelineClip): MediaItem {
        val b = MediaItem.Builder().setUri(c.uri!!.toUri())
        c.mimeType?.let { b.setMimeType(it) }
        if (c.isImage) {
            b.setImageDurationMs(c.durationMs)
        } else {
            b.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.trimStartMs)
                    .setEndPositionMs(c.trimStartMs + c.durationMs)
                    .build()
            )
        }
        return b.build()
    }

    /** Timeline ms -> (playlist index, offset inside that clip). */
    fun seekTo(timelineMs: Long, force: Boolean = false) {
        if (clips.isEmpty()) return
        // During continuous scrub the UI already throttles; only skip tiny jumps that
        // would still cost a decoder seek. Exact seeks (settle / play) always run.
        if (!force && kotlin.math.abs(timelinePositionMs() - timelineMs) < 40) return
        var acc = 0L
        for ((i, c) in clips.withIndex()) {
            if (timelineMs < acc + c.durationMs || i == clips.lastIndex) {
                player.seekTo(i, (timelineMs - acc).coerceIn(0L, c.durationMs))
                return
            }
            acc += c.durationMs
        }
    }

    /** Current playlist position expressed on the timeline. */
    fun timelinePositionMs(): Long {
        if (clips.isEmpty()) return 0L
        val index = player.currentMediaItemIndex.coerceIn(0, clips.lastIndex)
        var acc = 0L
        for (i in 0 until index) acc += clips[i].durationMs
        return acc + player.currentPosition.coerceIn(0L, clips[index].durationMs)
    }

    fun totalMs(): Long = clips.sumOf { it.durationMs }

    /** While scrubbing, jump to the nearest keyframe (fast). Before playing, seek exactly. */
    fun setScrubbing(scrubbing: Boolean) {
        if (isScrubbing == scrubbing) return
        isScrubbing = scrubbing
        player.setSeekParameters(
            if (scrubbing) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT
        )
    }

    fun setMuted(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
    }

    fun release() {
        onEnded = null
        player.release()
    }
}
