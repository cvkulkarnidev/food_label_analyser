package com.cvkulkarnidev.foodlabel.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import com.cvkulkarnidev.foodlabel.analysis.NutritionTextNormalizer
import com.cvkulkarnidev.foodlabel.model.ImageOcrAssessment
import com.cvkulkarnidev.foodlabel.model.LabelPanel
import com.cvkulkarnidev.foodlabel.model.OcrQuality
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class OcrReadResult(
    val text: String,
    val assessment: ImageOcrAssessment,
)

object OnDeviceOcr {
    private const val MAX_IMAGE_DIMENSION = 1_800
    private const val MAX_RETRIED_ROWS = 6

    suspend fun read(context: Context, uri: Uri, panel: LabelPanel): OcrReadResult = withContext(Dispatchers.Default) {
        val original = decodeBitmap(context, uri)
        val metrics = measure(original)
        val shouldEnhance = metrics.brightness < 115 || metrics.contrast < 38 || metrics.sharpness < 140
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var enhanced: Bitmap? = null

        try {
            val originalPage = recognize(recognizer, original)
            val originalRendered = render(originalPage, panel)
            var selectedRendered = originalRendered
            var selectedBitmap = original
            var enhancedWasSelected = false

            if (shouldEnhance) {
                val enhancedBitmap = enhance(original, metrics)
                enhanced = enhancedBitmap
                val enhancedPage = recognize(recognizer, enhancedBitmap)
                val enhancedRendered = render(enhancedPage, panel)
                if (textEvidence(enhancedRendered, panel) > textEvidence(originalRendered, panel)) {
                    selectedRendered = enhancedRendered
                    selectedBitmap = enhancedBitmap
                    enhancedWasSelected = true
                }
            }

            if (panel == LabelPanel.NUTRITION) {
                selectedRendered = refineNutritionRows(
                    recognizer = recognizer,
                    bitmap = selectedBitmap,
                    initial = selectedRendered,
                )
            }

            var enginesCompared = listOf(OcrEngine.ML_KIT.label)
            var paddleContributed = false
            var paddleInferenceTimeMs: Long? = null
            when (val paddle = PaddleOcrEngine.recognize(context, selectedBitmap)) {
                is PaddleReadOutcome.Success -> {
                    enginesCompared = listOf(OcrEngine.ML_KIT.label, OcrEngine.PADDLE.label)
                    paddleInferenceTimeMs = paddle.inferenceTimeMs
                    val paddlePage = RecognizedPage(
                        lines = paddle.lines.map { line ->
                            RecognizedLine(
                                text = line.text,
                                boundingBox = line.boundingBox,
                                confidence = line.confidence,
                                elements = emptyList(),
                                engine = OcrEngine.PADDLE,
                            )
                        },
                        fallbackText = paddle.lines.joinToString("\n", transform = PaddleRecognizedLine::text),
                    )
                    val paddleRendered = render(paddlePage, panel)
                    selectedRendered = combineReadings(selectedRendered, paddleRendered, panel)
                    paddleContributed = selectedRendered.rows.any { it.engine == OcrEngine.PADDLE }
                }
                is PaddleReadOutcome.Unavailable -> {
                    selectedRendered = selectedRendered.copy(
                        warnings = (
                            selectedRendered.warnings +
                                "PaddleOCR was unavailable (${paddle.reason}); the ML Kit reading was used."
                            ).distinct(),
                    )
                }
            }

            OcrReadResult(
                text = selectedRendered.text,
                assessment = assess(
                    panel = panel,
                    metrics = metrics,
                    rendered = selectedRendered,
                    enhancedImageUsed = enhancedWasSelected,
                    enginesCompared = enginesCompared,
                    paddleContributed = paddleContributed,
                    paddleInferenceTimeMs = paddleInferenceTimeMs,
                ),
            )
        } finally {
            recognizer.close()
            enhanced?.recycle()
            original.recycle()
        }
    }

    private fun decodeBitmap(context: Context, uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeBitmapWithImageDecoder(context, uri)
        } else {
            decodeLegacyBitmap(context, uri)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeBitmapWithImageDecoder(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val largest = max(width, height)
            if (largest > MAX_IMAGE_DIMENSION) {
                val scale = MAX_IMAGE_DIMENSION.toDouble() / largest
                decoder.setTargetSize(
                    max(1, (width * scale).roundToInt()),
                    max(1, (height * scale).roundToInt()),
                )
            }
        }
    }

