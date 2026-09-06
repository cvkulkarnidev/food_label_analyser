package com.cvkulkarnidev.foodlabel.history

import com.cvkulkarnidev.foodlabel.model.AnalysisReadiness
import com.cvkulkarnidev.foodlabel.model.ImageOcrAssessment
import com.cvkulkarnidev.foodlabel.model.LabelPanel
import com.cvkulkarnidev.foodlabel.model.LabelReport
import com.cvkulkarnidev.foodlabel.model.NutritionBasis
import com.cvkulkarnidev.foodlabel.model.NutritionFacts
import com.cvkulkarnidev.foodlabel.model.OcrAssessment
import com.cvkulkarnidev.foodlabel.model.OcrQuality
import com.cvkulkarnidev.foodlabel.model.PeerComparison
import com.cvkulkarnidev.foodlabel.model.ProductCategory
import com.cvkulkarnidev.foodlabel.model.ScoreFactor
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedAnalysisCodecTest {
    @Test
    fun `round trips complete saved report`() {
        val report = LabelReport(
            productName = "Fanta Orange",
            category = ProductCategory.BEVERAGES_AND_JUICES,
            ingredients = "Carbonated water, sugar, colour (110)",
            allergens = listOf("None declared"),
            nutrition = NutritionFacts(
                energyKcal = 56.0,
                proteinG = 0.0,
                carbohydrateG = 14.0,
                totalSugarG = 13.7,
                addedSugarG = 13.7,
                totalFatG = 0.0,
                sodiumMg = 22.3,
            ),
            nutritionBasis = NutritionBasis.PER_100_ML,
            servingSize = "200 ml",
            score = 2.4,
            verdict = "Occasional choice",
            confidence = 0.86,
            factors = listOf(ScoreFactor("Added sugar", "High at 13.7 g", -1.1)),
            peerComparison = PeerComparison(38, 108, "Below the category midpoint", false),
            ocrAssessment = OcrAssessment(
                images = listOf(
                    ImageOcrAssessment(
                        panel = LabelPanel.NUTRITION,
                        quality = OcrQuality.GOOD,
                        confidence = 0.91,
                        brightness = 68,
                        sharpness = 77,
                        enhancedImageUsed = true,
                        warnings = listOf("Minor glare"),
                        recognitionConfidence = 0.88,
                        corrections = listOf("Corrected m9 to mg"),
                        enginesCompared = listOf("ML Kit", "PaddleOCR"),
                        paddleOcrContributed = true,
                        paddleInferenceTimeMs = 945,
                        framesAnalyzed = 3,
                        consensusAgreement = 0.93,
                        perspectiveCorrected = true,
                        deskewed = false,
                    ),
                ),
            ),
            extractionWarnings = listOf("Verify flavouring text"),
            readiness = AnalysisReadiness.REVIEW,
            readinessMessage = "Check the extracted values.",
            wasUserReviewed = true,
            rawText = "Nutrition information...",
        )
        val record = SavedAnalysisRecord(
            id = "1234567890_abcdef123456",
            name = "Fanta bottle at office",
            savedAtEpochMs = 1_725_000_000_000,
            nutritionImageFileName = "nutrition.jpg",
            ingredientsImageFileName = "ingredients.jpg",
            report = report,
        )

        assertEquals(record, SavedAnalysisCodec.decode(SavedAnalysisCodec.encode(record)))
    }

    @Test
    fun `normalizes blank long and multiline names`() {
        assertEquals("Fanta 750 ml", normalizeSavedAnalysisName("  Fanta\n 750 ml  ", "fallback"))
        assertEquals("Detected product", normalizeSavedAnalysisName(" ", "Detected product"))
        assertEquals(80, normalizeSavedAnalysisName("x".repeat(100), "fallback").length)
    }
}
