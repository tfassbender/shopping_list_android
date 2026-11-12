package com.tfassbender.shoppinglist.ui.list

import com.tfassbender.shoppinglist.domain.Category
import com.tfassbender.shoppinglist.domain.Item
import com.tfassbender.shoppinglist.domain.LayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ItemSectionsTest {

    private val t0 = Instant.parse("2026-01-01T10:00:00Z")
    private val dairy = Category("dairy", "Dairy", "#0000ff")
    private val veg = Category("veg", "Vegetables", "#00ff00")

    private fun item(id: String, category: String? = null, checked: Boolean = false) =
        Item(id, id, categoryId = category, checked = checked, createdAt = t0)

    @Test
    fun `amount shows quantity and unit, a unit alone is hidden`() {
        assertEquals("2 l", formatAmount("2", "l"))
        assertEquals("2", formatAmount("2", null))
        assertEquals("", formatAmount(null, "l"))
        assertEquals("", formatAmount("", ""))
    }

    @Test
    fun `valid amounts have at most one decimal separator`() {
        listOf("", "2", "0,5", "1.25", "1.", " 3 ").forEach { assertTrue(it, isValidAmount(it)) }
        listOf("1.2.3", "1,2,3", "a", "1 l").forEach { assertFalse(it, isValidAmount(it)) }
        assertTrue(isTypedAmount("1.2.3"))
        assertFalse(isTypedAmount("1a"))
    }

    @Test
    fun `categories mode starts a new section whenever the category changes`() {
        val sections = groupSorted(listOf(item("Milk", "dairy"), item("Cheese", "dairy"), item("Carrots", "veg"), item("Batteries"), item("Yogurt", "dairy")), listOf(veg, dairy), LayoutMode.CATEGORIES)
        assertEquals(listOf(ItemSection.Kind.CATEGORY, ItemSection.Kind.CATEGORY, ItemSection.Kind.OTHER, ItemSection.Kind.CATEGORY), sections.map { it.kind })
        assertEquals(listOf("Milk", "Cheese"), sections[0].items.map { it.name })
        assertEquals(dairy, sections[3].category)
        assertEquals(sections.size, sections.map { it.key }.toSet().size) // keys stay unique
    }

    @Test
    fun `items mode is one section without header, unknown categories count as none`() {
        val sections = groupSorted(listOf(item("A", "dairy"), item("B", "gone")), listOf(dairy), LayoutMode.ITEMS)
        assertEquals(listOf(ItemSection.Kind.ITEMS), sections.map { it.kind })
        val other = groupSorted(listOf(item("B", "gone")), listOf(dairy), LayoutMode.CATEGORIES)
        assertEquals(ItemSection.Kind.OTHER, other.single().kind)
    }

    @Test
    fun `only items moved by the sort button and still checked go to the checked section`() {
        val items = listOf(item("A", checked = true), item("B"), item("C", checked = true), item("D"))
        val sections = groupSorted(items, emptyList(), LayoutMode.ITEMS, movedToEnd = setOf("A", "D"))
        assertEquals(listOf("C", "B", "D").sorted(), sections[0].items.map { it.name }.sorted())
        assertEquals(listOf("B", "C", "D"), sections[0].items.map { it.name })
        assertEquals(ItemSection.Kind.CHECKED, sections[1].kind)
        assertEquals(listOf("A"), sections[1].items.map { it.name })
    }

    @Test
    fun `without headers the order stays and only the checked section is separate`() {
        val grouped = groupSorted(listOf(item("Milk", "dairy"), item("Carrots", "veg"), item("X", checked = true)), listOf(veg, dairy), LayoutMode.CATEGORIES, setOf("X"))
        val flat = withoutCategoryHeaders(grouped)
        assertEquals(listOf(ItemSection.Kind.ITEMS, ItemSection.Kind.CHECKED), flat.map { it.kind })
        assertEquals(listOf("Milk", "Carrots"), flat[0].items.map { it.name })
        assertTrue(withoutCategoryHeaders(emptyList()).isEmpty())
    }
}
