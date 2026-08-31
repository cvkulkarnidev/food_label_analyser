package com.cvkulkarnidev.foodlabel.analysis

import com.cvkulkarnidev.foodlabel.model.LabelReport
import com.cvkulkarnidev.foodlabel.model.OcrAssessment
import com.cvkulkarnidev.foodlabel.model.ProductCategory

object ProductLabelAnalyzer {
    fun analyze(
        rawText: String,
        selectedCategory: ProductCategory? = null,
        ocrAssessment: OcrAssessment? = null,
    ): LabelReport {
        val detected = LabelTextParser.parse(rawText)
        val parsed = selectedCategory?.let { detected.copy(category = it) } ?: detected
        val validationPenalty = (parsed.extractionWarnings.size * 0.06).coerceAtMost(0.30)
        val effectiveOcrConfidence = (ocrAssessment?.confidence ?: 1.0) * (1.0 - validationPenalty)
        val result = HealthScorer.score(parsed, effectiveOcrConfidence)
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
            rawText = NutritionTextNormalizer.normalize(rawText).text,
        )
    }
}
