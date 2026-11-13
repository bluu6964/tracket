package com.example.ui.buddy

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

/** How Buddy looks / acts. Drawn by [BuddyFace]. */
enum class BuddyMood { IDLE, HAPPY, EXCITED, CURIOUS, THINKING, WORKING, SURPRISED, SAD, SLEEPY, ASLEEP, DIZZY, LOVE, CELEBRATE, GROOVE }

/** Things that happen in the editor that Buddy reacts to. */
enum class BuddyEvent { APP_START, CLIP_ADDED, CLIP_REMOVED, LAYER_SELECTED, UNDO, REDO, SPLIT, EXPORT_DONE, EXPORT_FAIL, TASK_DONE, SAVED }

/**
 * Buddy's mind: one place that decides the mood, the sound and the speech bubble.
 *  - A short reaction ([react]) wins over everything for its time (a "transient" mood).
 *  - Otherwise: a long job running ([setBusy]) -> working; video playing ([setPlaying]) -> groove; else idle, and after a
 *    while of nobody touching the screen: sleepy, then asleep (any touch wakes it).
 * The editor calls [react] / [setBusy] / [setPlaying]; [BuddyHost] calls [tick] a few times a second.
 */
object BuddyBrain {
    var mood by mutableStateOf(BuddyMood.IDLE); private set
    var bubble by mutableStateOf<String?>(null); private set
    /** Goes up on every poke / big reaction: [BuddyFace] hops once per change. */
    var hopTick by mutableIntStateOf(0); private set
    var talking by mutableStateOf(false); private set
    var enabled by mutableStateOf(true)
    var soundOn by mutableStateOf(true)
    /** Where Buddy looks, -1..1 (set from the finger position). */
    var gazeX by mutableFloatStateOf(0f)
    var gazeY by mutableFloatStateOf(0f)

    private var prefs: android.content.SharedPreferences? = null
    private var transient: BuddyMood? = null
    private var transientUntil = 0L
    private var busy = false
    private var playing = false
    private var lastActive = 0L
    private var bubbleUntil = 0L
    private var talkUntil = 0L
    private var lastSfxAt = 0L
    private var lastTipAt = 0L
    private var wasAsleep = false
    /** Split / undo / redo also change the number of clips: that must not become a second "Nice!" right after. */
    private var quietCountUntil = 0L
    private val pokes = ArrayDeque<Long>()
    private var tipIndex = 0

    private val tips = listOf(
        "Tip: tap a layer to see its tools",
        "Tip: drag me anywhere!",
        "Tip: long-press me for sound options",
        "Tip: a Null layer can move many layers at once",
        "Tip: use a 3D Camera for fly-through shots",
        "Tip: draw a vector shape with Add, Draw"
    )

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("buddy", Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean("enabled", true)
        soundOn = p.getBoolean("sound", true)
        lastActive = now()
        BuddySound.enabled = soundOn
    }

    fun showBuddy(on: Boolean) { enabled = on; prefs?.edit()?.putBoolean("enabled", on)?.apply(); if (on) touch() }
    fun setSound(on: Boolean) {
        soundOn = on; BuddySound.enabled = on
        prefs?.edit()?.putBoolean("sound", on)?.apply()
        if (on) BuddySound.play(BuddySfx.HAPPY)
    }

    /** Saved Buddy position as fractions of the screen (null = never moved). */
    fun savedPos(): Pair<Float, Float>? {
        val p = prefs ?: return null
        if (!p.contains("px")) return null
        return p.getFloat("px", 0.86f) to p.getFloat("py", 0.5f)
    }
    fun savePos(x: Float, y: Float) { prefs?.edit()?.putFloat("px", x)?.putFloat("py", y)?.apply() }

    private fun now() = SystemClock.uptimeMillis()

    /** Any touch on the screen: Buddy stays awake. */
    fun touch() { lastActive = now() }

    /** True while the user's video plays: Buddy dances quietly and makes no sounds (it must not disturb the audio). */
    fun setPlaying(on: Boolean) { playing = on; touch() }

    /** A long job (export, cut-out, tracking, captions...) is running. */
    fun setBusy(on: Boolean, text: String? = null) {
        if (on && !busy) { go(BuddyMood.WORKING, 0, BuddySfx.WORK, text) }
        busy = on
        touch()
    }

