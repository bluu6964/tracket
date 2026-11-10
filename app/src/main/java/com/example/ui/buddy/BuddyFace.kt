package com.example.ui.buddy

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Buddy: a round white robot with a dark visor and glowing eyes, drawn with Canvas (no image files) so it can move,
 * squash, blink, look around and change its eyes for every [BuddyMood]. The numbers are the same as the browser demo.
 *
 * [gazeX] / [gazeY] -1..1 = where it looks. [hopTick]: it hops once every time this number changes. [talking]: mouth and
 * side ring flicker.
 */
@Composable
fun BuddyFace(
    mood: BuddyMood,
    modifier: Modifier = Modifier,
    gazeX: Float = 0f,
    gazeY: Float = 0f,
    hopTick: Int = 0,
    talking: Boolean = false
) {
    var t by remember { mutableFloatStateOf(0f) }
    var moodStart by remember { mutableFloatStateOf(0f) }
    var hopStart by remember { mutableFloatStateOf(-9f) }
    var sgx by remember { mutableFloatStateOf(0f) }
    var sgy by remember { mutableFloatStateOf(0f) }
    val tgx by rememberUpdatedState(gazeX)
    val tgy by rememberUpdatedState(gazeY)
    // One clock for everything; the gaze eases toward its target.
    LaunchedEffect(Unit) {
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val n = androidx.compose.runtime.withFrameNanos { it }
            t += (n - last) / 1e9f
            last = n
            sgx += (tgx - sgx) * 0.12f
            sgy += (tgy - sgy) * 0.12f
        }
    }
    LaunchedEffect(mood) { moodStart = t }
    LaunchedEffect(hopTick) { if (hopTick > 0) hopStart = t }
    Canvas(modifier) {
        val s = min(size.width, size.height)
        translate((size.width - s) / 2f, (size.height - s) / 2f) {
            drawBuddy(s, t, mood, t - moodStart, t - hopStart, sgx, sgy, talking)
        }
    }
}

private enum class EyeType { CAPSULE, CAPSULE_BIG, CAPSULE_SMALL, HALF, ARC_UP, ARC_DOWN, LINE, RING, SPIN, CROSS, HEART, STAR }

private val EyeBlue = Color(0xFF58E0FF)
private val EyeViolet = Color(0xFF9B8CFF)
private val R2D = (180.0 / PI).toFloat()

private fun eyeBrush(u: Float) = Brush.linearGradient(listOf(EyeBlue, EyeViolet), Offset(-u, -u), Offset(u, u))

private fun heartPath(r: Float) = Path().apply {
    moveTo(0f, r * 0.9f)
    cubicTo(-r * 1.5f, -r * 0.1f, -r * 0.7f, -r * 1.2f, 0f, -r * 0.4f)
    cubicTo(r * 0.7f, -r * 1.2f, r * 1.5f, -r * 0.1f, 0f, r * 0.9f)
    close()
}

