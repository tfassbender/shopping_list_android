package com.tfassbender.shoppinglist.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Mirrors the suggestion cases of the server's `SpaceCatalogTest` (and the web's `rankSuggestions` tests). */
class SuggestionsTest {

    private val t0 = Instant.parse("2026-01-01T10:00:00Z")

    private val history = listOf(
        HistoryEntry("Bread", "bakery", null, 1, t0),
        HistoryEntry("Milk", "dairy", "l", 2, t0),
        HistoryEntry("Mineral water", null, null, 3, t0),
        HistoryEntry("Mint", null, null, 0, t0.plusSeconds(10)),
        HistoryEntry("Oat milk", "dairy", null, 4, t0),
    )

    @Test
    fun `name prefix matches rank before word matches, then by usage`() {
        assertEquals(listOf("Mineral water", "Milk", "Mint", "Oat milk"), rankSuggestions(history, "mi", 10).map { it.name })
    }

    @Test
    fun `matching is case-insensitive and limited`() {
        assertEquals(listOf("Mineral water", "Milk"), rankSuggestions(history, "MI", 2).map { it.name })
        assertEquals(listOf("Milk", "Oat milk"), rankSuggestions(history, "milk", 10).map { it.name })
    }

    @Test
    fun `recency breaks ties in usage`() {
        val tied = listOf(HistoryEntry("Apple", null, null, 0, t0), HistoryEntry("Apricot", null, null, 0, t0.plusSeconds(1)))
        assertEquals(listOf("Apricot", "Apple"), rankSuggestions(tied, "ap", 5).map { it.name })
    }

    @Test
    fun `blank query and no matches give nothing`() {
        assertTrue(rankSuggestions(history, "  ", 10).isEmpty())
        assertTrue(rankSuggestions(history, "xyz", 10).isEmpty())
    }

    @Test
    fun `suggestions carry the last category and unit`() {
        val milk = rankSuggestions(history, "milk", 1).single()
        assertEquals(Suggestion("Milk", "dairy", "l", 2), milk)
    }

    @Test
    fun `merging history changes replaces entries by normalized name and updates usage`() {
        val catalog = Catalog(usage = mapOf("milk" to 2, "bread" to 1), history = history)
        val merged = catalog.mergeHistory(
            listOf(HistoryEntry("MILK ", "dairy", "l", 3, t0.plusSeconds(5)), HistoryEntry("Butter", "dairy", null, 0, t0)),
        )
        assertEquals(3, merged.usage["milk"])
        assertEquals(null, merged.usage["butter"])
        assertEquals(listOf("Bread", "Butter", "MILK ", "Mineral water", "Mint", "Oat milk"), merged.history.map { it.name })
    }
}
