package com.cvkulkarnidev.foodlabel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.cvkulkarnidev.foodlabel.model.LabelPanel
import com.cvkulkarnidev.foodlabel.ui.theme.Amber
import com.cvkulkarnidev.foodlabel.ui.theme.Forest
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.delay

private const val SMART_SCAN_DURATION_MS = 3_000L

@Composable
internal fun SmartScanScreen(
    modifier: Modifier = Modifier,
    panel: LabelPanel,
    onComplete: (List<Uri>) -> Unit,
    onCancel: () -> Unit,
    onQuickCapture: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!permissionGranted) {
        CameraPermissionScreen(
            modifier = modifier,
            panel = panel,
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onQuickCapture = onQuickCapture,
            onCancel = onCancel,
        )
        return
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var cameraReady by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var selectedCount by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf("Place the complete panel inside the guide") }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }

    val controller = remember(context, lifecycleOwner, previewView) {
        SmartScanController(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            onReady = { cameraReady = true },
            onStatus = { message, count ->
                hint = message
                selectedCount = count
            },
            onCompleted = { uris ->
                scanning = false
                processing = false
                if (uris.isEmpty()) {
                    hint = "No readable frame was captured. Add light, move closer and try again."
                } else {
                    onComplete(uris)
                }
            },
            onError = { message ->
                scanning = false
                processing = false
                cameraError = message
            },
        )
    }

    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.close() }
    }

    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect
        val started = SystemClock.elapsedRealtime()
        while (scanning) {
            val elapsed = SystemClock.elapsedRealtime() - started
            progress = (elapsed.toFloat() / SMART_SCAN_DURATION_MS).coerceIn(0f, 1f)
            if (elapsed >= SMART_SCAN_DURATION_MS) {
                scanning = false
                processing = true
                hint = "Selecting the clearest complementary frames…"
                controller.finishScan()
                break
            }
            delay(50)
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel, enabled = !processing) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Cancel scan", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text("Smart Scan", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(panel.label, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(
                    onClick = {
                        torchEnabled = !torchEnabled
                        controller.setTorch(torchEnabled)
                    },
                    enabled = cameraReady,
                ) {
                    Icon(
                        if (torchEnabled) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                        contentDescription = if (torchEnabled) "Turn flash off" else "Turn flash on",
                        tint = if (torchEnabled) Amber else Color.White,
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .fillMaxWidth(0.84f)
                        .aspectRatio(0.72f)
                        .border(3.dp, if (scanning) Amber else Color.White, RoundedCornerShape(22.dp)),
                )
                if (scanning || processing) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = CircleShape,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp),
                    ) {
                        Text(
                            if (processing) "Checking" else "${max(1, 3 - (progress * 3).toInt())}",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            Surface(
                color = Color.Black.copy(alpha = 0.82f),
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (cameraError != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Amber)
                            Spacer(Modifier.size(8.dp))
                            Text(cameraError.orEmpty(), color = Color.White, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(
                            hint,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(
                        "${selectedCount.coerceAtMost(3)}/3 strong frames found",
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (scanning || processing) {
                        LinearProgressIndicator(
                            progress = { if (processing) 1f else progress },
                            modifier = Modifier.fillMaxWidth().height(7.dp),
                            color = Amber,
                            trackColor = Color.White.copy(alpha = 0.22f),
                        )
                    }
                    Button(
                        onClick = {
                            selectedCount = 0
                            progress = 0f
                            hint = "Hold steady while the app finds the clearest frames"
                            controller.beginScan()
                            scanning = true
                        },
                        enabled = cameraReady && !scanning && !processing,
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Forest),
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(17.dp),
                    ) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Start 3-second scan", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onQuickCapture,
                        enabled = !scanning && !processing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Use a single document photo", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPermissionScreen(
    modifier: Modifier,
    panel: LabelPanel,
    onRequestPermission: () -> Unit,
    onQuickCapture: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(16.dp))
        Text("Camera access is needed for Smart Scan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "LabelWise analyzes three frames of the ${panel.label.lowercase()} on your phone. It does not record audio or save a video.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        )
        Spacer(Modifier.height(22.dp))
        Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) { Text("Allow camera") }
        OutlinedButton(onClick = onQuickCapture, modifier = Modifier.fillMaxWidth()) { Text("Use single document photo") }
        TextButton(onClick = onCancel) { Text("Back") }
    }
}

private class SmartScanController(
    private val context: Context,
    private val lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    private val previewView: PreviewView,
    private val onReady: () -> Unit,
    private val onStatus: (String, Int) -> Unit,
    private val onCompleted: (List<Uri>) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val scanning = AtomicBoolean(false)
    private val candidates = mutableListOf<FrameCandidate>()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var lastAnalyzedMs = 0L
    private var lastSavedMs = 0L
    private var previousThumbnail: IntArray? = null

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzerExecutor, ::analyze)
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                provider = cameraProvider
                onReady()
            } catch (error: Throwable) {
                onError(error.message ?: "The camera could not be started.")
            }
        }, mainExecutor)
    }

    fun beginScan() {
        analyzerExecutor.execute {
            candidates.forEach { it.file.delete() }
            candidates.clear()
            previousThumbnail = null
            lastAnalyzedMs = 0L
            lastSavedMs = 0L
            scanning.set(true)
        }
    }

    fun finishScan() {
        scanning.set(false)
        analyzerExecutor.execute {
            val selected = selectComplementaryFrames(candidates, 3)
            val selectedFiles = selected.map(FrameCandidate::file).toSet()
            candidates.filter { it.file !in selectedFiles }.forEach { it.file.delete() }
            val uris = selected.map { candidate ->
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", candidate.file)
            }
            mainExecutor.execute { onCompleted(uris) }
        }
    }

    fun setTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (!scanning.get()) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalyzedMs < 95L) return
            lastAnalyzedMs = now

            val measured = FrameMetrics.measure(image, previousThumbnail)
            previousThumbnail = measured.thumbnail
            val message = measured.guidance()
            mainExecutor.execute { onStatus(message, candidates.size.coerceAtMost(3)) }
            if (!measured.usable || now - lastSavedMs < 430L) return

            lastSavedMs = now
            val unrotated = image.toBitmap()
            val bitmap = rotate(unrotated, image.imageInfo.rotationDegrees)
            if (bitmap !== unrotated) unrotated.recycle()
            val directory = File(context.cacheDir, "label_photos").apply { mkdirs() }
            val file = File.createTempFile("smart_", ".jpg", directory)
            FileOutputStream(file).use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output) }
            bitmap.recycle()
            candidates += FrameCandidate(file, measured)
            candidates.sortByDescending { it.metrics.quality }
            while (candidates.size > 6) candidates.removeLast().file.delete()
            mainExecutor.execute { onStatus(message, candidates.size.coerceAtMost(3)) }
        } catch (error: Throwable) {
            mainExecutor.execute { onError(error.message ?: "A camera frame could not be processed.") }
        } finally {
            image.close()
        }
    }

    fun close() {
        scanning.set(false)
        provider?.unbindAll()
        analyzerExecutor.shutdown()
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

private data class FrameCandidate(val file: File, val metrics: FrameMetrics)

private data class FrameMetrics(
    val quality: Double,
    val brightness: Double,
    val contrast: Double,
    val sharpness: Double,
    val glareRatio: Double,
    val stability: Double,
    val thumbnail: IntArray,
) {
    val usable: Boolean
        get() = quality >= 0.43 && brightness in 42.0..226.0 && contrast >= 13.0 && sharpness >= 0.18

    fun guidance(): String = when {
        brightness < 58 -> "Too dark — add light or use the torch"
        brightness > 220 || glareRatio > 0.13 -> "Reduce glare — tilt the phone slightly"
        sharpness < 0.24 -> "Hold steady and let the camera focus"
        contrast < 18 -> "Move closer so the text fills the guide"
        stability < 0.30 -> "Hold the phone still"
        else -> "Good view — keep holding steady"
    }

    companion object {
        private const val SAMPLE_COLUMNS = 48
        private const val SAMPLE_ROWS = 64

        fun measure(image: ImageProxy, previous: IntArray?): FrameMetrics {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val values = IntArray(SAMPLE_COLUMNS * SAMPLE_ROWS)
            var sum = 0.0
            var squareSum = 0.0
            var glare = 0
            for (row in 0 until SAMPLE_ROWS) {
                val sourceY = min(image.height - 1, row * image.height / SAMPLE_ROWS)
                for (column in 0 until SAMPLE_COLUMNS) {
                    val sourceX = min(image.width - 1, column * image.width / SAMPLE_COLUMNS)
                    val value = buffer.get(sourceY * rowStride + sourceX * pixelStride).toInt() and 0xFF
                    val index = row * SAMPLE_COLUMNS + column
                    values[index] = value
                    sum += value
                    squareSum += value.toDouble() * value
                    if (value >= 246) glare++
                }
            }
            val mean = sum / values.size
            val contrast = sqrt((squareSum / values.size - mean * mean).coerceAtLeast(0.0))
            var laplacianTotal = 0.0
            for (row in 1 until SAMPLE_ROWS - 1) {
                for (column in 1 until SAMPLE_COLUMNS - 1) {
                    val index = row * SAMPLE_COLUMNS + column
                    val laplacian = 4 * values[index] - values[index - 1] - values[index + 1] -
                        values[index - SAMPLE_COLUMNS] - values[index + SAMPLE_COLUMNS]
                    laplacianTotal += abs(laplacian)
                }
            }
            val laplacianMean = laplacianTotal / ((SAMPLE_ROWS - 2) * (SAMPLE_COLUMNS - 2))
            val sharpness = ((laplacianMean - 3.0) / 25.0).coerceIn(0.0, 1.0)
            val exposure = (1.0 - abs(mean - 142.0) / 125.0).coerceIn(0.0, 1.0)
            val contrastScore = ((contrast - 10.0) / 48.0).coerceIn(0.0, 1.0)
            val glareRatio = glare.toDouble() / values.size
            val glareScore = (1.0 - glareRatio / 0.16).coerceIn(0.0, 1.0)
            val stability = previous?.let { earlier ->
                val difference = values.indices.sumOf { abs(values[it] - earlier[it]).toDouble() } / values.size
                (1.0 - difference / 52.0).coerceIn(0.0, 1.0)
            } ?: 0.62
            val quality = (
                0.32 * sharpness +
                    0.22 * exposure +
                    0.18 * contrastScore +
                    0.16 * glareScore +
                    0.12 * stability
                ).coerceIn(0.0, 1.0)
            return FrameMetrics(quality, mean, contrast, sharpness, glareRatio, stability, values)
        }
    }
}

private fun selectComplementaryFrames(candidates: List<FrameCandidate>, count: Int): List<FrameCandidate> {
    if (candidates.size <= count) return candidates.sortedByDescending { it.metrics.quality }
    val remaining = candidates.toMutableList()
    val selected = mutableListOf(remaining.removeAt(remaining.indices.maxBy { remaining[it].metrics.quality }))
    while (selected.size < count && remaining.isNotEmpty()) {
        val nextIndex = remaining.indices.maxBy { index ->
            val candidate = remaining[index]
            val diversity = selected.minOf { chosen -> thumbnailDifference(candidate.metrics.thumbnail, chosen.metrics.thumbnail) }
            0.84 * candidate.metrics.quality + 0.16 * diversity.coerceAtMost(0.35) / 0.35
        }
        selected += remaining.removeAt(nextIndex)
    }
    return selected
}

private fun thumbnailDifference(first: IntArray, second: IntArray): Double =
    first.indices.sumOf { abs(first[it] - second[it]).toDouble() } / (first.size * 255.0)