    private fun decodeLegacyBitmap(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "The selected image could not be opened." }
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "The selected image format is not supported."
        }
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_IMAGE_DIMENSION) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "The selected image could not be opened." }
            BitmapFactory.decodeStream(stream, null, options)
        } ?: error("The selected image could not be decoded.")

        val rotation = context.contentResolver.openInputStream(uri).use { stream ->
            stream?.let { ExifInterface(it).rotationDegrees } ?: 0
        }
        if (rotation == 0) return decoded

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { rotated -> if (rotated !== decoded) decoded.recycle() }
    }

    private suspend fun recognize(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: Bitmap,
    ): RecognizedPage = suspendCancellableCoroutine { continuation ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                if (continuation.isActive) {
                    val lines = result.textBlocks
                        .flatMap { it.lines }
                        .map { line ->
                            RecognizedLine(
                                text = line.text,
                                boundingBox = line.boundingBox?.let(::Rect),
                                confidence = line.confidence.toDouble(),
                                engine = OcrEngine.ML_KIT,
                                elements = line.elements.map { element ->
                                    RecognizedElement(
                                        text = element.text,
                                        boundingBox = element.boundingBox?.let(::Rect),
                                        confidence = element.confidence.toDouble(),
                                    )
                                },
                            )
                        }
                    continuation.resume(RecognizedPage(lines, result.text))
                }
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }

    private fun render(page: RecognizedPage, panel: LabelPanel): RenderedPage {
        if (panel == LabelPanel.INGREDIENTS) {
            val ordered = page.lines
                .sortedWith(compareBy({ it.boundingBox?.top ?: Int.MAX_VALUE }, { it.boundingBox?.left ?: Int.MAX_VALUE }))
            return RenderedPage(
                rows = ordered.map { line ->
                    RenderedRow(
                        text = line.text,
                        boundingBox = line.boundingBox,
                        confidence = line.confidence,
                        engine = line.engine,
                    )
                },
                fallbackText = page.fallbackText,
                corrections = emptyList(),
                warnings = emptyList(),
            )
        }

        val corrections = mutableListOf<String>()
        val rows = buildGeometryRows(page.lines).map { row ->
            val normalized = NutritionTextNormalizer.normalize(row.text)
            corrections += normalized.corrections
            row.copy(text = normalized.text)
        }
        val warnings = rows.mapNotNull { row ->
            if (isNutritionRow(row.text) && (row.confidence ?: 1.0) < 0.58) {
                "A nutrition row has low character confidence: “${row.text.take(52)}”."
            } else {
                null
            }
        }
        return RenderedPage(
            rows = rows,
            fallbackText = page.fallbackText,
            corrections = corrections.distinct(),
            warnings = warnings.distinct(),
        )
    }

    private fun combineReadings(
        mlKit: RenderedPage,
        paddle: RenderedPage,
        panel: LabelPanel,
    ): RenderedPage {
        if (paddle.text.isBlank()) {
            return mlKit.copy(
                warnings = (
                    mlKit.warnings +
                        "PaddleOCR found no usable text in this panel; the ML Kit reading was kept."
                    ).distinct(),
            )
        }

        if (panel == LabelPanel.INGREDIENTS) {
            val selected = if (textEvidence(paddle, panel) >= textEvidence(mlKit, panel)) paddle else mlKit
            val usedPaddle = selected.rows.any { it.engine == OcrEngine.PADDLE }
            return selected.copy(
                corrections = (
                    selected.corrections +
                        if (usedPaddle) {
                            listOf("PaddleOCR supplied the stronger ingredients reading after both engines were compared.")
                        } else {
                            emptyList()
                        }
                    ).distinct(),
            )
        }

        val paddleIsPrimary = textEvidence(paddle, panel) >= textEvidence(mlKit, panel)
        val primary = if (paddleIsPrimary) paddle else mlKit
        val alternate = if (paddleIsPrimary) mlKit else paddle
        val rows = primary.rows.toMutableList()
        var disagreements = 0

        alternate.rows.forEach { candidate ->
            val key = nutritionRowKey(candidate.text) ?: return@forEach
            val existingIndex = rows.indexOfFirst { nutritionRowKey(it.text) == key }
            if (existingIndex < 0) {
                rows += candidate
                return@forEach
            }

            val existing = rows[existingIndex]
            val existingValues = measurementSignature(existing.text)
            val candidateValues = measurementSignature(candidate.text)
            if (
                existingValues.isNotEmpty() &&
                candidateValues.isNotEmpty() &&
                existingValues != candidateValues
            ) {
                disagreements++
            }
            if (rowPreference(candidate) > rowPreference(existing)) {
                rows[existingIndex] = candidate
            }
        }

        val sortedRows = rows.sortedWith(
            compareBy(
                { it.boundingBox?.top ?: Int.MAX_VALUE },
                { it.boundingBox?.left ?: Int.MAX_VALUE },
            ),
        )
        val paddleRows = sortedRows.count { it.engine == OcrEngine.PADDLE }
        val generatedWarnings = sortedRows.mapNotNull { row ->
            when {
                isNutritionRow(row.text) && Regex("(?i)\\d\\s+9(?:\\s|$)").containsMatchIn(row.text) ->
                    "A possible g/9 confusion remains in: “${row.text.take(52)}”. Confirm this value."
                isNutritionRow(row.text) && (row.confidence ?: 1.0) < 0.58 ->
                    "A selected nutrition row has low character confidence: “${row.text.take(52)}”."
                else -> null
            }
        }
        val disagreementWarning = if (disagreements > 0) {
            listOf(
                "ML Kit and PaddleOCR read ${disagreements} nutrition row(s) differently. " +
                    "The higher-confidence, unit-consistent value was selected; verify it in the raw OCR text.",
            )
        } else {
            emptyList()
        }
        val paddleNote = if (paddleRows > 0) {
            listOf("PaddleOCR contributed ${paddleRows} selected nutrition row(s) after both engines were compared.")
        } else {
            emptyList()
        }

        return primary.copy(
            rows = sortedRows,
            fallbackText = if (paddleIsPrimary) paddle.fallbackText else mlKit.fallbackText,
            corrections = (primary.corrections + alternate.corrections + paddleNote).distinct(),
            warnings = (primary.warnings + generatedWarnings + disagreementWarning).distinct(),
        )
    }

    private fun rowPreference(row: RenderedRow): Int {
        val labelCount = nutritionLabelCount(row.text)
        val mergedRowPenalty = (labelCount - 1).coerceAtLeast(0) * 30
        val headerLeakPenalty = if (labelCount > 0 && isBasisRow(row.text)) 20 else 0
        return rowEvidence(row) +
            (if (row.engine == OcrEngine.PADDLE) 3 else 0) -
            mergedRowPenalty -
            headerLeakPenalty
    }

    private fun nutritionRowKey(text: String): String? {
        val normalized = text.lowercase()
        return when {
            isBasisRow(normalized) -> "basis"
            "serving" in normalized || "serve size" in normalized -> "serving"
            "energy" in normalized || "calorie" in normalized -> "energy"
            "protein" in normalized -> "protein"
            "carbohydrate" in normalized || "carbohydrates" in normalized -> "carbohydrate"
            "added sugar" in normalized -> "added sugar"
            "total sugar" in normalized -> "total sugar"
            "sugar" in normalized -> "sugar"
            "fibre" in normalized || "fiber" in normalized -> "fibre"
            "saturated fat" in normalized -> "saturated fat"
            "trans fat" in normalized -> "trans fat"
            Regex("\\bfat\\b").containsMatchIn(normalized) -> "fat"
            "sodium" in normalized -> "sodium"
            Regex("\\bsalt\\b").containsMatchIn(normalized) -> "salt"
            else -> null
        }
    }

    private fun measurementSignature(text: String): List<String> =
        Regex("(?i)\\d+(?:[.,]\\d+)?\\s*(?:kcal|kj|mg|mcg|g)\\b")
            .findAll(text)
            .map { it.value.lowercase().replace(" ", "") }
            .toList()

    private fun buildGeometryRows(lines: List<RecognizedLine>): List<RenderedRow> {
        val positioned = lines.filter { it.boundingBox != null }
            .sortedWith(compareBy({ it.boundingBox!!.top }, { it.boundingBox!!.left }))
        if (positioned.isEmpty()) {
            return lines.map { RenderedRow(it.text, it.boundingBox, it.confidence, it.engine) }
        }

        val groups = mutableListOf<MutableList<RecognizedLine>>()
        positioned.forEach { line ->
            val matching = groups.lastOrNull()?.takeIf { group ->
                // Compare with a stable row anchor. Matching against any item lets a tall or
                // slightly shifted box bridge adjacent rows and transitively collapse a table.
                sameVisualRow(group.first().boundingBox!!, line.boundingBox!!)
            }
            if (matching != null) matching += line else groups += mutableListOf(line)
        }

        val rendered = groups.map { group ->
            val elements = group.flatMap(RecognizedLine::elements)
                .filter { it.boundingBox != null }
                .sortedBy { it.boundingBox!!.left }
            val text = if (elements.isNotEmpty()) {
                joinElements(elements)
            } else {
                group.sortedBy { it.boundingBox!!.left }.joinToString(" | ", transform = RecognizedLine::text)
            }
            val box = group.mapNotNull(RecognizedLine::boundingBox).reduce(::union)
            val confidences = elements.mapNotNull(RecognizedElement::confidence)
                .ifEmpty { group.mapNotNull(RecognizedLine::confidence) }
            RenderedRow(
                text = text,
                boundingBox = box,
                confidence = confidences.average().takeIf { !it.isNaN() },
                engine = group.maxByOrNull { it.confidence ?: 0.0 }?.engine ?: OcrEngine.ML_KIT,
            )
        }

        val unpositioned = lines.filter { it.boundingBox == null }
            .map { RenderedRow(it.text, null, it.confidence, it.engine) }
        return rendered + unpositioned
    }

    private fun sameVisualRow(first: Rect, second: Rect): Boolean {
        val overlap = min(first.bottom, second.bottom) - max(first.top, second.top)
        val smallerHeight = min(first.height(), second.height()).coerceAtLeast(1)
        val centerDistance = abs((first.top + first.bottom) - (second.top + second.bottom)) / 2.0
        val overlapRatio = overlap.coerceAtLeast(0).toDouble() / smallerHeight
        return overlapRatio >= 0.35 &&
            centerDistance <= max(first.height(), second.height()) * 0.55
    }

    private fun joinElements(elements: List<RecognizedElement>): String {
        val output = StringBuilder()
        var previous: Rect? = null
        elements.forEach { element ->
            val box = element.boundingBox!!
            if (output.isNotEmpty()) {
                val gap = box.left - (previous?.right ?: box.left)
                val rowHeight = max(box.height(), previous?.height() ?: box.height())
                output.append(if (gap > max(22, (rowHeight * 1.7).roundToInt())) " | " else " ")
            }
            output.append(element.text)
            previous = box
        }
        return output.toString()
    }

    private suspend fun refineNutritionRows(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: Bitmap,
        initial: RenderedPage,
    ): RenderedPage {
        val candidates = initial.rows.withIndex()
            .filter { (_, row) -> shouldRetry(row) && row.boundingBox != null }
            .take(MAX_RETRIED_ROWS)
        if (candidates.isEmpty()) return initial

        val refined = initial.rows.toMutableList()
        val corrections = initial.corrections.toMutableList()
        for ((index, row) in candidates) {
            val crop = cropAndUpscale(bitmap, row.boundingBox!!) ?: continue
            try {
                val retried = render(recognize(recognizer, crop), LabelPanel.NUTRITION)
                val best = retried.rows
                    .filter { isNutritionRow(it.text) || isBasisRow(row.text) }
                    .maxByOrNull(::rowEvidence)
                    ?: retried.rows.maxByOrNull(::rowEvidence)
                    ?: continue
                if (rowEvidence(best) >= rowEvidence(row) + 7 && best.text != row.text) {
                    refined[index] = row.copy(
                        text = best.text,
                        confidence = maxOf(row.confidence ?: 0.0, best.confidence ?: 0.0),
                    )
                    corrections += "Re-read a low-confidence nutrition row at higher resolution: “${best.text.take(52)}”."
                    corrections += retried.corrections
                }
            } finally {
                crop.recycle()
            }
        }

        val warnings = refined.mapNotNull { row ->
            when {
                isNutritionRow(row.text) && Regex("(?i)\\d\\s+9(?:\\s|$)").containsMatchIn(row.text) ->
                    "A possible g/9 confusion remains in: “${row.text.take(52)}”. Confirm this value."
                isNutritionRow(row.text) && (row.confidence ?: 1.0) < 0.58 ->
                    "A nutrition row remains low-confidence after a high-resolution retry: “${row.text.take(52)}”."
                else -> null
            }
        }
        return initial.copy(
            rows = refined,
            corrections = corrections.distinct(),
            warnings = (initial.warnings + warnings).distinct(),
        )
    }

    private fun cropAndUpscale(bitmap: Bitmap, box: Rect): Bitmap? {
        val padX = max(8, box.height() / 2)
        val padY = max(5, box.height() / 4)
        val left = (box.left - padX).coerceIn(0, bitmap.width - 1)
        val top = (box.top - padY).coerceIn(0, bitmap.height - 1)
        val right = (box.right + padX).coerceIn(left + 1, bitmap.width)
        val bottom = (box.bottom + padY).coerceIn(top + 1, bitmap.height)
        if (right - left < 8 || bottom - top < 8) return null

        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val scale = (112.0 / crop.height).coerceIn(1.0, 4.0)
        val targetWidth = (crop.width * scale).roundToInt().coerceAtMost(2_600)
        val targetHeight = (crop.height * scale).roundToInt()
        if (targetWidth == crop.width && targetHeight == crop.height) return crop
        return Bitmap.createScaledBitmap(crop, targetWidth, targetHeight, true).also { crop.recycle() }
    }

    private fun shouldRetry(row: RenderedRow): Boolean {
        if (!isNutritionRow(row.text) && !isBasisRow(row.text)) return false
        val confidence = row.confidence ?: 0.70
        val suspiciousUnit = Regex("(?i)(?:\\d\\s+9\\b|m9\\b|rn9\\b|\\(9\\))").containsMatchIn(row.text)
        val missingUnit = isNutritionRow(row.text) && Regex("\\d").containsMatchIn(row.text) &&
            !Regex("(?i)\\b(?:kcal|kj|mg|mcg|g)\\b").containsMatchIn(row.text)
        return confidence < 0.80 || suspiciousUnit || missingUnit
    }

    private fun rowEvidence(row: RenderedRow): Int {
        val text = row.text
        var score = ((row.confidence ?: 0.5) * 100).roundToInt()
        if (isNutritionRow(text)) score += 55
        if (isBasisRow(text)) score += 40
        if (Regex("(?i)\\d+(?:[.,]\\d+)?\\s*(?:kcal|kj|mg|mcg|g)\\b").containsMatchIn(text)) score += 35
        if (Regex("(?i)(?:m9|rn9|\\d\\s+9\\b|\\(9\\))").containsMatchIn(text)) score -= 45
        return score
    }

    private val nutritionLabelPattern = Regex(
        "(?i)\\b(?:energy|calories?|protein|carbohydrates?|total sugars?|added sugars?|sugars?|" +
            "dietary fib(?:re|er)|fib(?:re|er)|total fat|saturated fat|trans fat|sodium|salt)\\b",
    )

    private fun isNutritionRow(text: String): Boolean =
        nutritionLabelPattern.containsMatchIn(text)

    private fun nutritionLabelCount(text: String): Int =
        nutritionLabelPattern.findAll(text).count()

    private fun isBasisRow(text: String): Boolean =
        Regex("(?i)\\bper\\s*(?:100|serv(?:e|ing)|pack)").containsMatchIn(text)

    private fun union(first: Rect, second: Rect): Rect = Rect(
        min(first.left, second.left),
        min(first.top, second.top),
        max(first.right, second.right),
        max(first.bottom, second.bottom),
    )

    private fun measure(bitmap: Bitmap): ImageMetrics {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val grayscale = IntArray(pixels.size)
        val histogram = IntArray(256)
        var sum = 0.0
        var squareSum = 0.0
        pixels.forEachIndexed { index, color ->
            val gray = luminance(color)
            grayscale[index] = gray
            histogram[gray]++
            sum += gray
            squareSum += gray * gray.toDouble()
        }

        val count = grayscale.size.coerceAtLeast(1)
        val mean = sum / count
        val contrast = sqrt((squareSum / count - mean * mean).coerceAtLeast(0.0))
        val step = if (max(width, height) > 1_200) 2 else 1
        var laplacianSum = 0.0
        var laplacianSquareSum = 0.0
        var laplacianCount = 0
        for (y in 1 until height - 1 step step) {
            for (x in 1 until width - 1 step step) {
                val index = y * width + x
                val laplacian = 4 * grayscale[index] -
                    grayscale[index - 1] - grayscale[index + 1] -
                    grayscale[index - width] - grayscale[index + width]
                laplacianSum += laplacian
                laplacianSquareSum += laplacian * laplacian.toDouble()
                laplacianCount++
            }
        }
        val laplacianMean = laplacianSum / laplacianCount.coerceAtLeast(1)
        val sharpness = (laplacianSquareSum / laplacianCount.coerceAtLeast(1) - laplacianMean * laplacianMean)
            .coerceAtLeast(0.0)

        return ImageMetrics(
            brightness = mean.roundToInt(),
            contrast = contrast.roundToInt(),
            sharpness = sharpness.roundToInt().coerceAtMost(9_999),
            histogram = histogram,
        )
    }

    private fun enhance(bitmap: Bitmap, metrics: ImageMetrics): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val source = IntArray(width * height)
        bitmap.getPixels(source, 0, width, 0, 0, width, height)

        val low = percentile(metrics.histogram, 0.015)
        val high = percentile(metrics.histogram, 0.985)
        val span = max(32, high - low)
        val gamma = when {
            metrics.brightness < 70 -> 0.58
            metrics.brightness < 95 -> 0.68
            metrics.brightness < 120 -> 0.82
            else -> 1.0
        }
        val adjusted = IntArray(source.size) { index ->
            val stretched = ((luminance(source[index]) - low) * 255.0 / span).coerceIn(0.0, 255.0)
            (255.0 * (stretched / 255.0).pow(gamma)).roundToInt().coerceIn(0, 255)
        }

        val output = IntArray(source.size)
        val strength = if (metrics.sharpness < 70) 0.58 else 0.32
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val value = if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    adjusted[index]
                } else {
                    val edge = 4 * adjusted[index] - adjusted[index - 1] - adjusted[index + 1] -
                        adjusted[index - width] - adjusted[index + width]
                    (adjusted[index] + strength * edge).roundToInt().coerceIn(0, 255)
                }
                output[index] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
            }
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun assess(
        panel: LabelPanel,
        metrics: ImageMetrics,
        rendered: RenderedPage,
        enhancedImageUsed: Boolean,
        enginesCompared: List<String>,
        paddleContributed: Boolean,
        paddleInferenceTimeMs: Long?,
    ): ImageOcrAssessment {
        val text = rendered.text
        val warnings = rendered.warnings.toMutableList()
        if (metrics.brightness < 72) {
            warnings += "${panel.label} photo is very dark; some text may be missing."
        } else if (metrics.brightness < 98) {
            warnings += "${panel.label} photo is dim; verify the extracted text."
        }
        if (metrics.contrast < 24) {
            warnings += "${panel.label} has low contrast between the text and background."
        }
        if (metrics.sharpness < 55) {
            warnings += "${panel.label} looks blurry; a sharper photo is recommended."
        } else if (metrics.sharpness < 105) {
            warnings += "${panel.label} may be slightly out of focus."
        }

        val alphanumericCount = text.count(Char::isLetterOrDigit)
        val keywordCount = keywordCount(text, panel)
        val expectedText = if (panel == LabelPanel.NUTRITION) 100.0 else 140.0
        if (alphanumericCount < expectedText * 0.45 || keywordCount == 0) {
            warnings += "OCR confidence is low for the ${panel.label.lowercase()}; check it before trusting the score."
        }

        val brightnessScore = when {
            metrics.brightness in 90..210 -> 1.0
            metrics.brightness < 90 -> (metrics.brightness / 90.0).coerceIn(0.15, 1.0)
            else -> ((255 - metrics.brightness) / 45.0).coerceIn(0.15, 1.0)
        }
        val contrastScore = (metrics.contrast / 55.0).coerceIn(0.15, 1.0)
        val sharpnessScore = (metrics.sharpness / 160.0).coerceIn(0.15, 1.0)
        val amountScore = (alphanumericCount / expectedText).coerceIn(0.1, 1.0)
        val keywordScore = (keywordCount / 4.0).coerceIn(0.1, 1.0)
        val recognitionScore = rendered.recognitionConfidence.coerceIn(0.15, 1.0)
        var confidence = 0.14 * brightnessScore + 0.10 * contrastScore + 0.16 * sharpnessScore +
            0.18 * amountScore + 0.16 * keywordScore + 0.26 * recognitionScore
        if (rendered.warnings.isNotEmpty()) confidence -= min(0.18, rendered.warnings.size * 0.06)
        if (alphanumericCount < 20) confidence = min(confidence, 0.38)
        confidence = confidence.coerceIn(0.2, 0.97)

        val quality = when {
            confidence >= 0.78 && warnings.isEmpty() -> OcrQuality.GOOD
            confidence >= 0.52 -> OcrQuality.REVIEW
            else -> OcrQuality.POOR
        }
        return ImageOcrAssessment(
            panel = panel,
            quality = quality,
            confidence = confidence,
            brightness = metrics.brightness,
            sharpness = metrics.sharpness,
            enhancedImageUsed = enhancedImageUsed,
            warnings = warnings.distinct(),
            recognitionConfidence = rendered.recognitionConfidence,
            corrections = rendered.corrections,
            enginesCompared = enginesCompared,
            paddleOcrContributed = paddleContributed,
            paddleInferenceTimeMs = paddleInferenceTimeMs,
        )
    }

    private fun textEvidence(rendered: RenderedPage, panel: LabelPanel): Int {
        val text = rendered.text
        val alphanumeric = text.count(Char::isLetterOrDigit)
        val lines = text.lineSequence().count { it.isNotBlank() }
        val confidence = (rendered.recognitionConfidence * 120).roundToInt()
        val units = if (panel == LabelPanel.NUTRITION) {
            Regex("(?i)\\d+(?:[.,]\\d+)?\\s*(?:kcal|kj|mg|mcg|g)\\b").findAll(text).count() * 18
        } else {
            0
        }
        val suspicious = if (panel == LabelPanel.NUTRITION) {
            Regex("(?i)(?:m9|rn9|\\d\\s+9\\b|\\(9\\))").findAll(text).count() * 32
        } else {
            0
        }
        return alphanumeric + lines * 6 + keywordCount(text, panel) * 45 + confidence + units - suspicious
    }

    private fun keywordCount(text: String, panel: LabelPanel): Int {
        val normalized = text.lowercase()
        val terms = when (panel) {
            LabelPanel.NUTRITION -> listOf(
                "nutrition", "energy", "calorie", "protein", "carbohydrate", "sugar",
                "fibre", "fiber", "fat", "sodium", "serving",
            )
            LabelPanel.INGREDIENTS -> listOf(
                "ingredient", "contains", "allergen", "sugar", "flour", "oil", "milk",
                "wheat", "salt", "emulsifier", "preservative",
            )
        }
        return terms.count(normalized::contains)
    }

    private fun percentile(histogram: IntArray, fraction: Double): Int {
        val target = (histogram.sum() * fraction).roundToInt()
        var cumulative = 0
        histogram.forEachIndexed { value, count ->
            cumulative += count
            if (cumulative >= target) return value
        }
        return 255
    }

    private fun luminance(color: Int): Int {
        val red = color shr 16 and 0xFF
        val green = color shr 8 and 0xFF
        val blue = color and 0xFF
        return (red * 77 + green * 150 + blue * 29) shr 8
    }

    private enum class OcrEngine(val label: String) {
        ML_KIT("ML Kit"),
        PADDLE("PaddleOCR"),
    }

    private data class ImageMetrics(
        val brightness: Int,
        val contrast: Int,
        val sharpness: Int,
        val histogram: IntArray,
    )

    private data class RecognizedElement(
        val text: String,
        val boundingBox: Rect?,
        val confidence: Double?,
    )

    private data class RecognizedLine(
        val text: String,
        val boundingBox: Rect?,
        val confidence: Double?,
        val elements: List<RecognizedElement>,
        val engine: OcrEngine,
    )

    private data class RecognizedPage(
        val lines: List<RecognizedLine>,
        val fallbackText: String,
    )

    private data class RenderedRow(
        val text: String,
        val boundingBox: Rect?,
        val confidence: Double?,
        val engine: OcrEngine = OcrEngine.ML_KIT,
    )

    private data class RenderedPage(
        val rows: List<RenderedRow>,
        val fallbackText: String,
        val corrections: List<String>,
        val warnings: List<String>,
    ) {
        val text: String
            get() = rows.joinToString("\n", transform = RenderedRow::text).ifBlank { fallbackText }

        val recognitionConfidence: Double
            get() = rows.mapNotNull(RenderedRow::confidence).average().takeIf { !it.isNaN() } ?: 0.45
    }
}
