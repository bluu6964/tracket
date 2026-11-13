package com.example.ui.components

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.example.engine.CutoutEngine
import com.example.engine.CutoutVideo
import com.example.engine.EdgeSamEngine
import com.example.engine.MaskCleanup
import com.example.engine.RotoBrushVideo
import com.example.engine.RotoFrames
import com.example.model.TimelineClip
import com.example.model.exportSpeed
import com.example.ui.theme.NeonCyan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Roto Brush (After Effects style), powered by EdgeSAM:
 *  1. scrub to a frame, paint over the object with the green brush (and over wrong areas with the red brush);
 *  2. the object is outlined at once (cyan);
 *  3. "Propagate" carries it through the whole clip; scrub to a frame that went wrong, brush again, propagate again.
 * "Apply" puts the result on the layer as its cutout (same masks as the person cutout, so preview and export use it).
 */
@Composable
fun RotoBrushScreen(
    clip: TimelineClip,
    startSrcMs: Long,
    onClose: () -> Unit,
    onApply: (CutoutVideo.Result) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val fromMs = clip.trimStartMs
    var spanMs = (clip.durationMs * clip.exportSpeed).toLong().coerceAtLeast(100L)
    if (clip.sourceDurationMs > 0L) spanMs = minOf(spanMs, (clip.sourceDurationMs - fromMs).coerceAtLeast(100L))
    val toMs = fromMs + spanMs
    // The mask grid the propagation will use: frames are brushed, shown and re-found on THIS grid.
    val maskFps = remember(clip.id) { RotoBrushVideo.maskFps(context, clip) }
    fun snap(ms: Long): Long =
        RotoFrames.frameMs(fromMs, RotoFrames.indexOfUnclamped(fromMs, ms, maskFps), maskFps).coerceIn(fromMs, toMs)

    var modelsOk by remember { mutableStateOf(EdgeSamEngine.isAvailable(context)) }
    var curMs by remember { mutableStateOf(startSrcMs.coerceIn(fromMs, toMs)) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var emb by remember { mutableStateOf<EdgeSamEngine.Embedding?>(null) }
    var overlay by remember { mutableStateOf<ImageBitmap?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var foreground by remember { mutableStateOf(true) }
    var downloading by remember { mutableStateOf<Float?>(null) }
    var inputMode by remember { mutableStateOf(EdgeSamEngine.inputMode(context)) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // Brush strokes per frame time (ms of the source video, snapped to the mask grid by loadFrame);
    // one stroke = a list of normalised points.
    val strokes = remember { mutableStateMapOf<Long, List<List<RotoBrushVideo.NPt>>>() }
    val live = remember { mutableStateListOf<Offset>() }
    var frameJob by remember { mutableStateOf<Job?>(null) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    // Only the newest outline request may land (a slow decoder answer must not overwrite a newer one),
    // and the last shown mask steadies the next decode's candidate choice (see showMask).
    var maskSeq by remember { mutableStateOf(0) }
    var lastMaskAlpha by remember { mutableStateOf<FloatArray?>(null) }

    val pickEncoder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { EdgeSamEngine.importModel(context, uri, true) }
                modelsOk = EdgeSamEngine.isAvailable(context)
                status = if (modelsOk) "Models ready" else "Encoder saved. Now choose the decoder."
            } catch (e: Throwable) {
                status = "Import failed: " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }
    val pickDecoder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { EdgeSamEngine.importModel(context, uri, false) }
                modelsOk = EdgeSamEngine.isAvailable(context)
                status = if (modelsOk) "Models ready" else "Decoder saved. Now choose the encoder."
            } catch (e: Throwable) {
                status = "Import failed: " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // Outline of the object for the brush of the current frame (fast: only the decoder runs).
    val showMask: () -> Unit = {
        val bmp = frame
        val e = emb
        val list = strokes[curMs].orEmpty().flatten()
        val mySeq = ++maskSeq // every call invalidates the previous in-flight answer, even a clearing one
        if (bmp == null || e == null || list.isEmpty()) {
            overlay = null
            lastMaskAlpha = null
        } else {
            scope.launch {
                try {
                    val k = min(1f, RotoBrushVideo.MASK_SIDE / max(bmp.width, bmp.height).toFloat())
                    val ow = (bmp.width * k).toInt().coerceAtLeast(16)
                    val oh = (bmp.height * k).toInt().coerceAtLeast(16)
                    val prefer = lastMaskAlpha
                    val decoded = withContext(Dispatchers.Default) {
                        val pts = list.map { EdgeSamEngine.Pt(it.x * bmp.width, it.y * bmp.height, it.positive) }
                        // `prefer` keeps the candidate closest to the mask already on screen: without it
                        // a refinement stroke can flip the pick to a near-duplicate (or the whole-picture)
                        // candidate and the outline visibly jumps to a different region.
                        val m = EdgeSamEngine.decode(context, e, pts, null, null, prefer, ow, oh)
                        // Show exactly what propagation will save (same small-defect cleanup).
                        val shown = MaskCleanup.clean(m.alpha, ow, oh)
                        val px = IntArray(ow * oh)
                        for (i in px.indices) {
                            val a = (shown[i] * 120f).toInt().coerceIn(0, 255)
                            px[i] = (a shl 24) or 0x0000E5FF
                        }
                        val o = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
                        o.setPixels(px, 0, ow, 0, 0, ow, oh)
                        Pair(m.alpha, o.asImageBitmap())
                    }
                    if (mySeq != maskSeq) return@launch // a newer brush already asked; drop this answer
                    lastMaskAlpha = decoded.first
                    overlay = decoded.second
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Throwable) {
                    if (mySeq == maskSeq) status = "Brush failed: " + (ex.message ?: ex.javaClass.simpleName)
                }
            }
        }
    }

    // Loads the picture at [ms], runs the (slow) encoder on it once, then shows the outline if it has a brush.
    // The time is snapped to the mask grid first: strokes live under grid times, so any way of arriving
    // at a frame (slider, step buttons, reopening) lands on the same key and finds its strokes again.
    // An unsnapped slider time made brushed frames practically unfindable — the strokes were still
    // there, but the screen only ever shows the strokes of the exact current millisecond.
    val loadFrame: (Long) -> Unit = { ms ->
        frameJob?.cancel()
        curMs = snap(ms)
        overlay = null
        lastMaskAlpha = null
        maskSeq++
        val target = curMs
        frameJob = scope.launch {
            busy = if (modelsOk) "Analysing frame…" else null
            try {
                val bmp = withContext(Dispatchers.IO) { CutoutEngine.loadVideoFrame(context, clip.uri!!.toUri(), target) }
                if (bmp == null) {
                    status = "Could not read this frame"
                } else {
                    frame = bmp
                    emb = null
                    if (modelsOk) {
                        emb = withContext(Dispatchers.Default) { EdgeSamEngine.encode(context, bmp) }
                        showMask()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                status = "Failed: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = null
            }
        }
    }

    LaunchedEffect(Unit) { loadFrame(curMs) }
    // When the models arrive while a frame is already shown, analyse it.
    LaunchedEffect(modelsOk) { if (modelsOk && emb == null && frame != null) loadFrame(curMs) }
    DisposableEffect(Unit) { onDispose { frameJob?.cancel(); runJob?.cancel() } }

    // Where the picture sits inside the touch area (Fit).
    fun fit(): FloatArray {
        val bmp = frame ?: return floatArrayOf(0f, 0f, 1f, 1f)
        val s = min(boxSize.width / bmp.width.toFloat(), boxSize.height / bmp.height.toFloat())
        val w = bmp.width * s
        val h = bmp.height * s
        return floatArrayOf((boxSize.width - w) / 2f, (boxSize.height - h) / 2f, w, h)
    }

    fun addStroke(screen: List<Offset>) {
        if (screen.isEmpty()) return
        if (emb == null) {
            // Never swallow a brush silently: before this, strokes painted while the frame was still
            // being analysed (or after the analysis had failed) simply vanished without a word.
            status = if (modelsOk) "Still analysing this frame — wait a moment, then brush"
            else "Download the EdgeSAM models first (below)"
            return
        }
        val f = fit()
        val pts = screen.map {
            RotoBrushVideo.NPt(((it.x - f[0]) / f[2]).coerceIn(0f, 1f), ((it.y - f[1]) / f[3]).coerceIn(0f, 1f), foreground)
        }
        strokes[curMs] = strokes[curMs].orEmpty() + listOf(pts)
        showMask()
    }

    val totalStrokes = strokes.values.sumOf { it.size }

    Dialog(
        onDismissRequest = { if (runJob == null) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0B0F))
                .systemBarsPadding()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Roto Brush", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { runJob?.cancel(); onClose() }) { Text("Close", color = Color.White) }
            }

            // Picture + outline + brush
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { boxSize = it }
            ) {
                val bmp = frame
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    overlay?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(modelsOk) {
                            detectTapGestures(onTap = { o -> if (runJob == null) addStroke(listOf(o)) })
                        }
                        .pointerInput(modelsOk) {
                            detectDragGestures(
                                onDragStart = { o -> live.clear(); live.add(o) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val last = live.lastOrNull()
                                    val p = change.position
                                    // A point about every 28 px of travel (about the width of the brush).
                                    if (last == null || hypot(p.x - last.x, p.y - last.y) >= 28f) live.add(p)
                                },
                                onDragEnd = {
                                    val s = live.toList()
                                    live.clear()
                                    if (runJob == null) addStroke(s)
                                },
                                onDragCancel = { live.clear() }
                            )
                        }
                ) {
                    val f = fit()
                    for (stroke in strokes[curMs].orEmpty()) for (p in stroke) {
                        drawCircle(
                            color = if (p.positive) Color(0xFF39E26B) else Color(0xFFFF4D4D),
                            radius = 7f,
                            center = Offset(f[0] + p.x * f[2], f[1] + p.y * f[3])
                        )
                    }
                    for (o in live) drawCircle(
                        color = if (foreground) Color(0xFF39E26B) else Color(0xFFFF4D4D),
                        radius = 14f,
                        center = o,
                        alpha = 0.55f
                    )
                }
                busy?.let {
                    Text(
                        it, color = Color.White, fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(8.dp)
                            .background(Color(0xAA000000))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            if (!modelsOk) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        "EdgeSAM model is not on this phone yet. Download it once (works offline after that). If the download fails, choose the two ONNX files by hand.",
                        color = Color(0xFFFFC857), fontSize = 13.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        enabled = downloading == null,
                        onClick = {
                            downloading = 0f
                            status = "Downloading EdgeSAM (about 38 MB)…"
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { EdgeSamEngine.download(context) { p -> downloading = p } }
                                    modelsOk = EdgeSamEngine.isAvailable(context)
                                    status = if (modelsOk) "Models ready" else "Download finished but a file is missing"
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    status = "Download failed: " + (e.message ?: e.javaClass.simpleName)
                                } finally {
                                    downloading = null
                                }
                            }
                        }
                    ) { Text(if (downloading != null) "Downloading… " + ((downloading ?: 0f) * 100).toInt() + "%" else "Download models (38 MB)") }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickEncoder.launch(arrayOf("*/*")) }) {
                            Text(if (EdgeSamEngine.hasEncoder(context)) "Encoder ✓" else "Choose encoder")
                        }
                        OutlinedButton(onClick = { pickDecoder.launch(arrayOf("*/*")) }) {
                            Text(if (EdgeSamEngine.hasDecoder(context)) "Decoder ✓" else "Choose decoder")
                        }
                    }
                }
            }

            // Time
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { if (runJob == null) loadFrame(curMs - (1000f / maskFps).toLong().coerceAtLeast(1L)) }) { Text("◀", color = Color.White) }
                Slider(
                    value = (curMs - fromMs).toFloat(),
                    onValueChange = { curMs = fromMs + it.toLong() },
                    onValueChangeFinished = { if (runJob == null) loadFrame(curMs) },
                    valueRange = 0f..spanMs.toFloat(),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { if (runJob == null) loadFrame(curMs + (1000f / maskFps).toLong().coerceAtLeast(1L)) }) { Text("▶", color = Color.White) }
                Text(
                    String.format(java.util.Locale.US, "%.1fs", (curMs - fromMs) / 1000f),
                    color = Color.White, fontSize = 12.sp, textAlign = TextAlign.End
                )
            }

            // Brush type + edits
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { foreground = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (foreground) Color(0xFF39E26B) else Color(0xFF2A2A33),
                        contentColor = if (foreground) Color.Black else Color.White
                    )
                ) { Text("+ Object") }
                Button(
                    onClick = { foreground = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!foreground) Color(0xFFFF4D4D) else Color(0xFF2A2A33),
                        contentColor = Color.White
                    )
                ) { Text("− Not object") }
                OutlinedButton(onClick = {
                    val list = strokes[curMs].orEmpty()
                    if (list.size <= 1) strokes.remove(curMs) else strokes[curMs] = list.dropLast(1)
                    showMask()
                }) { Text("Undo") }
                OutlinedButton(onClick = { strokes.remove(curMs); showMask() }) { Text("Clear") }
                if (modelsOk) OutlinedButton(onClick = {
                    inputMode = (inputMode + 1) % 3
                    EdgeSamEngine.setInputMode(context, inputMode)
                    if (runJob == null) loadFrame(curMs)
                }) { Text("Input " + (inputMode + 1)) }
            }

            progress?.let {
                LinearProgressIndicator(
                    progress = { it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    color = NeonCyan
                )
            }
            if (status.isNotEmpty()) {
                Text(status, color = Color(0xFFBEBEBE), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            }

            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (runJob != null) {
                    Button(
                        onClick = { runJob?.cancel() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Cancel") }
                } else {
                    Button(
                        enabled = modelsOk && totalStrokes > 0,
                        onClick = {
                            val keys = strokes.entries
                                .filter { it.value.isNotEmpty() }
                                .map { RotoBrushVideo.Key(it.key, it.value.flatten()) }
                            progress = 0f
                            status = "Following the object through the clip… (this can take a few minutes)"
                            runJob = scope.launch {
                                try {
                                    val r = withContext(Dispatchers.Default) {
                                        RotoBrushVideo.generate(context, clip, keys) { p -> progress = p }
                                    }
                                    progress = null
                                    runJob = null
                                    onApply(r)
                                } catch (e: CancellationException) {
                                    status = "Stopped"
                                    throw e
                                } catch (e: Throwable) {
                                    status = "Failed: " + (e.message ?: e.javaClass.simpleName)
                                } finally {
                                    progress = null
                                    runJob = null
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Propagate & apply") }
                }
            }
        }
    }
}
