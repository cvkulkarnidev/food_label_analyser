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
    BISCUITS_AND_BAKERY("Biscuits & bakery"),
    BEVERAGES_AND_JUICES("Juices & beverages"),
    SAVOURY_SNACKS("Savoury snacks"),
    CHOCOLATE_AND_SWEETS("Chocolate & sweets"),
    INSTANT_AND_READY_FOODS("Instant & ready foods"),
    SAUCES_AND_SPREADS("Sauces & spreads"),
    DAIRY_AND_YOGURT("Dairy & yoghurt"),
    ICE_CREAM_AND_DESSERTS("Ice cream & desserts"),
}

data class PeerComparison(
    val percentile: Int,
    val peerCount: Int,
    val position: String,
    val isSmallSample: Boolean,
)

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
    val peerComparison: PeerComparison,
    val rawText: String,
)
