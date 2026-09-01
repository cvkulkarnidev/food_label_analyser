package com.cvkulkarnidev.foodlabel.analysis

data class IngredientAlertDefinition(
    val id: String,
    val label: String,
    val aliases: List<String>,
    val shortDescription: String,
    val reason: String,
)

data class IngredientAlertPreferences(
    val enabledPresetIds: Set<String> = IngredientAlerts.defaultPresetIds,
    val customTerms: Set<String> = emptySet(),
)

data class IngredientAlertMatch(
    val id: String,
    val label: String,
    val matchedTerms: List<String>,
    val ranges: List<IntRange>,
    val reason: String,
)

object IngredientAlerts {
    val presets: List<IngredientAlertDefinition> = listOf(
        IngredientAlertDefinition(
            id = "palm_oil",
            label = "Palm oil / palmolein",
            aliases = listOf("palm oil", "palmolein", "palm olein", "palm fat", "palm kernel oil"),
            shortDescription = "Palm-derived oils and fats",
            reason = "Palm-derived fat is on your personal watchlist; check its position in the ingredient order and the saturated-fat value.",
        ),
        IngredientAlertDefinition(
            id = "added_sugar",
            label = "Added or refined sugars",
            aliases = listOf(
                "sugar",
                "sucrose",
                "invert sugar",
                "glucose syrup",
                "dextrose",
                "maltose",
                "jaggery",
                "liquid glucose",
            ),
            shortDescription = "Sugar, syrups and common sugar names",
            reason = "Added sugars can raise calories without adding much nutritional value. Check the quantity, serving size and ingredient order.",
        ),
        IngredientAlertDefinition(
            id = "maida",
            label = "Maida / refined flour",
            aliases = listOf("maida", "refined wheat flour", "refined flour", "white flour"),
            shortDescription = "Highly refined wheat flour",
            reason = "Refined flour generally contains less fibre than whole-grain flour. Compare the fibre value and the first few ingredients.",
        ),
        IngredientAlertDefinition(
            id = "hydrogenated_fat",
            label = "Hydrogenated fat",
            aliases = listOf(
                "hydrogenated vegetable oil",
                "partially hydrogenated oil",
                "partially hydrogenated vegetable oil",
                "hydrogenated fat",
                "vegetable shortening",
            ),
            shortDescription = "Hydrogenated oils and shortening",
            reason = "Hydrogenated fats are worth checking alongside the trans-fat and saturated-fat values on the nutrition panel.",
        ),
        IngredientAlertDefinition(
            id = "high_fructose_syrup",
            label = "High-fructose syrup",
            aliases = listOf("high fructose corn syrup", "high-fructose corn syrup", "hfcs", "glucose-fructose syrup"),
            shortDescription = "HFCS and glucose-fructose syrup",
            reason = "This is an added-sugar source. Check total and added sugar per 100 g/ml as well as the serving size.",
        ),
        IngredientAlertDefinition(
            id = "artificial_sweeteners",
            label = "Artificial sweeteners",
            aliases = listOf(
                "artificial sweetener",
                "aspartame",
                "acesulfame potassium",
                "acesulfame k",
                "sucralose",
                "saccharin",
                "ins 950",
                "ins 951",
                "ins 954",
                "ins 955",
            ),
            shortDescription = "Aspartame, sucralose, acesulfame K and related additives",
            reason = "A non-sugar sweetener is present. This alert is informational; suitability depends on personal needs and the full product context.",
        ),
        IngredientAlertDefinition(
            id = "artificial_colours",
            label = "Artificial colours",
            aliases = listOf(
                "artificial colour",
                "artificial color",
                "tartrazine",
                "sunset yellow",
                "carmoisine",
                "allura red",
                "brilliant blue",
                "ins 102",
                "ins 110",
                "ins 122",
                "ins 124",
                "ins 129",
                "ins 133",
            ),
            shortDescription = "Common synthetic colours and INS codes",
            reason = "A colour additive on your watchlist was detected. Use the original label to verify the exact additive name or INS number.",
        ),
        IngredientAlertDefinition(
            id = "preservatives",
            label = "Selected preservatives",
            aliases = listOf(
                "preservative",
                "sodium benzoate",
                "potassium sorbate",
                "calcium propionate",
                "ins 202",
                "ins 211",
                "ins 282",
            ),
            shortDescription = "Common preservative names and INS codes",
            reason = "A selected preservative was detected. This is a label-reading alert, not a statement that the product is unsafe.",
        ),
        IngredientAlertDefinition(
            id = "msg",
            label = "MSG",
            aliases = listOf("monosodium glutamate", "msg", "ins 621"),
            shortDescription = "Monosodium glutamate / INS 621",
            reason = "MSG is on your personal watchlist. Verify the ingredient in the original label, especially when OCR confidence is low.",
        ),
    )

    val defaultPresetIds: Set<String> = presets.mapTo(linkedSetOf(), IngredientAlertDefinition::id)

    fun findMatches(
        ingredients: String?,
        preferences: IngredientAlertPreferences,
    ): List<IngredientAlertMatch> {
        if (ingredients.isNullOrBlank()) return emptyList()

        val presetMatches = presets
            .asSequence()
            .filter { it.id in preferences.enabledPresetIds }
            .mapNotNull { definition ->
                definition.toMatch(ingredients)
            }
            .toList()

        val customMatches = preferences.customTerms
            .asSequence()
            .map { it.trim() }
            .filter { it.length in 2..40 }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
            .mapNotNull { term ->
                val ranges = findRanges(ingredients, term)
                if (ranges.isEmpty()) {
                    null
                } else {
                    IngredientAlertMatch(
                        id = "custom:${term.lowercase()}",
                        label = term,
                        matchedTerms = ranges
                            .map { ingredients.substring(it) }
                            .distinctBy { it.lowercase() },
                        ranges = ranges,
                        reason = "This matches a custom ingredient you chose to watch.",
                    )
                }
            }
            .toList()

        return presetMatches + customMatches
    }

    private fun IngredientAlertDefinition.toMatch(text: String): IngredientAlertMatch? {
        val ranges = aliases
            .flatMap { alias -> findRanges(text, alias) }
            .distinct()
            .sortedBy { it.first }
        if (ranges.isEmpty()) return null
        return IngredientAlertMatch(
            id = id,
            label = label,
            matchedTerms = ranges
                .map { text.substring(it) }
                .distinctBy { it.lowercase() },
            ranges = ranges,
            reason = reason,
        )
    }

    private fun findRanges(text: String, term: String): List<IntRange> {
        val words = term
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()

        val flexibleTerm = words.joinToString("""[\s_-]+""") { Regex.escape(it) }
        val expression = Regex(
            pattern = """(?<![\p{L}\p{N}])$flexibleTerm(?![\p{L}\p{N}])""",
            option = RegexOption.IGNORE_CASE,
        )
        return expression.findAll(text).map { it.range }.toList()
    }
}
