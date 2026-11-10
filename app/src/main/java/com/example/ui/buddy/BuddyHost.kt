package com.example.ui.buddy

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Where Buddy's centre is on the screen (window px) and where the finger last was: Buddy looks toward the finger. */
private object BuddyPointer {
    var centerX = 0f
    var centerY = 0f
}

/**
 * Put on the ROOT of the screen: every touch (seen first, never consumed) wakes Buddy and turns its eyes toward the finger.
 */
fun Modifier.buddyGaze(): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin = it.positionInWindow() }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    BuddyBrain.touch()
                    val c = ev.changes.firstOrNull() ?: continue
                    val dx = origin.x + c.position.x - BuddyPointer.centerX
                    val dy = origin.y + c.position.y - BuddyPointer.centerY
                    BuddyBrain.gazeX = (dx / 500f).coerceIn(-1f, 1f)
                    BuddyBrain.gazeY = (dy / 500f).coerceIn(-1f, 1f)
                }
            }
        }
}

/**
 * The floating Buddy. Tap = poke, drag = move (remembered), long press = menu (sound / hide). A speech bubble shows what it
 * says. Put it above the editor, inside the same Box (see MainActivity). When hidden only a small tab stays at the edge.
 */
@Composable
fun BuddyHost(modifier: Modifier = Modifier, buddySize: androidx.compose.ui.unit.Dp = 60.dp) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        BuddyBrain.init(ctx)
        BuddyBrain.react(BuddyEvent.APP_START)
        while (true) {
            BuddyBrain.tick()
            delay(150)
        }
    }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val sizePx = with(density) { buddySize.toPx() }
        var fx by remember { mutableFloatStateOf(BuddyBrain.savedPos()?.first ?: 0.86f) }
        var fy by remember { mutableFloatStateOf(BuddyBrain.savedPos()?.second ?: 0.50f) }
        var menu by remember { mutableStateOf(false) }
        var bubbleW by remember { mutableFloatStateOf(0f) }

        if (!BuddyBrain.enabled) {
            // A small tab at the right edge to bring Buddy back.
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(width = 14.dp, height = 56.dp)
                    .background(Color(0x66B8F31A), RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
                    .pointerInput(Unit) { detectTapGestures { BuddyBrain.showBuddy(true); BuddyBrain.react(BuddyEvent.APP_START) } }
            )
            return@BoxWithConstraints
        }

        val x = (fx * (wPx - sizePx)).coerceIn(0f, (wPx - sizePx).coerceAtLeast(0f))
        val y = (fy * (hPx - sizePx)).coerceIn(0f, (hPx - sizePx).coerceAtLeast(0f))
        BuddyPointer.centerX = x + sizePx / 2f
        BuddyPointer.centerY = y + sizePx / 2f
        val onRight = x + sizePx / 2f > wPx / 2f

        // speech bubble (beside Buddy, toward the middle of the screen)
        val bubbleText = BuddyBrain.bubble
        AnimatedVisibility(
            visible = bubbleText != null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.offset {
                val bx = if (onRight) x - bubbleW - 8.dp.toPx() else x + sizePx + 8.dp.toPx()
                IntOffset(bx.roundToInt().coerceAtLeast(0), (y + sizePx * 0.18f).roundToInt())
            }
        ) {
            Text(
                text = bubbleText ?: "",
                color = Color(0xFF111111),
                fontSize = 12.sp,
                modifier = Modifier
                    .widthIn(max = 190.dp)
                    .onSizeChanged { bubbleW = it.width.toFloat() }
                    .background(Color.White, RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(buddySize)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { BuddyBrain.poke() }, onLongPress = { menu = true })
                }
                .pointerInput(wPx, hPx) {
                    detectDragGestures(
                        onDragEnd = { BuddyBrain.savePos(fx, fy) },
                        onDrag = { change, drag ->
                            change.consume()
                            val room = (wPx - sizePx).coerceAtLeast(1f)
                            val roomY = (hPx - sizePx).coerceAtLeast(1f)
                            fx = (fx + drag.x / room).coerceIn(0f, 1f)
                            fy = (fy + drag.y / roomY).coerceIn(0f, 1f)
                            BuddyBrain.touch()
                        }
                    )
                }
        ) {
            BuddyFace(
                mood = BuddyBrain.mood,
                modifier = Modifier.fillMaxSize(),
                gazeX = BuddyBrain.gazeX,
                gazeY = BuddyBrain.gazeY,
                hopTick = BuddyBrain.hopTick,
                talking = BuddyBrain.talking
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (BuddyBrain.soundOn) "Sound: On" else "Sound: Off") },
                    onClick = { BuddyBrain.setSound(!BuddyBrain.soundOn); menu = false }
                )
                DropdownMenuItem(
                    text = { Text("Hide Buddy") },
                    onClick = { BuddyBrain.showBuddy(false); menu = false }
                )
            }
        }
    }
}
