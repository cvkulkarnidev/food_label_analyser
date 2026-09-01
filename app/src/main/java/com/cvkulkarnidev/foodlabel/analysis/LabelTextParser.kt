package com.cvkulkarnidev.foodlabel.analysis

import com.cvkulkarnidev.foodlabel.model.NutritionBasis
import com.cvkulkarnidev.foodlabel.model.NutritionFacts
import com.cvkulkarnidev.foodlabel.model.ProductCategory

internal data class ParsedLabel(
    val productName: String,
    val category: ProductCategory,
    val ingredients: String?,
    val allergens: List<String>,
    val nutrition: NutritionFacts,
    val nutritionBasis: NutritionBasis,
    val servingSize: String?,
    val extractionWarnings: List<String>,
)

internal object LabelTextParser {
    private val numberRegex = Regex(
        "(?<![A-Za-z0-9])([0-9]+(?:[.,][0-9]+)?)\\s*(?:\\|\\s*)?(kcal|kj|mg|mcg|ml|g)?(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val basisValueRegex = Regex(
        "\\bper\\s*(?:\\|\\s*)?100\\s*(?:\\|\\s*)?(?:g|ml)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val servingRegex = Regex(
        "serv(?:ing|e)\\s*size\\s*[:|\\-]?\\s*([0-9]+(?:[.,][0-9]+)?\\s*(?:\\|\\s*)?(?:g|ml))",
        RegexOption.IGNORE_CASE,
    )

    fun parse(rawText: String): ParsedLabel {
        val normalizedText = NutritionTextNormalizer.normalize(rawText)
        val lines = normalizedText.text.lines()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }

        val ingredients = extractIngredients(lines)
        val detectedBasis = detectBasis(lines)
        val extractedNutrition = NutritionFacts(
            energyKcal = extractEnergy(lines),
            proteinG = extractNutrient(lines, listOf("protein"), "g"),
            carbohydrateG = extractNutrient(lines, listOf("total carbohydrate", "carbohydrate", "carbohydrates"), "g"),
            totalSugarG = extractNutrient(lines, listOf("total sugars", "total sugar", "sugars", "sugar"), "g", excluded = listOf("added")),
            addedSugarG = extractNutrient(lines, listOf("added sugars", "added sugar"), "g"),
            fibreG = extractNutrient(lines, listOf("dietary fibre", "dietary fiber", "fibre", "fiber"), "g"),
            totalFatG = extractNutrient(lines, listOf("total fat", "fat"), "g", excluded = listOf("saturated", "trans")),
            saturatedFatG = extractNutrient(lines, listOf("saturated fat", "saturates", "sat fat"), "g"),
            transFatG = extractNutrient(lines, listOf("trans fat", "transfat"), "g"),
            sodiumMg = extractSodium(lines),
        )
        val validation = validateNutrition(extractedNutrition, detectedBasis)

