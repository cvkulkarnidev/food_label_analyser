package com.cvkulkarnidev.foodlabel.analysis

import com.cvkulkarnidev.foodlabel.model.NutritionBasis
import com.cvkulkarnidev.foodlabel.model.NutritionFacts
import com.cvkulkarnidev.foodlabel.model.ProductCategory
import com.cvkulkarnidev.foodlabel.model.ScoreFactor
import kotlin.math.round

internal data class ScoreResult(
    val score: Double,
    val verdict: String,
    val confidence: Double,
    val factors: List<ScoreFactor>,
)

internal object HealthScorer {
    private val sugarTerms = listOf(
        "sugar", "glucose", "fructose", "corn syrup", "invert syrup", "maltose", "dextrose", "jaggery", "honey",
    )
    private val wholeFoodTerms = listOf(
        "whole grain", "whole wheat", "oats", "millet", "ragi", "jowar", "bajra", "lentil", "chickpea", "nuts", "seeds", "fruit", "vegetable",
    )
    private val refinedTerms = listOf("refined wheat flour", "maida", "maltodextrin", "hydrogenated", "palm oil")
    private val additiveRegex = Regex("\\b(?:INS|E)[ -]?\\d{3,4}[a-z]?\\b", RegexOption.IGNORE_CASE)

    fun score(parsed: ParsedLabel): ScoreResult {
        val normalized = normalizeTo100(parsed.nutrition, parsed.nutritionBasis, parsed.servingSize)
        val factors = mutableListOf<ScoreFactor>()
        var rawScore = 4.5

        val sugar = normalized.addedSugarG ?: normalized.totalSugarG
        if (sugar != null) {
            val beverage = parsed.category == ProductCategory.BEVERAGES_AND_JUICES
            val penalty = when {
                beverage && sugar > 11.25 -> -1.5
                beverage && sugar > 5.0 -> -0.9
                beverage && sugar > 2.5 -> -0.4
                sugar > 22.5 -> -1.2
                sugar > 10.0 -> -0.75
                sugar > 5.0 -> -0.3
                else -> 0.0
            }
            if (penalty < 0) factors += ScoreFactor(
                title = if (normalized.addedSugarG != null) "Added sugar" else "Total sugar",
                detail = "${format(sugar)} g ${basisDetail(parsed)}",
                impact = penalty,
            ) else factors += ScoreFactor("Sugar", "Low at ${format(sugar)} g ${basisDetail(parsed)}", 0.2)
            rawScore += if (penalty < 0) penalty else 0.2
        }

        normalized.sodiumMg?.let { sodium ->
            val penalty = when {
                sodium > 600 -> -1.0
                sodium > 400 -> -0.65
                sodium > 120 -> -0.3
                else -> 0.0
            }
            if (penalty < 0) factors += ScoreFactor("Sodium", "${format(sodium)} mg ${basisDetail(parsed)}", penalty)
            else factors += ScoreFactor("Sodium", "Low at ${format(sodium)} mg ${basisDetail(parsed)}", 0.2)
            rawScore += if (penalty < 0) penalty else 0.2
        }

        normalized.saturatedFatG?.let { saturatedFat ->
            val penalty = when {
                saturatedFat > 10 -> -1.0
                saturatedFat > 5 -> -0.65
                saturatedFat > 1.5 -> -0.3
                else -> 0.0
            }
            if (penalty < 0) factors += ScoreFactor("Saturated fat", "${format(saturatedFat)} g ${basisDetail(parsed)}", penalty)
            rawScore += penalty
        }

        normalized.transFatG?.let { transFat ->
            val penalty = when {
                transFat > 1 -> -0.8
                transFat > 0.2 -> -0.45
                else -> 0.0
            }
            if (penalty < 0) factors += ScoreFactor("Trans fat", "${format(transFat)} g ${basisDetail(parsed)}", penalty)
            rawScore += penalty
        }

        normalized.fibreG?.let { fibre ->
            val bonus = when {
                fibre >= 6 -> 0.35
                fibre >= 3 -> 0.2
                else -> 0.0
            }
            if (bonus > 0) factors += ScoreFactor("Fibre", "Good source at ${format(fibre)} g ${basisDetail(parsed)}", bonus)
            rawScore += bonus
        }

        normalized.proteinG?.let { protein ->
            val bonus = when {
                protein >= 10 -> 0.25
                protein >= 5 -> 0.1
                else -> 0.0
            }
            if (bonus > 0) factors += ScoreFactor("Protein", "${format(protein)} g ${basisDetail(parsed)}", bonus)
            rawScore += bonus
        }

        val ingredients = parsed.ingredients.orEmpty().lowercase()
        if (ingredients.isNotBlank()) {
            val firstIngredients = ingredients.split(',', ';').take(3).joinToString(" ")
            when {
                sugarTerms.any(firstIngredients::contains) -> {
                    factors += ScoreFactor("Ingredient order", "A sugar source appears among the first ingredients", -0.55)
                    rawScore -= 0.55
                }
                wholeFoodTerms.any(firstIngredients::contains) -> {
                    factors += ScoreFactor("Ingredient quality", "A whole-food ingredient appears near the top", 0.25)
                    rawScore += 0.25
                }
            }

            val refinedHits = refinedTerms.count(ingredients::contains)
            if (refinedHits > 0) {
                val penalty = if (refinedHits >= 2) -0.4 else -0.2
                factors += ScoreFactor("Processed ingredients", "Contains ${refinedHits} ingredient${if (refinedHits == 1) "" else "s"} worth noting", penalty)
                rawScore += penalty
            }

            val additiveCount = additiveRegex.findAll(ingredients).count()
            if (additiveCount >= 3) {
                factors += ScoreFactor("Additives", "$additiveCount labelled additives detected", -0.2)
                rawScore -= 0.2
            }
        }

        val confidence = calculateConfidence(parsed)
        // Pull low-evidence scores toward neutral instead of rewarding missing OCR fields.
        val conservative = (rawScore.coerceIn(0.5, 5.0) * confidence) + (3.0 * (1.0 - confidence))
        val score = (round(conservative.coerceIn(0.5, 5.0) * 10) / 10)
        val verdict = when {
            score >= 4.5 -> "Excellent"
            score >= 3.7 -> "Good choice"
            score >= 2.8 -> "Moderate"
            score >= 1.8 -> "Limit often"
            else -> "Occasional choice"
        }

        if (factors.isEmpty()) {
            factors += ScoreFactor("Limited label data", "The score is held near neutral until more fields can be read", 0.0)
        }
        return ScoreResult(score, verdict, confidence, factors.sortedBy { it.impact })
    }

