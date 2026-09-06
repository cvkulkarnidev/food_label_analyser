package com.cvkulkarnidev.foodlabel.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.AtomicFile
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.cvkulkarnidev.foodlabel.model.LabelReport
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

internal class ScanHistoryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val historyRoot = File(appContext.filesDir, HISTORY_DIRECTORY)

    fun save(
        requestedName: String,
        nutritionImage: Uri,
        ingredientsImage: Uri,
        report: LabelReport,
    ): SavedAnalysis {
        ensureHistoryRoot()
        val now = System.currentTimeMillis()
        val id = "${now}_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val staging = File(historyRoot, ".$id.tmp")
        val destination = File(historyRoot, id)
        check(staging.mkdir()) { "Could not create history storage." }

        return try {
            persistImage(nutritionImage, File(staging, NUTRITION_IMAGE))
            persistImage(ingredientsImage, File(staging, INGREDIENTS_IMAGE))
            val record = SavedAnalysisRecord(
                id = id,
                name = normalizeSavedAnalysisName(requestedName, report.productName),
                savedAtEpochMs = now,
                nutritionImageFileName = NUTRITION_IMAGE,
                ingredientsImageFileName = INGREDIENTS_IMAGE,
                report = report,
            )
            writeMetadata(File(staging, METADATA_FILE), record)
            check(staging.renameTo(destination)) { "Could not finish saving this analysis." }
            record.toSavedAnalysis(destination)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun list(): List<SavedAnalysisSummary> {
        ensureHistoryRoot()
        return historyRoot.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .mapNotNull { directory ->
                runCatching {
                    val record = readMetadata(directory)
                    SavedAnalysisSummary(
                        id = record.id,
                        name = record.name,
                        savedAtEpochMs = record.savedAtEpochMs,
                        nutritionImage = contentUri(File(directory, record.nutritionImageFileName)),
                        productName = record.report.productName,
                        category = record.report.category,
                        score = record.report.score,
                        readiness = record.report.readiness,
                    )
                }.getOrNull()
            }
            .sortedByDescending(SavedAnalysisSummary::savedAtEpochMs)
            .toList()
    }

    fun load(id: String): SavedAnalysis? {
        val directory = entryDirectory(id) ?: return null
        return runCatching { readMetadata(directory).toSavedAnalysis(directory) }.getOrNull()
    }

    fun updateReport(id: String, report: LabelReport): SavedAnalysis? {
        val directory = entryDirectory(id) ?: return null
        val updated = readMetadata(directory).copy(report = report)
        writeMetadata(File(directory, METADATA_FILE), updated)
        return updated.toSavedAnalysis(directory)
    }

    fun delete(id: String): Boolean {
        val directory = entryDirectory(id) ?: return false
        return directory.deleteRecursively()
    }

    private fun ensureHistoryRoot() {
        check(historyRoot.exists() || historyRoot.mkdirs()) { "Could not access history storage." }
        historyRoot.listFiles()
            .orEmpty()
            .filter { it.name.startsWith('.') && it.name.endsWith(".tmp") }
            .forEach(File::deleteRecursively)
    }

    private fun entryDirectory(id: String): File? {
        if (!ENTRY_ID.matches(id)) return null
        val directory = File(historyRoot, id)
        return directory.takeIf(File::isDirectory)
    }

    private fun readMetadata(directory: File): SavedAnalysisRecord =
        SavedAnalysisCodec.decode(File(directory, METADATA_FILE).readText(Charsets.UTF_8))

    private fun writeMetadata(file: File, record: SavedAnalysisRecord) {
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(SavedAnalysisCodec.encode(record).toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun SavedAnalysisRecord.toSavedAnalysis(directory: File): SavedAnalysis = SavedAnalysis(
        id = id,
        name = name,
        savedAtEpochMs = savedAtEpochMs,
        nutritionImage = contentUri(File(directory, nutritionImageFileName)),
        ingredientsImage = contentUri(File(directory, ingredientsImageFileName)),
        report = report,
    )

    private fun contentUri(file: File): Uri = FileProvider.getUriForFile(
        appContext,
        "${appContext.packageName}.fileprovider",
        file,
    )

    private fun persistImage(source: Uri, destination: File) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "The selected image is no longer available." }
            BitmapFactory.decodeStream(input, null, options)
        }
        require(options.outWidth > 0 && options.outHeight > 0) { "The selected image could not be decoded." }

        var sampleSize = 1
        while (max(options.outWidth, options.outHeight) / (sampleSize * 2) >= MAX_IMAGE_EDGE) {
            sampleSize *= 2
        }
        val bitmap = appContext.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "The selected image is no longer available." }
            BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
        } ?: error("The selected image could not be decoded.")

        val rotation = runCatching {
            appContext.contentResolver.openInputStream(source).use { input ->
                input?.let { ExifInterface(it).rotationDegrees } ?: 0
            }
        }.getOrDefault(0)
        val oriented = if (rotation == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                Matrix().apply { postRotate(rotation.toFloat()) },
                true,
            ).also { bitmap.recycle() }
        }
        val largestEdge = max(oriented.width, oriented.height)
        val stored = if (largestEdge <= MAX_IMAGE_EDGE) {
            oriented
        } else {
            val scale = MAX_IMAGE_EDGE.toDouble() / largestEdge
            Bitmap.createScaledBitmap(
                oriented,
                (oriented.width * scale).roundToInt(),
                (oriented.height * scale).roundToInt(),
                true,
            ).also { oriented.recycle() }
        }

        try {
            FileOutputStream(destination).use { output ->
                check(stored.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "The image could not be stored."
                }
                output.fd.sync()
            }
        } finally {
            stored.recycle()
        }
    }

    private companion object {
        const val HISTORY_DIRECTORY = "saved_analyses"
        const val METADATA_FILE = "analysis.json"
        const val NUTRITION_IMAGE = "nutrition.jpg"
        const val INGREDIENTS_IMAGE = "ingredients.jpg"
        const val MAX_IMAGE_EDGE = 1_800
        const val JPEG_QUALITY = 88
        val ENTRY_ID = Regex("[0-9]+_[a-f0-9]{12}")
    }
}
