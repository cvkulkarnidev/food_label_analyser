package com.cvkulkarnidev.foodlabel.analysis

import com.cvkulkarnidev.foodlabel.model.NutritionBasis
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
}
