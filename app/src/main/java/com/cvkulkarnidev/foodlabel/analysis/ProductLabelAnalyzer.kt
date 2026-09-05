package com.cvkulkarnidev.foodlabel.analysis

import com.cvkulkarnidev.foodlabel.model.AnalysisReadiness
import com.cvkulkarnidev.foodlabel.model.LabelPanel
import com.cvkulkarnidev.foodlabel.model.LabelReport
import com.cvkulkarnidev.foodlabel.model.NutritionBasis
import com.cvkulkarnidev.foodlabel.model.NutritionFacts
import com.cvkulkarnidev.foodlabel.model.OcrAssessment
import com.cvkulkarnidev.foodlabel.model.OcrQuality
import com.cvkulkarnidev.foodlabel.model.ProductCategory
import com.cvkulkarnidev.foodlabel.model.ReviewedLabelInput

object ProductLabelAnalyzer {
    fun analyze(
        rawText: String,
        selectedCategory: ProductCategory? = null,
        ocrAssessment: OcrAssessment? = null,
    ): LabelReport {
        val detected = LabelTextParser.parse(rawText)
        val parsed = selectedCategory?.let { detected.copy(category = it) } ?: detected
        return buildReport(
            parsed = parsed,
            ocrAssessment = ocrAssessment,
            rawText = NutritionTextNormalizer.normalize(rawText).text,
            wasUserReviewed = false,
        )
    }

    fun review(original: LabelReport, reviewed: ReviewedLabelInput): LabelReport {
        val reparsed = LabelTextParser.parse(buildReviewedLabelText(reviewed)).copy(
            productName = reviewed.productName.trim().ifBlank { original.productName },
            category = original.category,
            allergens = original.allergens,
        )
        return buildReport(
            parsed = reparsed,
            ocrAssessment = original.ocrAssessment,
            rawText = original.rawText,
            wasUserReviewed = true,
        )
    }

    private fun buildReport(
        parsed: ParsedLabel,
        ocrAssessment: OcrAssessment?,
        rawText: String,
        wasUserReviewed: Boolean,
    ): LabelReport {
        val validationPenalty = (parsed.extractionWarnings.size * 0.06).coerceAtMost(0.30)
        val sourceConfidence = if (wasUserReviewed) 1.0 else (ocrAssessment?.confidence ?: 1.0)
        val effectiveOcrConfidence = sourceConfidence * (1.0 - validationPenalty)
        val result = HealthScorer.score(parsed, effectiveOcrConfidence)
        val readiness = determineReadiness(parsed, result.confidence, ocrAssessment, wasUserReviewed)

        return LabelReport(
            productName = parsed.productName,
            category = parsed.category,
            ingredients = parsed.ingredients,
            allergens = parsed.allergens,
            nutrition = parsed.nutrition,
            nutritionBasis = parsed.nutritionBasis,
            servingSize = parsed.servingSize,
            score = result.score,
            verdict = result.verdict,
            confidence = result.confidence,
            factors = result.factors,
            peerComparison = CategoryBenchmark.compare(parsed.category, result.score),
            ocrAssessment = ocrAssessment,
            extractionWarnings = parsed.extractionWarnings,
            readiness = readiness.first,
            readinessMessage = readiness.second,
            wasUserReviewed = wasUserReviewed,
            rawText = rawText,
        )
    }

    private fun determineReadiness(
        parsed: ParsedLabel,
        confidence: Double,
        ocrAssessment: OcrAssessment?,
        wasUserReviewed: Boolean,
    ): Pair<AnalysisReadiness, String> {
        val knownFields = parsed.nutrition.knownCount
        val nutritionQuality = ocrAssessment?.images?.firstOrNull { it.panel == LabelPanel.NUTRITION }?.quality

        if (knownFields < 2) {
            val fieldText = if (knownFields == 1) "field was" else "fields were"
            return AnalysisReadiness.INSUFFICIENT to
                "Only $knownFields nutrition $fieldText read. Review the values or retake the nutrition panel."
        }
        if (parsed.nutritionBasis == NutritionBasis.UNKNOWN) {
            return AnalysisReadiness.INSUFFICIENT to
                "The serving basis was not detected, so these values cannot be compared safely."
        }
        if (parsed.nutritionBasis == NutritionBasis.PER_PACK) {
            return AnalysisReadiness.INSUFFICIENT to
                "Per-pack values need a pack quantity before a comparable score can be calculated."
        }
        if (parsed.nutritionBasis == NutritionBasis.PER_SERVING && parsed.servingSize.isNullOrBlank()) {
            return AnalysisReadiness.INSUFFICIENT to
                "A serving size is required to compare per-serving values fairly."
        }
        if (!wasUserReviewed && nutritionQuality == OcrQuality.POOR) {
            return AnalysisReadiness.INSUFFICIENT to
                "The nutrition photo is too uncertain for a trustworthy score. Review the values or retake it."
        }
        if (!wasUserReviewed && confidence < 0.48) {
            return AnalysisReadiness.INSUFFICIENT to
                "Extraction confidence is too low for a trustworthy score. Review the values or retake both panels."
        }

        val needsReview = knownFields < 4 || parsed.extractionWarnings.isNotEmpty() ||
            (!wasUserReviewed && ocrAssessment?.needsReview == true) || confidence < 0.72
        return if (needsReview) {
            AnalysisReadiness.REVIEW to
                "This is a provisional estimate. Check the extracted values before relying on it."
        } else {
            AnalysisReadiness.READY to if (wasUserReviewed) {
                "Score recalculated from the values you reviewed."
            } else {
                "Enough comparable label data was read for an automatic estimate."
            }
        }
    }

    private fun buildReviewedLabelText(reviewed: ReviewedLabelInput): String = buildString {
        appendLine(reviewed.productName.trim())
        when (reviewed.nutritionBasis) {
            NutritionBasis.PER_100_G -> appendLine("Per 100 g")
            NutritionBasis.PER_100_ML -> appendLine("Per 100 ml")
            NutritionBasis.PER_SERVING -> appendLine("Per serving")
            NutritionBasis.PER_PACK -> appendLine("Per pack")
            NutritionBasis.UNKNOWN -> Unit
        }
        reviewed.servingSize?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine("Serving size: $it") }
        appendNutrition(reviewed.nutrition)
        reviewed.ingredients?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine("Ingredients: $it") }
    }

    private fun StringBuilder.appendNutrition(facts: NutritionFacts) {
        facts.energyKcal?.let { appendLine("Energy ${formatNumber(it)} kcal") }
        facts.proteinG?.let { appendLine("Protein ${formatNumber(it)} g") }
        facts.carbohydrateG?.let { appendLine("Carbohydrate ${formatNumber(it)} g") }
        facts.totalSugarG?.let { appendLine("Total sugars ${formatNumber(it)} g") }
        facts.addedSugarG?.let { appendLine("Added sugars ${formatNumber(it)} g") }
        facts.fibreG?.let { appendLine("Dietary fibre ${formatNumber(it)} g") }
        facts.totalFatG?.let { appendLine("Total fat ${formatNumber(it)} g") }
        facts.saturatedFatG?.let { appendLine("Saturated fat ${formatNumber(it)} g") }
        facts.transFatG?.let { appendLine("Trans fat ${formatNumber(it)} g") }
        facts.sodiumMg?.let { appendLine("Sodium ${formatNumber(it)} mg") }
    }

    private fun formatNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
}