        return ParsedLabel(
            productName = findProductName(lines),
            category = detectCategory(lines, ingredients),
            ingredients = ingredients,
            allergens = extractAllergens(lines),
            nutrition = validation.nutrition,
            nutritionBasis = detectedBasis,
            servingSize = servingRegex.find(normalizedText.text)?.groupValues?.get(1)?.replace("|", "")?.replace(Regex("\\s+"), " "),
            extractionWarnings = (normalizedText.corrections + validation.warnings).distinct(),
        )
    }

    private fun validateNutrition(facts: NutritionFacts, basis: NutritionBasis): NutritionValidation {
        val warnings = mutableListOf<String>()
        var checked = facts
        val isPerHundred = basis == NutritionBasis.PER_100_G || basis == NutritionBasis.PER_100_ML

        fun plausibleGrams(name: String, value: Double?): Double? {
            if (value == null) return null
            if (value < 0 || (isPerHundred && value > 100.0)) {
                warnings += "$name was read as ${format(value)} g, which is impossible ${basis.label}; it was excluded from scoring."
                return null
            }
            return value
        }

        checked = checked.copy(
            energyKcal = checked.energyKcal?.takeIf { value ->
                val valid = value >= 0 && (!isPerHundred || value <= 1_000)
                if (!valid) warnings += "Energy was read as ${format(value)} kcal, which is implausible ${basis.label}; it was excluded from scoring."
                valid
            },
            proteinG = plausibleGrams("Protein", checked.proteinG),
            carbohydrateG = plausibleGrams("Carbohydrate", checked.carbohydrateG),
            totalSugarG = plausibleGrams("Total sugar", checked.totalSugarG),
            addedSugarG = plausibleGrams("Added sugar", checked.addedSugarG),
            fibreG = plausibleGrams("Fibre", checked.fibreG),
            totalFatG = plausibleGrams("Total fat", checked.totalFatG),
            saturatedFatG = plausibleGrams("Saturated fat", checked.saturatedFatG),
            transFatG = plausibleGrams("Trans fat", checked.transFatG),
            sodiumMg = checked.sodiumMg?.takeIf { value ->
                val valid = value >= 0 && (!isPerHundred || value <= 50_000)
                if (!valid) warnings += "Sodium was read as ${format(value)} mg, which is implausible ${basis.label}; it was excluded from scoring."
                valid
            },
        )

        if (checked.addedSugarG != null && checked.totalSugarG != null && checked.addedSugarG > checked.totalSugarG) {
            warnings += "Added sugar exceeded total sugar, so the added-sugar value was excluded pending confirmation."
            checked = checked.copy(addedSugarG = null)
        }
        if (checked.totalSugarG != null && checked.carbohydrateG != null && checked.totalSugarG > checked.carbohydrateG) {
            warnings += "Total sugar exceeded carbohydrate, so the sugar value was excluded pending confirmation."
            checked = checked.copy(totalSugarG = null, addedSugarG = null)
        }
        if (checked.saturatedFatG != null && checked.totalFatG != null && checked.saturatedFatG > checked.totalFatG) {
            warnings += "Saturated fat exceeded total fat, so the saturated-fat value was excluded pending confirmation."
            checked = checked.copy(saturatedFatG = null)
        }
        if (checked.transFatG != null && checked.totalFatG != null && checked.transFatG > checked.totalFatG) {
            warnings += "Trans fat exceeded total fat, so the trans-fat value was excluded pending confirmation."
            checked = checked.copy(transFatG = null)
        }

        if (isPerHundred) {
            val macroTotal = listOfNotNull(
                checked.proteinG,
                checked.carbohydrateG,
                checked.totalFatG,
                checked.fibreG,
            ).sum()
            if (macroTotal > 115.0) {
                warnings += "The extracted macronutrients total ${format(macroTotal)} g per 100 g/ml; verify that the correct table column was read."
            }
        }
        if ((checked.proteinG ?: 0.0) >= 60.0) {
            warnings += "Protein was read as ${format(checked.proteinG!!)} g; this unusually high value may be a merged ‘g/9’ OCR error."
        }
        if ((checked.fibreG ?: 0.0) >= 40.0) {
            warnings += "Fibre was read as ${format(checked.fibreG!!)} g; confirm this unusually high value."
        }
        if ((checked.transFatG ?: 0.0) > 10.0) {
            warnings += "Trans fat was read as ${format(checked.transFatG!!)} g; confirm this unusually high value."
        }

        return NutritionValidation(checked, warnings.distinct())
    }

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

    private data class NutritionValidation(
        val nutrition: NutritionFacts,
        val warnings: List<String>,
    )

    private fun findProductName(lines: List<String>): String {
        val ignored = Regex(
            "nutrition|ingredients?|serving|manufactured|marketed|best before|net (?:wt|weight|quantity)|fssai|customer care|allergen",
            RegexOption.IGNORE_CASE,
        )
        return lines.take(8).firstOrNull { line ->
            line.length in 3..70 &&
                !ignored.containsMatchIn(line) &&
                line.count(Char::isLetter) >= 3 &&
                line.count(Char::isDigit) < line.length / 2
        } ?: "Food product"
    }

    private fun extractIngredients(lines: List<String>): String? {
        val start = lines.indexOfFirst { Regex("^ingredients?\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        if (start < 0) return null

        val stop = Regex(
            "^(nutrition(?:al)?(?: information| facts)?|allerg(?:en|y)|serving|recommended usage|storage|manufactured|marketed|packed|best before|net (?:wt|weight|quantity)|customer care|fssai)\\b",
            RegexOption.IGNORE_CASE,
        )
        val parts = mutableListOf<String>()
        val first = lines[start].replaceFirst(Regex("^ingredients?\\s*[:\\-]?\\s*", RegexOption.IGNORE_CASE), "")
        if (first.isNotBlank()) parts += first
        for (index in start + 1 until minOf(lines.size, start + 12)) {
            if (stop.containsMatchIn(lines[index])) break
            parts += lines[index]
        }
        return parts.joinToString(" ").trim().takeIf { it.isNotBlank() }
    }

    private fun extractAllergens(lines: List<String>): List<String> {
        val allergenWords = listOf("milk", "soy", "soya", "wheat", "gluten", "peanut", "groundnut", "tree nut", "almond", "cashew", "egg", "sesame", "fish", "shellfish")
        val candidateLines = lines.filter {
            Regex("allerg(?:en|y)|may contain|contains", RegexOption.IGNORE_CASE).containsMatchIn(it)
        }
        return allergenWords.filter { word -> candidateLines.any { it.contains(word, ignoreCase = true) } }
            .map { it.replaceFirstChar(Char::uppercase) }
            .distinct()
    }

    private fun detectBasis(lines: List<String>): NutritionBasis {
        val header = lines.take(30).joinToString(" ").lowercase()
        return when {
            Regex("per\\s*100\\s*(?:\\|\\s*)?ml").containsMatchIn(header) -> NutritionBasis.PER_100_ML
            Regex("per\\s*100\\s*(?:\\|\\s*)?g").containsMatchIn(header) -> NutritionBasis.PER_100_G
            Regex("per\\s*(?:serve|serving)").containsMatchIn(header) -> NutritionBasis.PER_SERVING
            Regex("per\\s*pack").containsMatchIn(header) -> NutritionBasis.PER_PACK
            else -> NutritionBasis.UNKNOWN
        }
    }

    private fun detectCategory(lines: List<String>, ingredients: String?): ProductCategory {
        val text = (lines.take(12).joinToString(" ") + " " + ingredients.orEmpty()).lowercase()
        return when {
            listOf("juice", "drink", "beverage", "soda", "cola", "energy drink", "water").any(text::contains) -> ProductCategory.BEVERAGES_AND_JUICES
            listOf("ice cream", "icecream", "kulfi", "frozen dessert").any(text::contains) -> ProductCategory.ICE_CREAM_AND_DESSERTS
            listOf("milk", "yogurt", "yoghurt", "curd", "paneer", "cheese").any(text::contains) -> ProductCategory.DAIRY_AND_YOGURT
            listOf("chocolate", "candy", "toffee", "laddu", "ladoo", "burfi", "sweet").any(text::contains) -> ProductCategory.CHOCOLATE_AND_SWEETS
            listOf("sauce", "ketchup", "spread", "mayonnaise", "chutney", "pickle").any(text::contains) -> ProductCategory.SAUCES_AND_SPREADS
            listOf("chips", "namkeen", "mixture", "makhana", "cracker", "savoury", "savory", "snack").any(text::contains) -> ProductCategory.SAVOURY_SNACKS
            listOf("biscuit", "cookie", "cake", "wafer", "waffle", "rusk", "bread").any(text::contains) -> ProductCategory.BISCUITS_AND_BAKERY
            else -> ProductCategory.INSTANT_AND_READY_FOODS
        }
    }

    private fun extractEnergy(lines: List<String>): Double? {
        val hit = findValue(
            lines = lines,
            aliases = listOf("energy", "calories"),
            preferredUnits = setOf("kcal", "kj"),
        ) ?: return null
        val (value, unit) = hit
        return if (unit.equals("kj", ignoreCase = true)) value / 4.184 else value
    }

    private fun extractSodium(lines: List<String>): Double? {
        findValue(lines, listOf("sodium"), preferredUnits = setOf("mg", "mcg", "g"))?.let { (value, unit) ->
            return when {
                unit.equals("g", ignoreCase = true) -> value * 1000.0
                unit.equals("mcg", ignoreCase = true) -> value / 1000.0
                else -> value
            }
        }
        findValue(lines, listOf("salt"), preferredUnits = setOf("mg", "mcg", "g"))?.let { (value, unit) ->
            val saltMg = when {
                unit.equals("g", ignoreCase = true) -> value * 1000.0
                unit.equals("mcg", ignoreCase = true) -> value / 1000.0
                else -> value
            }
            return saltMg * 0.4
        }
        return null
    }

    private fun extractNutrient(
        lines: List<String>,
        aliases: List<String>,
        defaultUnit: String,
        excluded: List<String> = emptyList(),
    ): Double? {
        val hit = findValue(
            lines = lines,
            aliases = aliases,
            excluded = excluded,
            preferredUnits = setOf("g", "mg", "mcg"),
        ) ?: return null
        val (value, unit) = hit
        return when {
            defaultUnit == "g" && unit.equals("mg", ignoreCase = true) -> value / 1000.0
            defaultUnit == "g" && unit.equals("mcg", ignoreCase = true) -> value / 1_000_000.0
            else -> value
        }
    }

    private fun findValue(
        lines: List<String>,
        aliases: List<String>,
        excluded: List<String> = emptyList(),
        preferredUnits: Set<String>,
    ): Pair<Double, String?>? {
        for (index in lines.indices) {
            val lower = lines[index].lowercase()
            val alias = aliases.firstOrNull(lower::contains) ?: continue
            if (excluded.any(lower::contains)) continue

            val afterLabel = lines[index].substring(lower.indexOf(alias) + alias.length)
            parseNumber(afterLabel, preferredUnits)?.let { return it }

            for (offset in 1..2) {
                val nearby = lines.getOrNull(index + offset) ?: break
                if (nearby.length > 28 || nearby.count(Char::isLetter) > 6) break
                parseNumber(nearby, preferredUnits)?.let { return it }
            }
        }
        return null
    }

    private fun parseNumber(
        text: String,
        preferredUnits: Set<String>,
    ): Pair<Double, String?>? {
        val searchable = basisValueRegex.replace(text, " ")
        val candidates = numberRegex.findAll(searchable).mapNotNull { match ->
            val suffix = searchable
                .substring(match.range.last + 1)
                .trimStart()
            if (suffix.startsWith("%")) return@mapNotNull null

            val value = match.groupValues[1].replace(',', '.').toDoubleOrNull()
                ?: return@mapNotNull null
            val unit = match.groupValues
                .getOrNull(2)
                ?.takeIf(String::isNotBlank)
                ?.lowercase()
            NumberCandidate(value, unit)
        }.toList()

        val selected = candidates.firstOrNull { it.unit in preferredUnits }
            ?: candidates.firstOrNull { it.unit == null }
            ?: candidates.firstOrNull().takeIf { preferredUnits.isEmpty() }
            ?: return null
        return selected.value to selected.unit
    }

    private data class NumberCandidate(
        val value: Double,
        val unit: String?,
    )
}
