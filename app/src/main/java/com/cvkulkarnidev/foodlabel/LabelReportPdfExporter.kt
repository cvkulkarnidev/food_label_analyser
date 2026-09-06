package com.cvkulkarnidev.foodlabel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.cvkulkarnidev.foodlabel.analysis.IngredientAlertMatch
import com.cvkulkarnidev.foodlabel.model.AnalysisReadiness
import com.cvkulkarnidev.foodlabel.model.LabelReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object LabelReportPdfExporter {
    fun suggestedFileName(report: LabelReport, instanceName: String? = null): String {
        val product = instanceName.orEmpty().ifBlank { report.productName }
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .take(36)
            .ifBlank { "food_label" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return "LabelWise_${product}_$stamp.pdf"
    }

    fun write(
        context: Context,
        destination: Uri,
        nutritionImage: Uri,
        ingredientsImage: Uri,
        report: LabelReport,
        alerts: List<IngredientAlertMatch>,
        instanceName: String? = null,
    ) {
        val nutritionBitmap = decodeForReport(context, nutritionImage)
        val ingredientsBitmap = decodeForReport(context, ingredientsImage)
        val document = PdfDocument()
        try {
            val writer = PdfWriter(document)
            writer.heading("LabelWise food label report", 24f, Color.rgb(21, 92, 65))
            writer.paragraph(
                SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date()),
                10f,
                Color.DKGRAY,
            )
            writer.space(8f)
            val displayName = instanceName.orEmpty().ifBlank { report.productName }
            writer.heading(displayName, 18f, Color.rgb(24, 38, 32))
            if (!displayName.equals(report.productName, ignoreCase = true)) {
                writer.keyValue("Detected product", report.productName)
            }
            writer.keyValue("Category", report.category.label)
            writer.keyValue(
                "Health score",
                if (report.readiness == AnalysisReadiness.INSUFFICIENT) {
                    "Withheld — review required"
                } else {
                    "${format(report.score)} / 5 — ${report.verdict}"
                },
            )
            writer.keyValue("Analysis confidence", "${(report.confidence * 100).roundToInt()}%")
            writer.paragraph(report.readinessMessage, 10f, Color.DKGRAY)
            writer.space(10f)
            writer.twoImages(
                first = nutritionBitmap,
                firstLabel = "Nutrition panel",
                second = ingredientsBitmap,
                secondLabel = "Ingredients panel",
            )

            writer.section("Nutrition extracted")
            writer.keyValue("Basis", report.nutritionBasis.label)
            report.servingSize?.let { writer.keyValue("Serving size", it) }
            val facts = report.nutrition
            facts.energyKcal?.let { writer.keyValue("Energy", "${format(it)} kcal") }
            facts.proteinG?.let { writer.keyValue("Protein", "${format(it)} g") }
            facts.carbohydrateG?.let { writer.keyValue("Carbohydrate", "${format(it)} g") }
            facts.totalSugarG?.let { writer.keyValue("Total sugar", "${format(it)} g") }
            facts.addedSugarG?.let { writer.keyValue("Added sugar", "${format(it)} g") }
            facts.fibreG?.let { writer.keyValue("Dietary fibre", "${format(it)} g") }
            facts.totalFatG?.let { writer.keyValue("Total fat", "${format(it)} g") }
            facts.saturatedFatG?.let { writer.keyValue("Saturated fat", "${format(it)} g") }
            facts.transFatG?.let { writer.keyValue("Trans fat", "${format(it)} g") }
            facts.sodiumMg?.let { writer.keyValue("Sodium", "${format(it)} mg") }

            writer.section("Why this score")
            if (report.factors.isEmpty()) {
                writer.paragraph("Not enough confirmed information was available to explain a score.")
            } else {
                report.factors.forEach { factor ->
                    val sign = if (factor.impact > 0) "+" else ""
                    writer.bullet("${factor.title} ($sign${format(factor.impact)}): ${factor.detail}")
                }
            }
            writer.paragraph(
                "Category position: ${report.peerComparison.position} " +
                    "(${report.peerComparison.percentile}th percentile among ${report.peerComparison.peerCount} similar products).",
            )

            writer.section("Ingredients")
            writer.paragraph(report.ingredients ?: "No reliable ingredients list was extracted.")
            if (report.allergens.isNotEmpty()) {
                writer.keyValue("Allergens detected", report.allergens.joinToString())
            }
            writer.subheading("Your ingredient alerts")
            if (alerts.isEmpty()) {
                writer.paragraph("None of the selected watchlist ingredients were found.")
            } else {
                alerts.forEach { alert ->
                    writer.bullet("${alert.label}: ${alert.matchedTerms.joinToString()} — ${alert.reason}")
                }
            }

            writer.section("OCR quality and review notes")
            report.ocrAssessment?.images?.forEach { assessment ->
                val frameText = if (assessment.framesAnalyzed > 1) {
                    " • ${assessment.framesAnalyzed} frames" +
                        (assessment.consensusAgreement?.let { " • ${(it * 100).roundToInt()}% frame agreement" } ?: "")
                } else {
                    " • single image"
                }
                writer.subheading(
                    "${assessment.panel.label}: ${(assessment.confidence * 100).roundToInt()}%$frameText",
                )
                assessment.corrections.forEach(writer::bullet)
                assessment.warnings.forEach(writer::bullet)
            }
            report.extractionWarnings.forEach(writer::bullet)

            writer.section("Important")
            writer.paragraph(
                "This report is general label-reading guidance, not medical advice. Verify uncertain values " +
                    "against the original package. Individual needs, allergies and portion size still matter.",
                10f,
                Color.DKGRAY,
            )
            writer.finish()
            context.contentResolver.openOutputStream(destination, "w").use { output ->
                requireNotNull(output) { "The selected file could not be opened." }
                document.writeTo(output)
            }
        } finally {
            document.close()
            nutritionBitmap?.recycle()
            ingredientsBitmap?.recycle()
        }
    }

    private fun decodeForReport(context: Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val largest = max(info.size.width, info.size.height)
                if (largest > 1_400) {
                    val scale = 1_400.0 / largest
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt(),
                        (info.size.height * scale).roundToInt(),
                    )
                }
            }
        } else {
            val decoded = context.contentResolver.openInputStream(uri).use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return null
            val rotation = context.contentResolver.openInputStream(uri).use { stream ->
                stream?.let { ExifInterface(it).rotationDegrees } ?: 0
            }
            if (rotation == 0) decoded else Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                Matrix().apply { postRotate(rotation.toFloat()) },
                true,
            ).also { decoded.recycle() }
        }
    } catch (_: Throwable) {
        null
    }

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(Locale.US, value).trimEnd('0')
}