    fun react(e: BuddyEvent) {
        touch()
        when (e) {
            BuddyEvent.APP_START -> go(BuddyMood.HAPPY, 2600, BuddySfx.HELLO, "Hi! Let's make something cool")
            BuddyEvent.CLIP_ADDED -> if (now() >= quietCountUntil) go(BuddyMood.HAPPY, 2000, BuddySfx.HAPPY, pick("Nice!", "Looks good!", "Fresh footage!"))
            BuddyEvent.CLIP_REMOVED -> if (now() >= quietCountUntil) go(BuddyMood.SURPRISED, 1400, BuddySfx.OOPS, "Gone!")
            BuddyEvent.LAYER_SELECTED -> go(BuddyMood.CURIOUS, 1300, BuddySfx.LOOK, null, minGapMs = 700)
            BuddyEvent.UNDO -> { quietCountUntil = now() + 900; go(BuddyMood.CURIOUS, 1200, BuddySfx.WHOOSH, "Rewind!") }
            BuddyEvent.REDO -> { quietCountUntil = now() + 900; go(BuddyMood.CURIOUS, 1200, BuddySfx.WHOOSH, "Again!") }
            BuddyEvent.SPLIT -> { quietCountUntil = now() + 900; go(BuddyMood.EXCITED, 1100, BuddySfx.POKE, "Snip!", hop = true) }
            BuddyEvent.EXPORT_DONE -> { busy = false; go(BuddyMood.CELEBRATE, 4500, BuddySfx.DONE, "Your video is ready!", hop = true, force = true) }
            BuddyEvent.EXPORT_FAIL -> { busy = false; go(BuddyMood.SAD, 3200, BuddySfx.OOPS, "Something went wrong…") }
            BuddyEvent.TASK_DONE -> { busy = false; go(BuddyMood.HAPPY, 2200, BuddySfx.HAPPY, "All done!") }
            BuddyEvent.SAVED -> go(BuddyMood.HAPPY, 1500, BuddySfx.HAPPY, "Saved!")
        }
    }

    /** Buddy was tapped. */
    fun poke() {
        val t = now()
        lastActive = t
        while (pokes.isNotEmpty() && t - pokes.first() > 1600) pokes.removeFirst()
        pokes.addLast(t)
        when {
            mood == BuddyMood.ASLEEP || mood == BuddyMood.SLEEPY ->
                go(BuddyMood.SURPRISED, 1200, BuddySfx.WAKE, "Oh! Hi!", hop = true, force = true)
            pokes.size >= 5 -> { pokes.clear(); go(BuddyMood.DIZZY, 2400, BuddySfx.DIZZY, "Whoa… dizzy!", force = true) }
            Random.nextBoolean() -> go(BuddyMood.HAPPY, 1600, BuddySfx.GIGGLE, pick("Hehe!", "Tickles!", "Again!"), hop = true, force = true)
            else -> go(BuddyMood.EXCITED, 1100, BuddySfx.POKE, null, hop = true, force = true, variant = Random.nextInt(4))
        }
    }

    /** Call a few times a second. */
    fun tick() {
        val t = now()
        if (transient != null && t >= transientUntil) transient = null
        val idleFor = t - lastActive
        val next = transient
            ?: if (busy) BuddyMood.WORKING
            else if (playing) BuddyMood.GROOVE
            else if (idleFor > 35_000) BuddyMood.ASLEEP
            else if (idleFor > 20_000) BuddyMood.SLEEPY
            else BuddyMood.IDLE
        if (next != mood) {
            if (next == BuddyMood.ASLEEP && !wasAsleep) playSfx(BuddySfx.SLEEP, 0, false)
            mood = next
        }
        if (mood == BuddyMood.ASLEEP) wasAsleep = true
        else if (wasAsleep) { wasAsleep = false; playSfx(BuddySfx.WAKE, 0, false) }
        talking = t < talkUntil
        if (bubble != null && t >= bubbleUntil) bubble = null
        // A tip once in a while, only when nothing else is going on.
        if (mood == BuddyMood.IDLE && bubble == null && idleFor in 9_000L..14_000L && t - lastTipAt > 50_000) {
            lastTipAt = t
            say(tips[tipIndex % tips.size], 3500)
            tipIndex++
            playSfx(BuddySfx.THINK, 0, false)
        }
    }

    private fun say(text: String, ms: Long) { bubble = text; bubbleUntil = now() + ms; talkUntil = now() + minOf(ms, 900L) }

    private fun go(m: BuddyMood, ms: Long, sfx: BuddySfx?, text: String?, hop: Boolean = false, force: Boolean = false,
                   variant: Int = 0, minGapMs: Long = 120) {
        val t = now()
        if (ms > 0) { transient = m; transientUntil = t + ms }
        mood = transient ?: mood
        if (hop) hopTick++
        if (text != null) say(text, if (text.length > 24) 3200 else 2200)
        if (sfx != null) playSfx(sfx, variant, force, minGapMs)
    }

    private fun playSfx(sfx: BuddySfx, variant: Int, force: Boolean, minGapMs: Long = 120) {
        if (!enabled || !soundOn) return
        if (playing && !force) return  // never over the user's video
        val t = now()
        if (!force && t - lastSfxAt < minGapMs) return
        lastSfxAt = t
        BuddySound.play(sfx, variant)
    }

    private fun pick(vararg s: String) = s[Random.nextInt(s.size)]
}