private fun starPath(r: Float) = Path().apply {
    for (i in 0 until 10) {
        val a = -PI.toFloat() / 2f + i * PI.toFloat() / 5f
        val rr = if (i % 2 == 1) r * 0.45f else r
        val x = cos(a) * rr
        val y = sin(a) * rr
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** Three passes (wide faint glow, narrower glow, bright core) so the eye glows without a blur effect. */
private inline fun glowStroke(u: Float, lw: Float, draw: (Brush, Float) -> Unit) {
    draw(SolidColor(Color(0x1A78AAFF)), lw * 2.6f)
    draw(SolidColor(Color(0x3878C8FF)), lw * 1.7f)
    draw(eyeBrush(u), lw)
}

private fun DrawScope.glowRoundRect(u: Float, w: Float, h: Float) {
    val passes = listOf(Color(0x1F78AAFF) to 0.55f, Color(0x4078C8FF) to 0.26f)
    for ((c, grow) in passes) {
        val gw = w + u * grow * 2f; val gh = h + u * grow * 2f
        drawRoundRect(c, Offset(-gw / 2f, -gh / 2f), Size(gw, gh), CornerRadius(gw / 2f))
    }
    drawRoundRect(eyeBrush(u), Offset(-w / 2f, -h / 2f), Size(w, h), CornerRadius(w / 2f))
}

private fun DrawScope.drawEye(type: EyeType, u: Float, t: Float, k: Float, open: Float, side: Int, gx: Float, gy: Float) {
    val lw = u * 0.30f
    translate(gx * u * 0.9f, gy * u * 0.9f) {
        scale(k, k, Offset.Zero) {
            fun st(w: Float) = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (type) {
                EyeType.CAPSULE -> glowRoundRect(u, u * 0.62f, u * 1.7f * max(open, 0.06f))
                EyeType.CAPSULE_BIG -> glowRoundRect(u, u * 0.8f, u * 2.2f * max(open, 0.06f))
                EyeType.CAPSULE_SMALL -> glowRoundRect(u, u * 0.5f, u * 1.1f * max(open, 0.06f))
                EyeType.HALF -> translate(0f, u * 0.25f) { glowRoundRect(u, u * 0.7f, u * 0.75f) }
                EyeType.ARC_UP -> glowStroke(u, lw) { b, w ->
                    drawArc(b, 201.6f, 136.8f, false, Offset(-u, -u * 0.45f), Size(u * 2f, u * 2f), style = st(w))
                }
                EyeType.ARC_DOWN -> glowStroke(u, lw) { b, w ->
                    drawArc(b, 21.6f, 136.8f, false, Offset(-u, -u * 1.55f), Size(u * 2f, u * 2f), style = st(w))
                }
                EyeType.LINE -> glowStroke(u, lw) { b, w ->
                    drawLine(b, Offset(-u * 0.8f, 0f), Offset(u * 0.8f, 0f), w, StrokeCap.Round)
                }
                EyeType.RING -> {
                    drawCircle(Color(0x2E78AAFF), u * 0.95f, style = st(lw * 2f))
                    glowStroke(u, lw) { b, w -> drawCircle(b, u * 0.85f, style = st(w)) }
                }
                EyeType.SPIN -> {
                    val a = (t * 5.5f * R2D) + (if (side > 0) 180f else 0f)
                    drawCircle(Color(0x3378C8FF), u * 0.85f, style = st(lw * 0.55f))
                    glowStroke(u, lw) { b, w -> drawArc(b, a, 144f, false, Offset(-u * 0.85f, -u * 0.85f), Size(u * 1.7f, u * 1.7f), style = st(w)) }
                }
                EyeType.CROSS -> glowStroke(u, lw) { b, w ->
                    drawLine(b, Offset(-u * 0.8f, -u * 0.8f), Offset(u * 0.8f, u * 0.8f), w, StrokeCap.Round)
                    drawLine(b, Offset(u * 0.8f, -u * 0.8f), Offset(-u * 0.8f, u * 0.8f), w, StrokeCap.Round)
                }
                EyeType.HEART -> {
                    val p = 1f + sin(t * 9f) * 0.12f
                    scale(p, p, Offset.Zero) {
                        scale(1.5f, 1.5f, Offset.Zero) { drawPath(heartPath(u), Color(0x1F78AAFF)) }
                        scale(1.22f, 1.22f, Offset.Zero) { drawPath(heartPath(u), Color(0x4078C8FF)) }
                        drawPath(heartPath(u), eyeBrush(u))
                    }
                }
                EyeType.STAR -> rotate(sin(t * 4f) * 0.25f * R2D, Offset.Zero) {
                    scale(1.5f, 1.5f, Offset.Zero) { drawPath(starPath(u * 1.15f), Color(0x1F78AAFF)) }
                    scale(1.22f, 1.22f, Offset.Zero) { drawPath(starPath(u * 1.15f), Color(0x4078C8FF)) }
                    drawPath(starPath(u * 1.15f), eyeBrush(u))
                }
            }
        }
    }
}

private fun DrawScope.drawZ(x: Float, y: Float, sz: Float, alpha: Float) {
    val p = Path().apply { moveTo(x - sz, y - sz); lineTo(x + sz, y - sz); lineTo(x - sz, y + sz); lineTo(x + sz, y + sz) }
    drawPath(p, Color(0xFFBFD2FF).copy(alpha = alpha.coerceIn(0f, 1f)),
        style = Stroke(width = max(1.5f, sz * 0.22f), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawBuddy(s: Float, t: Float, mood: BuddyMood, moodT: Float, hopT: Float, gazeX: Float, gazeY: Float, talking: Boolean) {
    // bob amplitude (of s) and speed per mood
    val (bobAmp, bobSpeed) = when (mood) {
        BuddyMood.IDLE -> 0.018f to 1.7f; BuddyMood.HAPPY -> 0.026f to 2.6f; BuddyMood.EXCITED -> 0.04f to 4.2f
        BuddyMood.CURIOUS -> 0.016f to 1.6f; BuddyMood.THINKING -> 0.012f to 1.2f; BuddyMood.WORKING -> 0.008f to 2.0f
        BuddyMood.SURPRISED -> 0.01f to 1.5f; BuddyMood.SAD -> 0.008f to 1.0f; BuddyMood.SLEEPY -> 0.01f to 0.9f
        BuddyMood.ASLEEP -> 0.014f to 0.8f; BuddyMood.DIZZY -> 0.012f to 3f; BuddyMood.LOVE -> 0.022f to 2.2f
        BuddyMood.CELEBRATE -> 0.05f to 5.0f; BuddyMood.GROOVE -> 0.035f to 5.2f
    }
    val bob = sin(t * bobSpeed) * bobAmp * s
    var hop = 0f
    var squash = 1f
    if (hopT < 0.55f) {
        val p = hopT / 0.55f
        hop = sin(p * PI.toFloat()) * 0.12f * s
        squash = 1f + sin(p * PI.toFloat() * 2f + PI.toFloat()) * 0.06f * (1f - p)
    }
    if (mood == BuddyMood.CELEBRATE || mood == BuddyMood.EXCITED) {
        val p = (t * (if (mood == BuddyMood.EXCITED) 2.2f else 2.6f)) % 1f
        hop += abs(sin(p * PI.toFloat())) * 0.07f * s
    }
    var tilt = sin(t * 0.9f) * 0.035f
    when (mood) {
        BuddyMood.WORKING -> tilt += sin(t * 22f) * 0.012f
        BuddyMood.DIZZY -> tilt += sin(t * 6f) * 0.16f
        BuddyMood.GROOVE -> tilt += sin(t * 2.6f) * 0.09f
        BuddyMood.SLEEPY, BuddyMood.ASLEEP -> tilt += 0.10f + sin(t * 0.8f) * 0.01f
        BuddyMood.SAD -> tilt -= 0.06f
        BuddyMood.CURIOUS -> tilt += 0.07f
        else -> {}
    }
    val cx = s * 0.5f
    val cy = s * 0.47f - bob - hop

    // ground shadow: smaller when Buddy is high
    val lift = ((bob + hop) / (s * 0.12f)).coerceIn(-0.3f, 1f)
    translate(cx, s * 0.90f) {
        scale(1f - lift * 0.25f, 1f, Offset.Zero) {
            for (i in 0 until 4) {
                val rr = 1f - i * 0.2f
                drawOval(Color(0xFF6E78FF).copy(alpha = 0.05f + i * 0.022f),
                    Offset(-s * 0.30f * rr, -s * 0.032f * rr), Size(s * 0.60f * rr, s * 0.064f * rr))
            }
        }
    }

    translate(cx, cy) {
        rotate(tilt * R2D, Offset.Zero) {
            scale(1f / squash, squash, Offset.Zero) {
                val rx = s * 0.30f
                val ry = s * 0.285f
                val body = Path().apply {
                    moveTo(0f, -ry)
                    cubicTo(rx * 0.62f, -ry, rx, -ry * 0.55f, rx, -ry * 0.05f)
                    cubicTo(rx, ry * 0.5f, rx * 0.55f, ry, 0f, ry)
                    cubicTo(-rx * 0.6f, ry, -rx, ry * 0.55f, -rx, 0f)
                    cubicTo(-rx, -ry * 0.55f, -rx * 0.6f, -ry, 0f, -ry)
                    close()
                }
                // soft halo
                scale(1.14f, 1.14f, Offset.Zero) { drawPath(body, Color(0x0D96AAFF)) }
                scale(1.08f, 1.08f, Offset.Zero) { drawPath(body, Color(0x1496AAFF)) }
                scale(1.03f, 1.03f, Offset.Zero) { drawPath(body, Color(0x2296AAFF)) }
                drawPath(body, Brush.radialGradient(
                    0f to Color(0xFFFFFFFF), 0.55f to Color(0xFFF1F3FA), 1f to Color(0xFFC9D0E4),
                    center = Offset(-rx * 0.1f, -ry * 0.1f), radius = rx * 1.25f
                ))
                clipPath(body) {
                    drawRect(Brush.verticalGradient(listOf(Color(0x007896FF), Color(0x4D7896FF)), ry * 0.2f, ry),
                        Offset(-rx, -ry), Size(rx * 2f, ry * 2f))
                    drawRect(Brush.horizontalGradient(listOf(Color(0x61969CC8), Color(0x00969CC8)), -rx, -rx * 0.55f),
                        Offset(-rx, -ry), Size(rx * 2f, ry * 2f))
                }
                // panel seam
                drawPath(Path().apply { moveTo(-rx * 0.74f, -ry * 0.62f); quadraticTo(-rx * 0.9f, 0f, -rx * 0.72f, ry * 0.62f) },
                    Color(0x5996A0BE), style = Stroke(width = s * 0.004f))

                // side ring (glows, brighter while talking)
                run {
                    val px = -rx * 0.88f; val py = -ry * 0.1f; val w = s * 0.034f; val h = s * 0.088f
                    val glow = if (talking) 0.55f + 0.35f * abs(sin(t * 14f)) else 0.55f + 0.18f * sin(t * 2.2f)
                    translate(px, py) {
                        drawOval(Color(0xFF3A3F52), Offset(-w * 1.45f, -h * 1.18f), Size(w * 2.9f, h * 2.36f))
                        drawOval(Color(0xFF6EC8FF).copy(alpha = 0.18f * glow), Offset(-w * 1.7f, -h * 1.35f), Size(w * 3.4f, h * 2.7f))
                        drawOval(eyeBrush(h), Offset(-w, -h), Size(w * 2f, h * 2f), style = Stroke(s * 0.017f))
                        drawOval(Color(0xFF96D2FF).copy(alpha = 0.25f * glow), Offset(-w * 0.8f, -h * 0.8f), Size(w * 1.6f, h * 1.6f))
                    }
                }

                // visor (turned a little, like looking sideways)
                translate(rx * 0.27f, -ry * 0.2f) {
                    rotate(0.22f * R2D, Offset.Zero) {
                        val vw = rx * 1.34f
                        val vh = ry * 1.18f
                        val vr = CornerRadius(vh * 0.5f)
                        val visor = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(-vw / 2f, -vh / 2f, vw / 2f, vh / 2f, vr)) }
                        drawRoundRect(Color(0xFFD8DCEA), Offset(-vw / 2f - s * 0.006f, -vh / 2f - s * 0.006f),
                            Size(vw + s * 0.012f, vh + s * 0.012f), CornerRadius(vh * 0.53f))
                        drawPath(visor, Brush.verticalGradient(
                            0f to Color(0xFF1B1B26), 0.5f to Color(0xFF08080C), 1f to Color(0xFF000000), startY = -vh / 2f, endY = vh / 2f))
                        clipPath(visor) {
                            val u = s * 0.052f
                            val blinkP = (t * 0.8f) % 4.6f
                            var blink = 1f
                            if (blinkP < 0.16f) blink = abs(blinkP - 0.08f) / 0.08f
                            else if (blinkP > 0.38f && blinkP < 0.52f) blink = abs(blinkP - 0.45f) / 0.07f
                            val pop = (moodT / 0.18f).coerceIn(0f, 1f)
                            val k = if (pop < 1f) (1f - (1f - pop).pow(3)) + sin(pop * PI.toFloat()) * 0.25f else 1f
                            val type = when (mood) {
                                BuddyMood.IDLE -> EyeType.CAPSULE; BuddyMood.HAPPY, BuddyMood.EXCITED -> EyeType.ARC_UP
                                BuddyMood.CURIOUS -> EyeType.CAPSULE_BIG; BuddyMood.THINKING -> EyeType.CAPSULE_SMALL
                                BuddyMood.WORKING -> EyeType.SPIN; BuddyMood.SURPRISED -> EyeType.RING
                                BuddyMood.SAD -> EyeType.ARC_DOWN; BuddyMood.SLEEPY -> EyeType.HALF
                                BuddyMood.ASLEEP -> EyeType.LINE; BuddyMood.DIZZY -> EyeType.CROSS
                                BuddyMood.LOVE -> EyeType.HEART; BuddyMood.CELEBRATE -> EyeType.STAR
                                BuddyMood.GROOVE -> if (floor(t * 2.6f).toInt() % 2 == 1) EyeType.ARC_UP else EyeType.CAPSULE
                            }
                            var gx = gazeX * 0.22f
                            var gy = gazeY * 0.18f
                            if (mood == BuddyMood.THINKING) { gx = 0.35f; gy = -0.35f }
                            if (mood == BuddyMood.CURIOUS) gx = 0.25f + sin(t * 1.3f) * 0.2f
                            val open = if (mood == BuddyMood.IDLE || mood == BuddyMood.CURIOUS || mood == BuddyMood.THINKING || mood == BuddyMood.GROOVE) blink else 1f
                            val sep = vw * 0.25f
                            val ey = vh * 0.02f
                            translate(-sep, ey) { drawEye(type, u, t, k, open, -1, gx, gy) }
                            translate(sep, ey) { drawEye(type, u, t, if (mood == BuddyMood.THINKING) k * 1.25f else k, open, 1, gx, gy) }
                            if (mood == BuddyMood.THINKING) {
                                for (i in 0 until 3) {
                                    val a = 0.3f + 0.7f * max(0f, sin(t * 4f - i * 0.9f))
                                    drawCircle(Color(0xFF8CC8FF).copy(alpha = a), u * 0.22f, Offset(-u * 1.2f + i * u * 1.2f, vh * 0.32f))
                                }
                            }
                            // glass gloss
                            rotate(-0.12f * R2D, Offset(-vw * 0.08f, -vh * 0.42f)) {
                                drawOval(Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x00FFFFFF)),
                                    Offset(-vw * 0.5f, -vh * 0.6f), Offset(vw * 0.1f, -vh * 0.2f)),
                                    Offset(-vw * 0.08f - vw * 0.5f, -vh * 0.42f - vh * 0.26f), Size(vw, vh * 0.52f))
                            }
                        }
                        drawCircle(Color(0x8CFFFFFF), s * 0.0065f, Offset(vw * 0.40f, -vh * 0.18f))
                    }
                }

                // mouth glow (a little pill); it jumps while talking
                run {
                    val mw = s * 0.075f * (if (talking) 0.8f + 0.5f * abs(sin(t * 16f)) else 1f)
                    val mh = s * 0.022f
                    val gl = 0.55f + 0.25f * sin(t * 2.4f)
                    translate(-rx * 0.14f, ry * 0.78f) {
                        rotate(0.05f * R2D, Offset.Zero) {
                            drawRoundRect(Color(0xFF8296FF).copy(alpha = 0.16f * gl), Offset(-mw * 1.5f, -mh * 2.4f), Size(mw * 3f, mh * 4.8f), CornerRadius(mh * 2.4f))
                            drawRoundRect(Color(0xFF8296FF).copy(alpha = 0.25f * gl), Offset(-mw * 1.2f, -mh * 1.6f), Size(mw * 2.4f, mh * 3.2f), CornerRadius(mh * 1.6f))
                            drawRoundRect(Brush.horizontalGradient(listOf(EyeBlue, EyeViolet), -mw, mw), Offset(-mw, -mh), Size(mw * 2f, mh * 2f), CornerRadius(mh))
                        }
                    }
                }
            }
        }
    }

    // extras around the body
    when (mood) {
        BuddyMood.ASLEEP -> for (i in 0 until 3) {
            val p = (t * 0.5f + i / 3f) % 1f
            drawZ(cx + s * 0.20f + p * s * 0.10f, cy - s * 0.22f - p * s * 0.20f, s * (0.012f + i * 0.006f) * (0.6f + p), sin(p * PI.toFloat()) * 0.9f)
        }
        BuddyMood.CELEBRATE -> {
            val cols = listOf(Color(0xFFB8F31A), Color(0xFF58E0FF), Color(0xFFFF7AD9), Color(0xFFFFD166), Color(0xFF9B8CFF))
            for (i in 0 until 22) {
                val ph = (moodT * 0.9f + i * 0.137f) % 1f
                val a = i * 2.399f
                val rr = s * (0.18f + 0.34f * ph) * (0.7f + 0.3f * ((i * 7) % 5) / 5f)
                val x = cx + cos(a) * rr
                val y = cy - s * 0.05f + sin(a) * rr * 0.8f + ph * ph * s * 0.22f
                translate(x, y) {
                    rotate((ph * 9f + i) * R2D, Offset.Zero) {
                        drawRect(cols[i % 5].copy(alpha = 1f - ph), Offset(-s * 0.011f, -s * 0.006f), Size(s * 0.022f, s * 0.012f))
                    }
                }
            }
        }
        BuddyMood.LOVE -> for (i in 0 until 3) {
            val p = (t * 0.45f + i / 3f) % 1f
            translate(cx + (i - 1) * s * 0.14f, cy - s * 0.30f - p * s * 0.2f) {
                drawPath(heartPath(s * 0.022f), Color(0xFFFF7AB6).copy(alpha = sin(p * PI.toFloat()).coerceIn(0f, 1f)))
            }
        }
        BuddyMood.SURPRISED -> {
            val sh = sin(t * 20f) * 0.01f * s
            drawLine(Color(0xFFFFD166), Offset(cx + s * 0.30f + sh, cy - s * 0.28f), Offset(cx + s * 0.30f + sh, cy - s * 0.20f), s * 0.012f, StrokeCap.Round)
            drawCircle(Color(0xFFFFD166), s * 0.007f, Offset(cx + s * 0.30f + sh, cy - s * 0.16f))
        }
        BuddyMood.SAD -> {
            val p = (t * 0.7f) % 1f
            drawOval(Color(0xFF78BEFF).copy(alpha = 0.8f * (1f - p)),
                Offset(cx + s * 0.26f - s * 0.008f, cy - s * 0.12f + p * s * 0.16f - s * 0.013f), Size(s * 0.016f, s * 0.026f))
        }
        else -> {}
    }
}
