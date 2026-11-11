package com.tfassbender.shoppinglist.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SuggestionsTest {

    private val t0 = Instant.parse("2026-01-01T10:00:00Z")
    private var created = 0L
    private fun item(name: String, category: String? = null, unit: String? = null) =
        Item(name + created, name, unit = unit, categoryId = category, createdAt = t0.plusSeconds(created++))

    private val lists = listOf(
        ShoppingList("l1", "s", "Groceries", t0, items = listOf(item("Milk", "dairy"), item("Oat milk", "dairy", "l"), item("Mints"))),
        ShoppingList("l2", "s", "Weekend", t0, items = listOf(item("milk", "drinks", "l"), item("Bread"))),
    )

    @Test
    fun `name prefix before word prefix, most used first, newest category and unit`() {
        val result = localSuggestions("mi", lists, usage = mapOf("mints" to 5, "milk" to 2))

        assertEquals(listOf("Mints", "milk", "Oat milk"), result.map { it.name })
        assertEquals("drinks", result[1].categoryId) // the newer "milk" wins
        assertEquals(5, result[0].usageCount)
    }

    @Test
    fun `blank query or no match gives nothing, limit applies`() {
        assertEquals(emptyList<Suggestion>(), localSuggestions(" ", lists, emptyMap()))
        assertEquals(emptyList<Suggestion>(), localSuggestions("xyz", lists, emptyMap()))
        assertEquals(1, localSuggestions("m", lists, emptyMap(), limit = 1).size)
    }
}