private class PdfWriter(private val document: PdfDocument) {
    private val pageWidth = 595
    private val pageHeight = 842
    private val margin = 42f
    private val contentWidth = pageWidth - margin * 2
    private var pageNumber = 0
    private var page: PdfDocument.Page? = null
    private var y = margin

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(24, 38, 32)
        textSize = 11f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    init {
        newPage()
    }

    fun heading(text: String, size: Float, color: Int) {
        ensure(size + 12f)
        paint.textSize = size
        paint.color = color
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        page!!.canvas.drawText(text.take(78), margin, y + size, paint)
        y += size + 10f
    }

    fun section(text: String) {
        ensure(42f)
        space(10f)
        paint.color = Color.rgb(21, 92, 65)
        paint.strokeWidth = 2f
        page!!.canvas.drawLine(margin, y, pageWidth - margin, y, paint)
        y += 8f
        heading(text, 15f, Color.rgb(21, 92, 65))
    }

    fun subheading(text: String) {
        ensure(25f)
        paint.textSize = 11f
        paint.color = Color.rgb(24, 38, 32)
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        page!!.canvas.drawText(text.take(95), margin, y + 12f, paint)
        y += 19f
    }

    fun keyValue(key: String, value: String) {
        paragraph("$key: $value", 11f, Color.rgb(24, 38, 32), boldPrefix = key.length + 1)
    }

    fun bullet(text: String) {
        paragraph("• $text", 10.5f, Color.rgb(44, 54, 50), indent = 12f)
    }

    fun paragraph(
        text: String,
        size: Float = 10.5f,
        color: Int = Color.rgb(44, 54, 50),
        boldPrefix: Int = 0,
        indent: Float = 0f,
    ) {
        paint.textSize = size
        paint.color = color
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        val lines = wrap(text.replace(Regex("\\s+"), " ").trim(), contentWidth - indent, paint)
        val lineHeight = size * 1.42f
        lines.forEachIndexed { index, line ->
            ensure(lineHeight + 2f)
            if (boldPrefix > 0 && index == 0 && line.length > boldPrefix) {
                val prefix = line.take(boldPrefix)
                paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                page!!.canvas.drawText(prefix, margin + indent, y + size, paint)
                val prefixWidth = paint.measureText(prefix)
                paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                page!!.canvas.drawText(line.drop(boldPrefix), margin + indent + prefixWidth, y + size, paint)
            } else {
                page!!.canvas.drawText(line, margin + indent, y + size, paint)
            }
            y += lineHeight
        }
        y += 2f
    }

    fun twoImages(first: Bitmap?, firstLabel: String, second: Bitmap?, secondLabel: String) {
        if (first == null && second == null) return
        ensure(205f)
        val gap = 14f
        val boxWidth = (contentWidth - gap) / 2f
        val top = y
        drawImage(first, margin, top, boxWidth, 160f, firstLabel)
        drawImage(second, margin + boxWidth + gap, top, boxWidth, 160f, secondLabel)
        y += 190f
    }

    fun space(amount: Float) {
        ensure(amount)
        y += amount
    }

    fun finish() {
        page?.let(document::finishPage)
        page = null
    }

    private fun drawImage(bitmap: Bitmap?, left: Float, top: Float, width: Float, height: Float, label: String) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.LTGRAY
        page!!.canvas.drawRoundRect(RectF(left, top, left + width, top + height), 8f, 8f, paint)
        paint.style = Paint.Style.FILL
        if (bitmap != null) {
            val scale = min(width / bitmap.width, height / bitmap.height)
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            val destination = RectF(
                left + (width - drawWidth) / 2f,
                top + (height - drawHeight) / 2f,
                left + (width + drawWidth) / 2f,
                top + (height + drawHeight) / 2f,
            )
            page!!.canvas.drawBitmap(bitmap, null, destination, paint)
        }
        paint.textSize = 10f
        paint.color = Color.DKGRAY
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        page!!.canvas.drawText(label, left, top + height + 14f, paint)
    }

    private fun ensure(height: Float) {
        if (y + height <= pageHeight - margin) return
        page?.let(document::finishPage)
        newPage()
    }

    private fun newPage() {
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        y = margin
        val canvas: Canvas = page!!.canvas
        paint.textSize = 8f
        paint.color = Color.GRAY
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText("LabelWise • page $pageNumber", margin, pageHeight - 20f, paint)
    }

    private fun wrap(text: String, width: Float, paint: Paint): List<String> {
        if (text.isBlank()) return listOf("—")
        val words = text.split(' ')
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= width || current.isEmpty()) {
                current = StringBuilder(candidate)
            } else {
                lines += current.toString()
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }
}
