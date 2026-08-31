package com.cvkulkarnidev.foodlabel.analysis

internal data class NormalizedNutritionText(
    val text: String,
    val corrections: List<String>,
)

/**
 * Repairs only high-confidence OCR confusions whose position supplies the missing context.
 * Deliberately does not replace every 9 with g: values such as vitamin B9 and 9 kcal must
 * remain untouched.
 */
internal object NutritionTextNormalizer {
    private val nutrientLabel = Regex(
        "\\b(?:energy|calories?|protein|carbohydrates?|total sugars?|added sugars?|sugars?|" +
            "dietary fib(?:re|er)|fib(?:re|er)|total fat|saturated fat|trans fat|sodium|salt)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val perHundredG = Regex("(?i)\\bper\\s*100\\s*9\\b")
    private val perHundredMl = Regex("(?i)\\bper\\s*100\\s*(?:rn|m)[1lI]\\b")
    private val brokenMg = Regex("(?i)(\\d(?:[0-9.,]*))\\s*(?:m9|rn9)\\b")
    private val brokenKcal = Regex("(?i)\\bkca[1lI]\\b")
    private val bracketedG = Regex("(?i)\\(\\s*9\\s*\\)")
    private val trailingG = Regex("(?i)([0-9]+(?:[.,][0-9]+)?)\\s+9(?=\\s*(?:$|[|;]))")

    fun normalize(rawText: String): NormalizedNutritionText {
        val corrections = mutableListOf<String>()
        val normalizedLines = rawText.lineSequence().map { original ->
            var line = original
            line = replace(line, perHundredG, "per 100 g", "Corrected ‘per 100 9’ to ‘per 100 g’. ", corrections)
            line = replace(line, perHundredMl, "per 100 ml", "Corrected a likely ‘per 100 ml’ OCR error.", corrections)
            line = replace(line, brokenKcal, "kcal", "Corrected a likely kcal OCR error.", corrections)

            if (nutrientLabel.containsMatchIn(line)) {
                val beforeMg = line
                line = brokenMg.replace(line) { "${it.groupValues[1]} mg" }
                if (line != beforeMg) corrections += "Corrected ‘m9’ to ‘mg’ in a nutrient row."

                val beforeBracket = line
                line = bracketedG.replace(line, "(g)")
                if (line != beforeBracket) corrections += "Corrected ‘(9)’ to ‘(g)’ in a nutrient unit position."

                val beforeTrailing = line
                line = trailingG.replace(line) { "${it.groupValues[1]} g" }
                if (line != beforeTrailing) corrections += "Corrected a trailing ‘9’ to ‘g’ in a nutrient unit position."
            }
            line
        }.joinToString("\n")

        return NormalizedNutritionText(
            text = normalizedLines,
            corrections = corrections.distinct(),
        )
    }

    private fun replace(
        text: String,
        regex: Regex,
        replacement: String,
        note: String,
        corrections: MutableList<String>,
    ): String {
        val updated = regex.replace(text, replacement)
        if (updated != text) corrections += note.trim()
        return updated
    }
}
