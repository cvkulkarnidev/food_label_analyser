package com.cvkulkarnidev.foodlabel.analysis

import com.cvkulkarnidev.foodlabel.model.LabelReport

object ProductLabelAnalyzer {
    fun analyze(rawText: String): LabelReport {
        val parsed = LabelTextParser.parse(rawText)
        val result = HealthScorer.score(parsed)
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
            rawText = rawText,
        )
    }
}

