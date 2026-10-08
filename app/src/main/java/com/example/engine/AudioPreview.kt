package com.example.engine

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.model.LayerType
import com.example.model.TimelineTrack
import com.example.model.fadeGainAt
import kotlin.math.abs

/**
 * Plays the audio layers while previewing. Every audio layer near the playhead gets its own
 * small ExoPlayer; the editor playhead is the clock, the players just follow it.
 * Volume, fades, speed, layer/master mute are applied live. Call [sync] on every playhead change.
 */
class AudioPreview(context: Context) {

    private class Slot(val player: ExoPlayer, var sig: List<Any> = emptyList())

    private val appContext = context.applicationContext
    private val slots = HashMap<String, Slot>()

    fun sync(
        tracks: List<TimelineTrack>,
        playheadMs: Long,
        playing: Boolean,
        masterMuted: Boolean
    ) {
        val wanted = HashSet<String>()
        for (track in tracks) {
            for (clip in track.clips) {
                val uri = clip.uri
                if (clip.type != LayerType.AUDIO || uri == null) continue
                // Only layers around the playhead keep a player (decoders are limited).
                val near = playheadMs >= clip.startMs - PRELOAD_MS && playheadMs < clip.endMs + KEEP_MS
                if (!near) continue
                wanted += clip.id

                val slot = slots.getOrPut(clip.id) { Slot(newPlayer()) }
                val p = slot.player

                val sig = listOf(uri, clip.trimStartMs, clip.durationMs, clip.speed)
                if (slot.sig != sig) {
                    slot.sig = sig
                    val spanMs = (clip.durationMs * clip.speed).toLong()
                    val item = MediaItem.Builder()
                        .setUri(uri.toUri())
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(clip.trimStartMs)
                                .setEndPositionMs(clip.trimStartMs + spanMs)
                                .build()
                        )
                        .build()
                    p.playWhenReady = false
                    p.setMediaItem(item)
                    p.playbackParameters = PlaybackParameters(clip.speed, 1f)
                    p.prepare()
                }

                val active = playheadMs >= clip.startMs && playheadMs < clip.endMs
                if (active) {
                    val rel = playheadMs - clip.startMs
                    val target = (rel * clip.speed).toLong()
                    val muted = masterMuted || clip.isMuted || track.isMuted
                    p.volume = if (muted) 0f else (clip.volume * clip.fadeGainAt(rel)).coerceIn(0f, 1f)
                    if (playing) {
                        if (!p.playWhenReady) {
                            p.seekTo(target)
                            p.playWhenReady = true
                        } else if (p.playbackState == Player.STATE_READY &&
                            abs(p.currentPosition - target) > DRIFT_MS
                        ) {
                            p.seekTo(target)
                        }
                    } else {
                        p.playWhenReady = false
                        if (abs(p.currentPosition - target) > 50L) p.seekTo(target)
                    }
                } else {
                    p.playWhenReady = false
                    if (p.currentPosition != 0L) p.seekTo(0L)
                }
            }
        }
        val gone = slots.keys.filter { it !in wanted }
        for (id in gone) slots.remove(id)?.player?.release()
    }

    private fun newPlayer(): ExoPlayer {
        val p = ExoPlayer.Builder(appContext).build()
        // Several players at once: none of them should grab audio focus from the others.
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ false
        )
        return p
    }

    fun pauseAll() {
        slots.values.forEach { it.player.playWhenReady = false }
    }

    fun release() {
        slots.values.forEach { it.player.release() }
        slots.clear()
    }

    private companion object {
        const val PRELOAD_MS = 2_000L
        const val KEEP_MS = 1_000L
        const val DRIFT_MS = 200L
    }
}
