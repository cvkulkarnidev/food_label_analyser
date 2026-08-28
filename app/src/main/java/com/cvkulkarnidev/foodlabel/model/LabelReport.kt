package com.cvkulkarnidev.foodlabel.model

data class NutritionFacts(
    val energyKcal: Double? = null,
    val proteinG: Double? = null,
    val carbohydrateG: Double? = null,
    val totalSugarG: Double? = null,
    val addedSugarG: Double? = null,
    val fibreG: Double? = null,
    val totalFatG: Double? = null,
    val saturatedFatG: Double? = null,
    val transFatG: Double? = null,
    val sodiumMg: Double? = null,
) {
    val knownCount: Int
        get() = listOf(
            energyKcal,
            proteinG,
            carbohydrateG,
            totalSugarG,
            addedSugarG,
            fibreG,
            totalFatG,
            saturatedFatG,
            transFatG,
            sodiumMg,
        ).count { it != null }
}

enum class NutritionBasis(val label: String) {
    PER_100_G("per 100 g"),
    PER_100_ML("per 100 ml"),
    PER_SERVING("per serving"),
    PER_PACK("per pack"),
    UNKNOWN("basis not detected"),
}

enum class ProductCategory(val label: String) {
    BEVERAGE("Beverage"),
    DAIRY("Dairy"),
    CEREAL("Cereal or breakfast food"),
    SNACK("Snack"),
    SAUCE_OR_SPREAD("Sauce or spread"),
    GENERAL("Packaged food"),
}

data class ScoreFactor(
    val title: String,
    val detail: String,
    val impact: Double,
) {
    val isPositive: Boolean get() = impact > 0
}

data class LabelReport(
    val productName: String,
    val category: ProductCategory,
    val ingredients: String?,
    val allergens: List<String>,
    val nutrition: NutritionFacts,
    val nutritionBasis: NutritionBasis,
    val servingSize: String?,
    val score: Double,
    val verdict: String,
    val confidence: Double,
    val factors: List<ScoreFactor>,
    val rawText: String,
)

