package com.example.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.model.TimelineTrack
import com.example.model.exportSpeed
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

/** Raw JNI functions of libam_engine.so (cpp/engine/audio_preview_jni.cpp). Use [NativeAudioPreview]. */
internal object AudioPreviewNative {
    @JvmStatic external fun nativeAvailable(): Boolean
    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeRelease(p: Long)
    @JvmStatic external fun nativeClearClips(p: Long)
    @JvmStatic external fun nativeAddClip(
        p: Long, fd: Int, length: Long, startUs: Long, durUs: Long, trimStartUs: Long, speed: Double,
        volume: Float, fadeInUs: Long, fadeOutUs: Long, duckRelUs: LongArray?, duckGains: FloatArray?,
        noiseProfile: FloatArray?, nrStrength: Float
    ): Boolean

    @JvmStatic external fun nativeStart(p: Long, positionUs: Long): Boolean
    @JvmStatic external fun nativeStop(p: Long)
    @JvmStatic external fun nativePositionUs(p: Long): Long
    @JvmStatic external fun nativeHealthy(p: Long): Boolean
    @JvmStatic external fun nativeUnderruns(p: Long): Long
}

/**
 * The editor's sound, played by the C++ engine (AAudio) instead of Media3 players: audio layers and the sound of video
 * layers are mixed by the SAME mixer as the export (trim, speed with the pitch kept, volume, fades; which clips make
 * sound is decided by [ExportPlanNative.pickAudio] too), so what you hear is what the exported file will have.
 *
 * The editor playhead is the clock. [sync] is called ~10x/s while playing and on every playhead change otherwise:
 *  - playing: when the sound is not running yet, or what should be heard changed (volume, mute, a layer moved...), or it
 *    drifted more than [DRIFT_US] from the playhead, the sound is started again at the playhead (on a worker thread, the
 *    decoders take a few hundred ms to open; the engine drops the part that is already late, so it lines up).
 *  - paused / scrubbing: silent (like the Media3 preview was).
 *
 * Speed keyframes: like the export, a layer with a speed ramp is played at its average speed.
 *
 * Ducking (build 194): a ducked layer's gain curve is decoded from its trigger off the sync
 * path and cached by the exact fields it depends on ([DuckCurve.Key]); until the curve lands
 * the layer plays unducked, and the curve's arrival changes the sound signature, so the next
 * sync restarts the sound with the ducking in — the same restart any other sound change gets.
 *
 * Noise reduction (build 195): a processed layer's noise profile is estimated from its own
 * sound off the sync path and cached by the exact window it depends on ([NoiseProfile.Key]);
 * until the profile lands the layer plays UNPROCESSED, and the profile's arrival changes the
 * sound signature, so the next sync restarts the sound with the gate in — the same restart
 * any other sound change gets. The export computes the profile during setup, so a finished
 * file is always cleaned when the profile could be computed at all.
 */
