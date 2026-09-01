package com.cvkulkarnidev.foodlabel.analysis

import com.cvkulkarnidev.foodlabel.model.NutritionBasis
import com.cvkulkarnidev.foodlabel.model.ImageOcrAssessment
import com.cvkulkarnidev.foodlabel.model.LabelPanel
import com.cvkulkarnidev.foodlabel.model.OcrAssessment
import com.cvkulkarnidev.foodlabel.model.OcrQuality
import com.cvkulkarnidev.foodlabel.model.ProductCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductLabelAnalyzerTest {
    @Test
    fun `extracts an Indian nutrition panel and ingredients`() {
        val report = ProductLabelAnalyzer.analyze(
            """
            Crunchy Breakfast Cereal
            NUTRITION INFORMATION (Approximate Values)
            Per 100 g
            Energy 410 kcal
            Protein 8.4 g
            Carbohydrate 76.2 g
            Total Sugars 24.0 g
            Added Sugars 18.0 g
            Dietary Fibre 6.5 g
            Total Fat 8.2 g
            Saturated Fat 2.1 g
            Trans Fat 0 g
            Sodium 380 mg
            Ingredients: Whole grain oats, sugar, rice flour, cocoa, palm oil, INS 322
            Allergen advice: Contains gluten and soy. May contain milk and nuts.
            """.trimIndent(),
        )

        assertEquals(NutritionBasis.PER_100_G, report.nutritionBasis)
        assertEquals(18.0, report.nutrition.addedSugarG ?: -1.0, 0.001)
        assertEquals(380.0, report.nutrition.sodiumMg ?: -1.0, 0.001)
        assertTrue(report.ingredients?.startsWith("Whole grain oats") == true)
        assertTrue(report.allergens.contains("Soy"))
        assertTrue(report.allergens.contains("Milk"))
        assertTrue(report.score in 2.0..4.0)
    }

    @Test
    fun `scores a minimally processed high fibre food highly`() {
        val report = ProductLabelAnalyzer.analyze(
            """
            Classic Rolled Oats
            Nutrition Facts Per 100 g
            Energy 370 kcal
            Protein 13 g
            Carbohydrate 62 g
            Total Sugars 1 g
            Dietary Fibre 10 g
            Total Fat 7 g
            Saturated Fat 1.2 g
            Trans Fat 0 g
            Sodium 5 mg
            Ingredients: 100% whole grain rolled oats
            """.trimIndent(),
        )

        assertTrue("Expected score above 4.5, got ${report.score}", report.score >= 4.5)
        assertTrue(report.confidence >= 0.8)
    }

    @Test
    fun `scores high sugar sodium and fat conservatively`() {
        val report = ProductLabelAnalyzer.analyze(
            """
            Choco Filled Cookies
            Nutrition Information Per 100 g
            Energy 510 kcal
            Protein 4 g
            Carbohydrate 68 g
            Total Sugar 35 g
            Dietary Fibre 1 g
            Total Fat 24 g
            Saturated Fat 12 g
            Trans Fat 1.2 g
            Sodium 710 mg
            Ingredients: Sugar, refined wheat flour (maida), palm oil, glucose syrup, cocoa, INS 322, INS 500, INS 503
            """.trimIndent(),
        )

        assertTrue("Expected score below 2, got ${report.score}", report.score < 2.0)
        assertTrue(report.factors.any { it.title == "Ingredient order" })
    }

    @Test
    fun `normalizes per serving values before scoring`() {
        val perServing = ProductLabelAnalyzer.analyze(
            """
            Granola Bar
            Serving Size: 25 g
            Nutrition Information Per Serving
            Energy 120 kcal
            Protein 2 g
            Total Sugar 5 g
            Saturated Fat 1 g
            Sodium 75 mg
            Ingredients: Oats, dates, sugar
            """.trimIndent(),
        )
        val perHundred = ProductLabelAnalyzer.analyze(
            """
            Granola Bar
            Nutrition Information Per 100 g
            Energy 480 kcal
            Protein 8 g
            Total Sugar 20 g
            Saturated Fat 4 g
            Sodium 300 mg
            Ingredients: Oats, dates, sugar
            """.trimIndent(),
        )

        assertEquals(perHundred.score, perServing.score, 0.1)
    }

    @Test
    fun `uses the category selected by the user`() {
        val report = ProductLabelAnalyzer.analyze(
            """
            Chocolate Cookies
            Nutrition Information Per 100 g
            Total Sugar 24 g
            Sodium 200 mg
            Ingredients: Sugar, refined wheat flour, cocoa
            """.trimIndent(),
            selectedCategory = ProductCategory.CHOCOLATE_AND_SWEETS,
        )

        assertEquals(ProductCategory.CHOCOLATE_AND_SWEETS, report.category)
        assertEquals(222, report.peerComparison.peerCount)
    }

    @Test
    fun `applies stricter sugar scoring to a selected beverage`() {
        val text = """
            Sweetened Product
            Nutrition Information Per 100 ml
            Protein 1 g
            Total Sugar 10 g
            Saturated Fat 0 g
            Trans Fat 0 g
            Sodium 20 mg
            Ingredients: Water, fruit pulp, sugar
        """.trimIndent()

        val beverage = ProductLabelAnalyzer.analyze(text, ProductCategory.BEVERAGES_AND_JUICES)
        val biscuit = ProductLabelAnalyzer.analyze(text, ProductCategory.BISCUITS_AND_BAKERY)

        assertTrue(beverage.score < biscuit.score)
    }

    @Test
    fun `category percentile compares only with selected peers`() {
        val biscuit = CategoryBenchmark.compare(ProductCategory.BISCUITS_AND_BAKERY, 3.0)
        val beverage = CategoryBenchmark.compare(ProductCategory.BEVERAGES_AND_JUICES, 3.0)

        assertTrue(biscuit.percentile > beverage.percentile)
        assertEquals(149, biscuit.peerCount)
        assertEquals(118, beverage.peerCount)
        assertTrue(ProductCategory.entries.all { CategoryBenchmark.peerCount(it) > 0 })
        assertEquals(840, ProductCategory.entries.sumOf(CategoryBenchmark::peerCount))
    }

    @Test
    fun `low OCR confidence is retained and makes scoring conservative`() {
        val text = """
            Rolled Oats
            Nutrition Information Per 100 g
            Protein 13 g
            Total Sugar 1 g
            Dietary Fibre 10 g
            Saturated Fat 1 g
            Trans Fat 0 g
            Sodium 5 mg
            Ingredients: Whole grain rolled oats
        """.trimIndent()
        val baseline = ProductLabelAnalyzer.analyze(text, ProductCategory.INSTANT_AND_READY_FOODS)
        val assessment = OcrAssessment(
            images = listOf(
                ImageOcrAssessment(
                    panel = LabelPanel.NUTRITION,
                    quality = OcrQuality.POOR,
                    confidence = 0.42,
                    brightness = 55,
                    sharpness = 30,
                    enhancedImageUsed = true,
                    warnings = listOf("Nutrition label looks blurry."),
                ),
                ImageOcrAssessment(
                    panel = LabelPanel.INGREDIENTS,
                    quality = OcrQuality.REVIEW,
                    confidence = 0.58,
                    brightness = 82,
                    sharpness = 90,
                    enhancedImageUsed = true,
                    warnings = listOf("Ingredients list photo is dim."),
                ),
            ),
        )

        val lowQuality = ProductLabelAnalyzer.analyze(
            text,
            ProductCategory.INSTANT_AND_READY_FOODS,
            assessment,
        )

        assertEquals(assessment, lowQuality.ocrAssessment)
        assertTrue(lowQuality.confidence < baseline.confidence)
        assertTrue(kotlin.math.abs(lowQuality.score - 3.0) < kotlin.math.abs(baseline.score - 3.0))
    }

    @Test
    fun `repairs 9 only when it is in a gram unit position`() {
        val report = ProductLabelAnalyzer.analyze(
            """
            Fortified Cereal with Vitamin B9
            Nutrition Information Per 100 9
            Protein 6 9
            Total Sugar 8.5 9
            Sodium 240 m9
            Ingredients: Whole grains, vitamin B9, salt
            """.trimIndent(),
        )

        assertEquals(NutritionBasis.PER_100_G, report.nutritionBasis)
        assertEquals(6.0, report.nutrition.proteinG ?: -1.0, 0.001)
        assertEquals(8.5, report.nutrition.totalSugarG ?: -1.0, 0.001)
        assertEquals(240.0, report.nutrition.sodiumMg ?: -1.0, 0.001)
        assertTrue(report.rawText.contains("Vitamin B9"))
        assertTrue(report.extractionWarnings.any { it.contains("trailing ‘9’") })
    }

    @Test
    fun `does not silently split a merged protein 69 value`() {
        val report = ProductLabelAnalyzer.analyze(
            """
            Protein Mix
            Nutrition Information Per 100 g
            Protein 69
            Total Fat 5 g
            Ingredients: Soy protein, cocoa
            """.trimIndent(),
        )

        assertEquals(69.0, report.nutrition.proteinG ?: -1.0, 0.001)
        assertTrue(report.extractionWarnings.any { it.contains("merged ‘g/9’") })
    }

    @Test
    fun `excludes cross-field values that cannot be true`() {
        val report = ProductLabelAnalyzer.analyze(
            """
            Test Food
            Nutrition Information Per 100 g
            Carbohydrate 20 g
            Total Sugar 35 g
            Added Sugar 40 g
            Total Fat 8 g
            Saturated Fat 18 g
            Ingredients: Flour, sugar
            """.trimIndent(),
        )

        assertEquals(null, report.nutrition.totalSugarG)
        assertEquals(null, report.nutrition.addedSugarG)
        assertEquals(null, report.nutrition.saturatedFatG)
        assertTrue(report.extractionWarnings.size >= 2)
    }

    @Test
    fun `reads units separated into geometric table cells`() {
        val report = ProductLabelAnalyzer.analyze(
            """
            Table Product
            Nutrition Information | Per 100 | g
            Protein | 7.5 | g
            Salt | 1 | g
            Ingredients: Oats, salt
            """.trimIndent(),
        )

        assertEquals(7.5, report.nutrition.proteinG ?: -1.0, 0.001)
        assertEquals(400.0, report.nutrition.sodiumMg ?: -1.0, 0.001)
    }

    @Test
    fun `ignores per 100 ml header inside a merged Fanta table row`() {
        val report = ProductLabelAnalyzer.analyze(
            """
            Fanta Orange
            Nutrition information (approximate values)
            SERVING = 200 ml : 3.8
            SERVINGS IN THIS PACK
            Energy | Carbohydrate | per 100 ml | 14 g | 56 kcal | %RDA* PER SERVE | 5.6%
            Total sugars | 13.7 g
            Added sugars | 13.7 g | 54.8%
            Total fat | 0 g | 0%
            Protein | 0 g
            Sodium | 22.3 mg | 2.2%
            Ingredients: Carbonated water, sugar, acidity regulator (330), stabilizers (414, 445), preservative (211), colour (110), flavours.
            """.trimIndent(),
            selectedCategory = ProductCategory.BEVERAGES_AND_JUICES,
        )

        assertEquals(NutritionBasis.PER_100_ML, report.nutritionBasis)
        assertEquals(56.0, report.nutrition.energyKcal ?: -1.0, 0.001)
        assertEquals(14.0, report.nutrition.carbohydrateG ?: -1.0, 0.001)
        assertEquals(13.7, report.nutrition.totalSugarG ?: -1.0, 0.001)
        assertEquals(22.3, report.nutrition.sodiumMg ?: -1.0, 0.001)
    }

    @Test
    fun `skips percent values while finding a value on following OCR lines`() {
        val report = ProductLabelAnalyzer.analyze(
            """
            Fanta Orange
            Nutrition information
            per 100 ml
            Energy
            5.6%
            56 kcal
            Carbohydrate
            14 g
            Total sugars
            13.7 g
            Sodium
            22.3 mg
            Ingredients: Carbonated water, sugar
            """.trimIndent(),
            selectedCategory = ProductCategory.BEVERAGES_AND_JUICES,
        )

        assertEquals(56.0, report.nutrition.energyKcal ?: -1.0, 0.001)
        assertEquals(14.0, report.nutrition.carbohydrateG ?: -1.0, 0.001)
        assertEquals(13.7, report.nutrition.totalSugarG ?: -1.0, 0.001)
        assertEquals(22.3, report.nutrition.sodiumMg ?: -1.0, 0.001)
    }
}
