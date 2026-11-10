package com.example.ui.buddy

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** The little sounds Buddy makes. No sound files: each one is a few soft notes made in code ([BuddySound.render]). */
enum class BuddySfx { HELLO, POKE, GIGGLE, HAPPY, OOPS, THINK, WORK, DONE, SLEEP, WAKE, WHOOSH, DIZZY, LOOK }

object BuddySound {
    private const val RATE = 22050
    private const val MAX_LIVE = 3

    /** One note: glide from [f0] to [f1] Hz over [ms]; [amp] 0..1; [mod] 'v' = light vibrato, 'w' = wide wobble, ' ' = none. */
    private class Note(val f0: Float, val f1: Float, val ms: Int, val amp: Float, val mod: Char = ' ')

    private val table: Map<BuddySfx, List<Note>> = mapOf(
        BuddySfx.HELLO to listOf(Note(660f, 880f, 90, .9f), Note(880f, 1320f, 150, .9f)),
        BuddySfx.POKE to listOf(Note(900f, 1500f, 70, .9f)),
        BuddySfx.GIGGLE to listOf(
            Note(1000f, 1100f, 45, .7f, 'v'), Note(1250f, 1350f, 45, .7f, 'v'),
            Note(1000f, 1100f, 45, .7f, 'v'), Note(1250f, 1500f, 70, .7f, 'v')
        ),
        BuddySfx.HAPPY to listOf(Note(523f, 523f, 80, .8f), Note(659f, 659f, 80, .8f), Note(784f, 784f, 130, .8f)),
        BuddySfx.OOPS to listOf(Note(520f, 240f, 220, .8f)),
        BuddySfx.THINK to listOf(Note(440f, 520f, 90, .6f), Note(520f, 440f, 90, .6f), Note(440f, 560f, 120, .6f)),
        BuddySfx.WORK to listOf(Note(300f, 620f, 140, .5f)),
        BuddySfx.DONE to listOf(
            Note(523f, 523f, 90, .8f), Note(659f, 659f, 90, .8f), Note(784f, 784f, 90, .8f), Note(1046f, 1046f, 300, .9f)
        ),
        BuddySfx.SLEEP to listOf(Note(520f, 300f, 420, .5f)),
        BuddySfx.WAKE to listOf(Note(400f, 820f, 110, .7f)),
        BuddySfx.WHOOSH to listOf(Note(1300f, 420f, 70, .6f)),
        BuddySfx.DIZZY to listOf(Note(600f, 640f, 320, .7f, 'w')),
        BuddySfx.LOOK to listOf(Note(1200f, 1320f, 30, .4f))
    )

    @Volatile var enabled = true
    private val cache = HashMap<String, ShortArray>()
    private val live = AtomicInteger(0)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "buddy-sfx").apply { isDaemon = true } }
    private val releaser: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "buddy-sfx-release").apply { isDaemon = true } }

    /** Plays [sfx] (a [variant] 0..3 only changes the pitch of POKE). Safe to call from any thread; never throws. */
    fun play(sfx: BuddySfx, variant: Int = 0) {
        if (!enabled || live.get() >= MAX_LIVE) return
        worker.execute { runCatching { playNow(sfx, variant) } }
    }

    private fun playNow(sfx: BuddySfx, variant: Int) {
        val pitch = if (sfx == BuddySfx.POKE) 1f + (variant.coerceIn(0, 3)) * 0.12f else 1f
        val key = "${sfx.name}:$pitch"
        val data = synchronized(cache) { cache.getOrPut(key) { render(table.getValue(sfx), pitch) } }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(data, 0, data.size)
        track.setVolume(0.8f)
        live.incrementAndGet()
        track.play()
        val ms = data.size * 1000L / RATE + 120L
        releaser.schedule(Runnable { runCatching { track.release() }; live.decrementAndGet() }, ms, TimeUnit.MILLISECONDS)
    }

    /** Soft sine + a quiet 2nd harmonic, quick attack, exponential fade. Notes follow each other; the tail of one rings into the next. */
    private fun render(notes: List<Note>, pitch: Float): ShortArray {
        val tail = RATE * 40 / 1000
        var total = tail
        for (n in notes) total += n.ms * RATE / 1000
        val mix = FloatArray(total + RATE / 10)
        var start = 0
        for (n in notes) {
            val main = n.ms * RATE / 1000
            val len = main + tail
            var phase = 0.0
            val vibHz = if (n.mod == 'w') 18.0 else 9.0
            val vibDepth = if (n.mod == 'w') 90.0 else if (n.mod == 'v') 40.0 else 0.0
            for (i in 0 until len) {
                val frac = (i.toFloat() / main).coerceAtMost(1f)
                var f = (n.f0 + (n.f1 - n.f0) * frac) * pitch.toDouble()
                if (vibDepth > 0.0) f += sin(2 * PI * vibHz * i / RATE) * vibDepth
                phase += 2 * PI * f / RATE
                val wave = sin(phase) + 0.22 * sin(2 * phase)
                val attack = (i / (RATE * 0.006f)).coerceAtMost(1f)
                val env = 0.28f * n.amp * attack * 0.00286f.pow(i.toFloat() / len)
                val idx = start + i
                if (idx < mix.size) mix[idx] += (wave * env).toFloat()
            }
            start += main
        }
        return ShortArray(mix.size) { (mix[it].coerceIn(-1f, 1f) * 32000f).toInt().toShort() }
    }
}