    private fun calculateConfidence(parsed: ParsedLabel): Double {
        var confidence = 0.18 + parsed.nutrition.knownCount * 0.065
        if (!parsed.ingredients.isNullOrBlank()) confidence += 0.16
        if (parsed.nutritionBasis != NutritionBasis.UNKNOWN) confidence += 0.1
        if (!parsed.servingSize.isNullOrBlank()) confidence += 0.04
        return confidence.coerceIn(0.25, 0.96)
    }

    private fun normalizeTo100(
        facts: NutritionFacts,
        basis: NutritionBasis,
        servingSize: String?,
    ): NutritionFacts {
        if (basis != NutritionBasis.PER_SERVING || servingSize == null) return facts
        val amount = Regex("[0-9]+(?:[.,][0-9]+)?").find(servingSize)?.value?.replace(',', '.')?.toDoubleOrNull()
            ?: return facts
        if (amount <= 0) return facts
        val multiplier = 100.0 / amount
        return facts.copy(
            energyKcal = facts.energyKcal?.times(multiplier),
            proteinG = facts.proteinG?.times(multiplier),
            carbohydrateG = facts.carbohydrateG?.times(multiplier),
            totalSugarG = facts.totalSugarG?.times(multiplier),
            addedSugarG = facts.addedSugarG?.times(multiplier),
            fibreG = facts.fibreG?.times(multiplier),
            totalFatG = facts.totalFatG?.times(multiplier),
            saturatedFatG = facts.saturatedFatG?.times(multiplier),
            transFatG = facts.transFatG?.times(multiplier),
            sodiumMg = facts.sodiumMg?.times(multiplier),
        )
    }

    private fun basisDetail(parsed: ParsedLabel): String = when (parsed.nutritionBasis) {
        NutritionBasis.PER_SERVING -> if (parsed.servingSize != null) "per 100 g/ml (normalized)" else "per serving"
        NutritionBasis.UNKNOWN, NutritionBasis.PER_PACK -> "on the detected label basis"
        else -> parsed.nutritionBasis.label
    }

    private fun format(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
}
