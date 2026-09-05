package com.cvkulkarnidev.foodlabel.analysis

import com.cvkulkarnidev.foodlabel.model.AnalysisReadiness
import com.cvkulkarnidev.foodlabel.model.ImageOcrAssessment
import com.cvkulkarnidev.foodlabel.model.LabelPanel
import com.cvkulkarnidev.foodlabel.model.NutritionBasis
import com.cvkulkarnidev.foodlabel.model.NutritionFacts
import com.cvkulkarnidev.foodlabel.model.OcrAssessment
import com.cvkulkarnidev.foodlabel.model.OcrQuality
import com.cvkulkarnidev.foodlabel.model.ProductCategory
import com.cvkulkarnidev.foodlabel.model.ReviewedLabelInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisReadinessTest {
    @Test
    fun unknownBasisWithholdsScore() {
        val report = ProductLabelAnalyzer.analyze(
            rawText = """
                Sample drink
                Energy 56 kcal
                Carbohydrate 14 g
                Total sugars 13.7 g
                Sodium 22.3 mg
                Ingredients: carbonated water, sugar
            """.trimIndent(),
            selectedCategory = ProductCategory.BEVERAGES_AND_JUICES,
        )

        assertEquals(AnalysisReadiness.INSUFFICIENT, report.readiness)
        assertTrue(report.readinessMessage.contains("basis", ignoreCase = true))
    }

    @Test
    fun poorNutritionPhotoWithholdsAutomaticScore() {
        val report = ProductLabelAnalyzer.analyze(
            rawText = """
                Sample drink
                Per 100 ml
                Energy 56 kcal
                Carbohydrate 14 g
                Total sugars 13.7 g
                Added sugars 13.7 g
                Sodium 22.3 mg
                Ingredients: carbonated water, sugar
            """.trimIndent(),
            selectedCategory = ProductCategory.BEVERAGES_AND_JUICES,
            ocrAssessment = OcrAssessment(
                images = listOf(
                    assessment(LabelPanel.NUTRITION, OcrQuality.POOR, 0.35),
                    assessment(LabelPanel.INGREDIENTS, OcrQuality.GOOD, 0.92),
                ),
            ),
        )

        assertEquals(AnalysisReadiness.INSUFFICIENT, report.readiness)
        assertTrue(report.readinessMessage.contains("nutrition photo", ignoreCase = true))
    }

    @Test
    fun reviewedValuesReplaceOcrMistakesAndRestoreComparableScore() {
        val original = ProductLabelAnalyzer.analyze(
            rawText = """
                Sample drink
                Per 100 ml
                Energy 100 kcal
                Carbohydrate 100 g
                Total sugars 13.7 g
                Added sugars 13.7 g
                Sodium 22.3 mg
                Ingredients: carbonated water, sugar
            """.trimIndent(),
            selectedCategory = ProductCategory.BEVERAGES_AND_JUICES,
        )

        val reviewed = ProductLabelAnalyzer.review(
            original = original,
            reviewed = ReviewedLabelInput(
                productName = "Sample drink",
                nutrition = NutritionFacts(
                    energyKcal = 56.0,
                    carbohydrateG = 14.0,
                    totalSugarG = 13.7,
                    addedSugarG = 13.7,
                    totalFatG = 0.0,
                    proteinG = 0.0,
                    sodiumMg = 22.3,
                ),
                nutritionBasis = NutritionBasis.PER_100_ML,
                servingSize = null,
                ingredients = "carbonated water, sugar",
            ),
        )

        assertEquals(56.0, reviewed.nutrition.energyKcal)
        assertEquals(14.0, reviewed.nutrition.carbohydrateG)
        assertEquals(AnalysisReadiness.READY, reviewed.readiness)
        assertTrue(reviewed.wasUserReviewed)
        assertTrue(reviewed.readinessMessage.contains("reviewed", ignoreCase = true))
    }

    private fun assessment(
        panel: LabelPanel,
        quality: OcrQuality,
        confidence: Double,
    ) = ImageOcrAssessment(
        panel = panel,
        quality = quality,
        confidence = confidence,
        brightness = 75,
        sharpness = 75,
        enhancedImageUsed = false,
        warnings = emptyList(),
        recognitionConfidence = confidence,
    )
}

