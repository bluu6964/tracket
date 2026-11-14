package com.example.engine

import android.content.Context
import com.example.model.AnimProp
import com.example.model.Ducking
import com.example.model.Keyframe
import com.example.model.TimelineClip
import com.example.model.fadeGainAt
import com.example.model.sourceOffsetMs

/**
 * Audio ducking (build 194): turns a ducked layer's settings into the gain
 * curve the shared C++ mixer multiplies in — the ONE computation both sound
 * paths use (the preview through [NativeAudioPreview]'s cache, the exporter
 * during setup), so preview and export duck identically by construction.
 *
 * The trigger is resolved through [ExportPlanNative.pickAudio]'s choices —
 * the same list the path is about to mix — so a trigger that makes no sound
 * in the mix (muted, hidden, track-muted, no file, deleted) ducks nothing.
 * Its envelope is decoded with build 193's [AudioBeatDecode] over exactly
 * the window the trigger plays, mapped onto the timeline by the model's own
 * time law ([Ducking.timelineLevels]: trim / speed / speed ramp), and scaled
 * by how loud the trigger is heard (its mix volume x its fades). The pure
 * law ([Ducking.curve]) does the rest. Any failure along the way (no sound,
 * an over-long span, a decode error) yields null = no ducking, never a
 * broken mix.
 */
internal object DuckCurve {

    /** A computed curve: breakpoint times (microseconds on the ducked layer's clock) + gains. */
    class Curve(val relUs: LongArray, val gains: FloatArray) {
        /** Content hash for the preview's sound signature: any curve change restarts the sound. */
        val version: Int = 31 * relUs.contentHashCode() + gains.contentHashCode()
    }

    /**
     * Everything a curve depends on, for the preview's cache: the ducked
     * layer's placement + duck settings and the trigger's placement, time
     * law (base speed + SPEED keys), heard gain (the pick's volume / fades)
     * and file. Any edit to any of these recomputes — no stale curves.
     */
    data class Key(
        val duckedId: String,
        val duckedStartMs: Long,
        val duckedDurationMs: Long,
        val amountDb: Float,
        val smoothness: Float,
        val triggerId: String,
        val triggerUri: String?,
        val triggerStartMs: Long,
        val triggerDurationMs: Long,
        val triggerTrimStartMs: Long,
        val triggerSpeed: Float,
        val triggerSpeedKeys: List<Keyframe>?,
        val triggerVolume: Float,
        val triggerFadeInMs: Long,
        val triggerFadeOutMs: Long
    )

    /** The pick that drives [ducked]'s ducking, or null when ducking is off / dangling / self. */
    fun triggerPick(
        picks: List<ExportPlanNative.AudioChoice>,
        ducked: TimelineClip
    ): ExportPlanNative.AudioChoice? {
        if (!ducked.duckEnabled) return null
        val triggerId = ducked.duckTriggerId ?: return null
        if (triggerId == ducked.id) return null
        return picks.firstOrNull { it.clip.id == triggerId }
    }

    /** The cache key for [ducked]'s curve under this exact plan, or null when it cannot duck. */
    fun keyFor(picks: List<ExportPlanNative.AudioChoice>, ducked: TimelineClip): Key? {
        val trigger = triggerPick(picks, ducked) ?: return null
        val t = trigger.clip
        if (t.uri == null) return null
        return Key(
            duckedId = ducked.id,
            duckedStartMs = ducked.startMs,
            duckedDurationMs = ducked.durationMs,
            amountDb = ducked.duckAmountDb,
            smoothness = ducked.duckSmoothness,
            triggerId = t.id,
            triggerUri = t.uri,
            triggerStartMs = t.startMs,
            triggerDurationMs = t.durationMs,
            triggerTrimStartMs = t.trimStartMs,
            triggerSpeed = t.speed,
            triggerSpeedKeys = t.keyframes[AnimProp.SPEED],
            triggerVolume = trigger.volume,
            triggerFadeInMs = trigger.fadeInMs,
            triggerFadeOutMs = trigger.fadeOutMs
        )
    }

    /**
     * Computes [ducked]'s curve under this plan (decodes the trigger), or
     * null when nothing should duck. Suspending: the decode runs on
     * [AudioBeatDecode]'s own dispatcher.
     */
    suspend fun compute(
        context: Context,
        picks: List<ExportPlanNative.AudioChoice>,
        ducked: TimelineClip
    ): Curve? {
        val trigger = triggerPick(picks, ducked) ?: return null
        val t = trigger.clip
        val uri = t.uri ?: return null
        val spanMs = t.sourceOffsetMs(t.durationMs)
        if (spanMs <= 0L || spanMs > AudioBeatDecode.MAX_SPAN_MS) return null
        val pcm = try {
            AudioBeatDecode.decodeWindow(context, uri, t.trimStartMs, spanMs)
        } catch (e: AudioBeatDecode.Stopped) {
            return null
        } catch (e: Exception) {
            return null
        }
        val levels = Ducking.timelineLevels(t, pcm.samples, pcm.sampleRate) { relMs ->
            trigger.volume * t.fadeGainAt(relMs)
        }
        val points = Ducking.curve(t.startMs, levels, ducked, ducked.duckAmountDb, ducked.duckSmoothness)
            ?: return null
        return Curve(
            LongArray(points.size) { points[it].relMs * 1000L },
            FloatArray(points.size) { points[it].gain }
        )
    }
}
