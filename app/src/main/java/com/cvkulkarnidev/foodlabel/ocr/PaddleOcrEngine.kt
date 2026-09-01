package com.cvkulkarnidev.foodlabel.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PaddleRecognizedLine(
    val text: String,
    val boundingBox: Rect,
    val confidence: Double,
)

internal sealed interface PaddleReadOutcome {
    data class Success(
        val lines: List<PaddleRecognizedLine>,
        val inferenceTimeMs: Long,
    ) : PaddleReadOutcome

    data class Unavailable(val reason: String) : PaddleReadOutcome
}

/** A process-wide PaddleOCR session so two-panel analysis pays model loading only once. */
internal object PaddleOcrEngine {
    private val mutex = Mutex()
    private var engine: PaddleOCR? = null
    private var initializationFailure: String? = null

    suspend fun recognize(context: Context, bitmap: Bitmap): PaddleReadOutcome = mutex.withLock {
        initializationFailure?.let { return PaddleReadOutcome.Unavailable(it) }

        val ocr = engine ?: try {
            check(OpenCVUtils.init(context.applicationContext)) {
                OpenCVUtils.lastError ?: "OpenCV could not be initialized"
            }
            PaddleOCR.create(
                context = context.applicationContext,
                config = PaddleOCRConfig(
                    detThresh = 0.20f,
                    detBoxThresh = 0.45f,
                    detUnclipRatio = 1.4f,
                    recScoreThresh = 0.25f,
                    recBatchSize = 1,
                ),
                engineConfig = EngineConfig(
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                ),
                detModelAssetPath = "models/det/inference.onnx",
                recModelAssetPath = "models/rec/inference.onnx",
                recConfigAssetPath = "models/rec/inference.yml",
            ).also { engine = it }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val reason = conciseReason(error)
            initializationFailure = reason
            return PaddleReadOutcome.Unavailable(reason)
        }

        try {
            val result = ocr.recognize(bitmap)
            PaddleReadOutcome.Success(
                lines = result.results
                    .filter { it.text.isNotBlank() }
                    .map { item ->
                        val points = item.box.points
                        PaddleRecognizedLine(
                            text = item.text.trim(),
                            boundingBox = Rect(
                                floor(points.minOf { it.x }.toDouble()).toInt(),
                                floor(points.minOf { it.y }.toDouble()).toInt(),
                                ceil(points.maxOf { it.x }.toDouble()).toInt(),
                                ceil(points.maxOf { it.y }.toDouble()).toInt(),
                            ),
                            confidence = item.confidence.toDouble().coerceIn(0.0, 1.0),
                        )
                    },
                inferenceTimeMs = result.totalTimeMs,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            PaddleReadOutcome.Unavailable(conciseReason(error))
        }
    }

    private fun conciseReason(error: Throwable): String {
        val useful = generateSequence(error) { it.cause }
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
        return useful?.take(120) ?: error::class.java.simpleName
    }
}
