package com.cvkulkarnidev.foodlabel.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.cvkulkarnidev.foodlabel.model.ImageOcrAssessment
import com.cvkulkarnidev.foodlabel.model.LabelPanel
import com.cvkulkarnidev.foodlabel.model.OcrQuality
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

    suspend fun read(context: Context, uri: Uri, panel: LabelPanel): OcrReadResult = withContext(Dispatchers.Default) {
        val original = decodeBitmap(context, uri)
        val metrics = measure(original)
        val shouldEnhance = metrics.brightness < 115 || metrics.contrast < 38 || metrics.sharpness < 140
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        try {
            val originalText = recognize(recognizer, original)
            var selectedText = originalText
            var enhancedWasSelected = false

            if (shouldEnhance) {
                val enhanced = enhance(original, metrics)
                try {
                    val enhancedText = recognize(recognizer, enhanced)
                    if (textEvidence(enhancedText, panel) > textEvidence(originalText, panel)) {
                        selectedText = enhancedText
                        enhancedWasSelected = true
                    }
                } finally {
                    enhanced.recycle()
                }
            }

            OcrReadResult(
                text = selectedText,
                assessment = assess(panel, metrics, selectedText, enhancedWasSelected),
            )
        } finally {
            recognizer.close()
            original.recycle()
        }
    }

    private fun decodeBitmap(context: Context, uri: Uri): Bitmap {
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

    private suspend fun recognize(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: Bitmap,
    ): String = suspendCancellableCoroutine { continuation ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                if (continuation.isActive) {
                    val orderedLines = result.textBlocks
                        .flatMap { it.lines }
                        .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                        .joinToString("\n") { it.text }
                    continuation.resume(orderedLines.ifBlank { result.text })
                }
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }

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
        text: String,
        enhancedImageUsed: Boolean,
    ): ImageOcrAssessment {
        val warnings = mutableListOf<String>()
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
        var confidence = 0.2 * brightnessScore + 0.15 * contrastScore + 0.2 * sharpnessScore +
            0.25 * amountScore + 0.2 * keywordScore
        if (enhancedImageUsed) confidence = min(0.92, confidence + 0.05)
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
        )
    }

    private fun textEvidence(text: String, panel: LabelPanel): Int {
        val alphanumeric = text.count(Char::isLetterOrDigit)
        val lines = text.lineSequence().count { it.isNotBlank() }
        return alphanumeric + lines * 6 + keywordCount(text, panel) * 45
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

    private data class ImageMetrics(
        val brightness: Int,
        val contrast: Int,
        val sharpness: Int,
        val histogram: IntArray,
    )
}
