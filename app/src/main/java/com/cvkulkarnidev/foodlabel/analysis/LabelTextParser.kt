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
)

internal object LabelTextParser {
    private val numberRegex = Regex("(?<![A-Za-z])([0-9]+(?:[.,][0-9]+)?)\\s*(kcal|kj|mg|mcg|g)?", RegexOption.IGNORE_CASE)
    private val servingRegex = Regex("serv(?:ing|e)\\s*size\\s*[:\\-]?\\s*([0-9]+(?:[.,][0-9]+)?\\s*(?:g|ml))", RegexOption.IGNORE_CASE)

    fun parse(rawText: String): ParsedLabel {
        val lines = rawText.lines()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }

        val ingredients = extractIngredients(lines)
        val nutrition = NutritionFacts(
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

        return ParsedLabel(
            productName = findProductName(lines),
            category = detectCategory(lines, ingredients),
            ingredients = ingredients,
            allergens = extractAllergens(lines),
            nutrition = nutrition,
            nutritionBasis = detectBasis(lines),
            servingSize = servingRegex.find(rawText)?.groupValues?.get(1),
        )
    }

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
            Regex("per\\s*100\\s*ml").containsMatchIn(header) -> NutritionBasis.PER_100_ML
            Regex("per\\s*100\\s*g").containsMatchIn(header) -> NutritionBasis.PER_100_G
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
        val hit = findValue(lines, listOf("energy", "calories")) ?: return null
        val (value, unit) = hit
        return if (unit.equals("kj", ignoreCase = true)) value / 4.184 else value
    }

    private fun extractSodium(lines: List<String>): Double? {
        findValue(lines, listOf("sodium"))?.let { (value, unit) ->
            return if (unit.equals("g", ignoreCase = true)) value * 1000.0 else value
        }
        findValue(lines, listOf("salt"))?.let { (value, unit) ->
            return if (unit.equals("g", ignoreCase = true)) value * 400.0 else value * 0.4
        }
        return null
    }

    private fun extractNutrient(
        lines: List<String>,
        aliases: List<String>,
        defaultUnit: String,
        excluded: List<String> = emptyList(),
    ): Double? {
        val hit = findValue(lines, aliases, excluded) ?: return null
        val (value, unit) = hit
        return when {
            defaultUnit == "g" && unit.equals("mg", ignoreCase = true) -> value / 1000.0
            else -> value
        }
    }

    private fun findValue(
        lines: List<String>,
        aliases: List<String>,
        excluded: List<String> = emptyList(),
    ): Pair<Double, String?>? {
        for (index in lines.indices) {
            val lower = lines[index].lowercase()
            val alias = aliases.firstOrNull(lower::contains) ?: continue
            if (excluded.any(lower::contains)) continue

            val afterLabel = lines[index].substring(lower.indexOf(alias) + alias.length)
            parseNumber(afterLabel)?.let { return it }

            for (offset in 1..2) {
                val nearby = lines.getOrNull(index + offset) ?: break
                if (nearby.length > 28 || nearby.count(Char::isLetter) > 6) break
                parseNumber(nearby)?.let { return it }
            }
        }
        return null
    }

    private fun parseNumber(text: String): Pair<Double, String?>? {
        val match = numberRegex.find(text) ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return value to match.groupValues.getOrNull(2)?.takeIf(String::isNotBlank)
    }
}