internal class NativeAudioPreview(context: Context) {
    private val appCtx = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "am-audio-preview").apply { isDaemon = true } }
    private val lock = ReentrantLock()          // the native handle: released only when no read is going on
    private var ptr: Long = 0L
    private var released = false

    // Written / read on the caller's thread only (sync / stop / release).
    private var wantedSig: List<Any?>? = null   // what the running sound was started for
    private var running = false                 // a start was requested and not stopped since
    private var startedAtMs = 0L                // System.nanoTime based, of the last start request
    private val busy = AtomicBoolean(false)     // a start / stop job is queued or running

    // Duck curves (build 194): computed on their own thread, read on the caller's thread.
    private val duckWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "am-duck").apply { isDaemon = true } }
    private val duckCache = ConcurrentHashMap<DuckCurve.Key, DuckCurve.Curve>()
    private val duckInFlight: MutableSet<DuckCurve.Key> = Collections.newSetFromMap(ConcurrentHashMap())

    /** Cache marker: this key was computed and has NO curve (silent / unresolvable trigger). */
    private val noCurve = DuckCurve.Curve(LongArray(0), FloatArray(0))

    // Noise profiles (build 195): computed on their own thread, read on the caller's thread.
    private val noiseWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "am-noise").apply { isDaemon = true } }
    private val nrCache = ConcurrentHashMap<NoiseProfile.Key, FloatArray>()
    private val nrInFlight: MutableSet<NoiseProfile.Key> = Collections.newSetFromMap(ConcurrentHashMap())

    /** Cache marker: this key was computed and has NO profile (no sound / over-long span). */
    private val noProfile = FloatArray(0)

    companion object {
        private const val TAG = "AmAudioPreview"
        private const val DRIFT_US = 250_000L
        private const val MIN_RESTART_GAP_MS = 1_500L

        /** False when the library is missing or the phone has no AAudio (Android 8+): there is no sound (the Media3 players were removed in v159). */
        val isAvailable: Boolean by lazy {
            try {
                DecoderPool.isAvailable && AudioPreviewNative.nativeAvailable()
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun handle(): Long {
        if (ptr == 0L && !released) ptr = AudioPreviewNative.nativeCreate()
        return ptr
    }

    /**
     * @param playing true while the playhead runs
     * @param masterMuted the master mute button
     */
    fun sync(tracks: List<TimelineTrack>, playheadMs: Long, playing: Boolean, masterMuted: Boolean) {
        if (released) return
        if (!playing || masterMuted) {
            if (running) stop()
            return
        }
        val endMs = tracks.maxOfOrNull { t -> t.clips.maxOfOrNull { it.endMs } ?: 0L } ?: 0L
        val picks = ExportPlanNative.pickAudio(tracks, endMs)
        // Ducking (build 194): each pick's curve (or null), aligned with `picks`. A curve that
        // is not computed yet is scheduled; the sound starts unducked and restarts ducked when
        // the curve lands, because the signature below carries the curve's version.
        val duckCurves: List<DuckCurve.Curve?> = picks.map { pick ->
            val key = DuckCurve.keyFor(picks, pick.clip) ?: return@map null
            val cached = duckCache[key]
            if (cached != null) return@map if (cached === noCurve) null else cached
            if (duckInFlight.add(key)) {
                val duckedClip = pick.clip
                duckWorker.execute {
                    try {
                        val curve = runBlocking { DuckCurve.compute(appCtx, picks, duckedClip) }
                        if (duckCache.size > 64) duckCache.clear()
                        duckCache[key] = curve ?: noCurve
                    } catch (t: Throwable) {
                        Log.w(TAG, "duck curve computation failed", t)
                        duckCache[key] = noCurve
                    } finally {
                        duckInFlight.remove(key)
                    }
                }
            }
            null
        }
        // Noise reduction (build 195): each pick's noise profile (or null), aligned with
        // `picks`. A profile that is not computed yet is scheduled; the sound starts
        // unprocessed and restarts gated when the profile lands, because the signature
        // below carries the profile's hash.
        val nrProfiles: List<FloatArray?> = picks.map { pick ->
            val key = NoiseProfile.keyFor(pick.clip) ?: return@map null
            val cached = nrCache[key]
            if (cached != null) return@map if (cached === noProfile) null else cached
            if (nrInFlight.add(key)) {
                val clip = pick.clip
                noiseWorker.execute {
                    try {
                        val profile = runBlocking { NoiseProfile.compute(appCtx, clip) }
                        if (nrCache.size > 64) nrCache.clear()
                        nrCache[key] = profile ?: noProfile
                    } catch (t: Throwable) {
                        Log.w(TAG, "noise profile computation failed", t)
                        nrCache[key] = noProfile
                    } finally {
                        nrInFlight.remove(key)
                    }
                }
            }
            null
        }
        // Everything that changes how it sounds, in one comparable value.
        val sig: List<Any?> = picks.mapIndexed { index, it ->
            val c = it.clip
            listOf(c.id, c.uri, c.startMs, c.durationMs, c.trimStartMs, c.exportSpeed, it.volume, it.fadeInMs, it.fadeOutMs,
                duckCurves[index]?.version, nrProfiles[index]?.contentHashCode(), if (nrProfiles[index] != null) c.nrStrength else 0f)
        }
        if (picks.isEmpty()) {
            if (running) stop()
            wantedSig = sig
            return
        }
        val now = System.nanoTime() / 1_000_000L
        val sameSound = sig == wantedSig
        var restart = !running || !sameSound
        if (!restart && !busy.get()) {
            // Drift check: where the playing sound is vs the playhead.
            // Never wait for the worker here (it holds the lock while a start is opening the decoders).
            var posUs = -1L
            var broken = false
            if (lock.tryLock()) {
                try {
                    if (ptr != 0L) {
                        posUs = AudioPreviewNative.nativePositionUs(ptr)
                        broken = !AudioPreviewNative.nativeHealthy(ptr)
                    }
                } finally {
                    lock.unlock()
                }
            }
            if (broken) restart = true
            else if (posUs >= 0 && abs(posUs / 1000L - playheadMs) * 1000L > DRIFT_US && now - startedAtMs > MIN_RESTART_GAP_MS) restart = true
        }
        if (!restart) return
        if (busy.get()) return              // the previous start is still opening: the next sync looks again
        wantedSig = sig
        running = true
        startedAtMs = now
        busy.set(true)
        val requestedAtNs = System.nanoTime()
        worker.execute {
            try {
                if (released) return@execute
                lock.withLock {
                    val p = handle()
                    if (p == 0L) return@withLock
                    AudioPreviewNative.nativeClearClips(p)
                    var added = 0
                    for ((index, a) in picks.withIndex()) {
                        val c = a.clip
                        val uri = Uri.parse(c.uri ?: continue)
                        val pfd = try { appCtx.contentResolver.openFileDescriptor(uri, "r") } catch (_: Exception) { null } ?: continue
                        pfd.use {
                            val duck = duckCurves[index]
                            val profile = nrProfiles[index]
                            if (AudioPreviewNative.nativeAddClip(
                                    p, it.fd, it.statSize, c.startMs * 1000L, c.durationMs * 1000L, c.trimStartMs * 1000L,
                                    c.exportSpeed.toDouble(), a.volume, a.fadeInMs * 1000L, a.fadeOutMs * 1000L,
                                    duck?.relUs, duck?.gains,
                                    profile, if (profile != null) c.nrStrength else 0f
                                )) added++
                        }
                    }
                    if (added == 0) return@withLock
                    // The playhead has moved on while this job waited in the queue.
                    val lateMs = (System.nanoTime() - requestedAtNs) / 1_000_000L
                    if (!AudioPreviewNative.nativeStart(p, (playheadMs + lateMs) * 1000L)) {
                        Log.w(TAG, "native audio could not start")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "native audio start failed", t)
            } finally {
                busy.set(false)
            }
        }
    }

    /** Silence now (pause, mute, end of the project). */
    fun stop() {
        running = false
        wantedSig = null
        worker.execute {
            try {
                lock.withLock { if (ptr != 0L) AudioPreviewNative.nativeStop(ptr) }
            } catch (t: Throwable) {
                Log.w(TAG, "native audio stop failed", t)
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        running = false
        duckWorker.shutdown()
        noiseWorker.shutdown()
        worker.execute {
            try {
                lock.withLock {
                    if (ptr != 0L) {
                        AudioPreviewNative.nativeRelease(ptr)
                        ptr = 0L
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "native audio release failed", t)
            }
        }
        worker.shutdown()
    }
}
