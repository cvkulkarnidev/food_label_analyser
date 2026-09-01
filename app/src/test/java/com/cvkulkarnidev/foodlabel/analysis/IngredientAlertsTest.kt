package com.cvkulkarnidev.foodlabel.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientAlertsTest {
    @Test
    fun defaultsIncludeRequestedWatchItems() {
        assertTrue("palm_oil" in IngredientAlerts.defaultPresetIds)
        assertTrue("added_sugar" in IngredientAlerts.defaultPresetIds)
        assertTrue("maida" in IngredientAlerts.defaultPresetIds)
    }

    @Test
    fun matchesPalmoleinMaidaAndSugarAcrossOcrLineBreaks() {
        val text = "Ingredients: Refined Palmolein Oil, Refined Wheat\nFlour (Maida), Sugar, Salt"
        val matches = IngredientAlerts.findMatches(text, IngredientAlertPreferences())
        val ids = matches.mapTo(mutableSetOf(), IngredientAlertMatch::id)

        assertTrue("palm_oil" in ids)
        assertTrue("maida" in ids)
        assertTrue("added_sugar" in ids)
        assertTrue(matches.flatMap(IngredientAlertMatch::ranges).all { it.first >= 0 && it.last < text.length })
    }

    @Test
    fun disabledPresetIsNotReported() {
        val preferences = IngredientAlertPreferences(
            enabledPresetIds = IngredientAlerts.defaultPresetIds - "added_sugar",
        )

        val matches = IngredientAlerts.findMatches("Ingredients: sugar, cocoa", preferences)

        assertFalse(matches.any { it.id == "added_sugar" })
    }

    @Test
    fun matchesCustomIngredientWithoutSubstringFalsePositive() {
        val preferences = IngredientAlertPreferences(
            enabledPresetIds = emptySet(),
            customTerms = setOf("pea"),
        )

        assertTrue(IngredientAlerts.findMatches("Ingredients: pea protein", preferences).isNotEmpty())
        assertTrue(IngredientAlerts.findMatches("Ingredients: peach puree", preferences).isEmpty())
    }

    @Test
    fun returnsOriginalMatchedTextForHighlighting() {
        val text = "Ingredients: PALM OIL"
        val match = IngredientAlerts.findMatches(text, IngredientAlertPreferences()).single()

        assertEquals("PALM OIL", match.matchedTerms.single())
        assertEquals("PALM OIL", text.substring(match.ranges.single()))
    }
}
