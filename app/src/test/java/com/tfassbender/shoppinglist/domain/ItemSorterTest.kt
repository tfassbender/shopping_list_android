package com.tfassbender.shoppinglist.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Mirrors the server's `ItemSorterTest` (backend/.../domain/list) - keep both in sync. */
class ItemSorterTest {

    private val t0 = Instant.parse("2026-01-01T10:00:00Z")
    private val veg = "veg"
    private val dairy = "dairy"
    private val bakery = "bakery"

    // Space default order: veg, dairy, bakery
    private val categories = listOf(Category(veg, "Vegetables", "#00ff00"), Category(dairy, "Dairy", "#0000ff"), Category(bakery, "Bakery", "#aa8800"))

    private var created = 0L
    private fun item(name: String, category: String? = null, checked: Boolean = false) =
        Item(name.replace(' ', '-'), name, categoryId = category, checked = checked, createdAt = t0.plusSeconds(created++), seq = 1)

    private fun layout(mode: LayoutMode = LayoutMode.CATEGORIES, categories: List<String> = emptyList(), items: List<String> = emptyList()) =
        ShopLayout("shop", "Shop", mode, categories, items, emptyList(), t0)

    private fun sortedNames(
        items: List<Item>,
        layout: ShopLayout,
        usage: Map<String, Int> = emptyMap(),
        checkedToEnd: Boolean = false,
    ) = ItemSorter.sort(items, layout, categories, usage, checkedToEnd).map { it.name }

    // --- category mode ---

    @Test
    fun `items follow the layout's category order`() {
        val items = listOf(item("Milk", dairy), item("Bread", bakery), item("Carrots", veg))
        assertEquals(listOf("Bread", "Milk", "Carrots"), sortedNames(items, layout(categories = listOf(bakery, dairy, veg))))
    }

    @Test
    fun `categories missing in the layout follow in the space's default order, uncategorized last`() {
        val items = listOf(item("Batteries"), item("Milk", dairy), item("Bread", bakery), item("Carrots", veg))
        assertEquals(listOf("Bread", "Carrots", "Milk", "Batteries"), sortedNames(items, layout(categories = listOf(bakery))))
    }

    @Test
    fun `deleted categories in the layout or on items are ignored`() {
        val items = listOf(item("Ghost", "deleted"), item("Milk", dairy))
        assertEquals(listOf("Milk", "Ghost"), sortedNames(items, layout(categories = listOf("deleted", dairy))))
    }

    @Test
    fun `new items are placed automatically by their category`() {
        val items = listOf(item("Bread", bakery), item("Brand new cheese", dairy))
        assertEquals(listOf("Brand new cheese", "Bread"), sortedNames(items, layout(categories = listOf(dairy, bakery))))
    }

    // --- item mode ---

    @Test
    fun `items follow the layout's item order case-insensitively`() {
        val items = listOf(item("milk"), item("Bread"), item("Apples"))
        val layout = layout(LayoutMode.ITEMS, items = listOf("BREAD", "Apples", "Milk"))
        assertEquals(listOf("Bread", "Apples", "milk"), sortedNames(items, layout))
    }

    @Test
    fun `items unknown to the layout come last, sorted by the remaining rules`() {
        val items = listOf(item("Zucchini"), item("Avocado"), item("Bread"))
        assertEquals(listOf("Bread", "Avocado", "Zucchini"), sortedNames(items, layout(LayoutMode.ITEMS, items = listOf("Bread"))))
    }

    @Test
    fun `category order is ignored in item mode`() {
        val items = listOf(item("Milk", dairy), item("Carrots", veg))
        val layout = layout(LayoutMode.ITEMS, categories = listOf(veg, dairy), items = listOf("Milk", "Carrots"))
        assertEquals(listOf("Milk", "Carrots"), sortedNames(items, layout))
    }

    // --- tie breakers ---

    @Test
    fun `most bought first within the same layout position`() {
        val usage = mapOf("cheese" to 1, "butter" to 2)
        val items = listOf(item("Cheese", dairy), item("Butter", dairy), item("Ayran", dairy), item("Quark", dairy))
        assertEquals(listOf("Butter", "Cheese", "Ayran", "Quark"), sortedNames(items, layout(categories = listOf(dairy)), usage))
    }

    @Test
    fun `name is the last criterion, case-insensitive`() {
        val items = listOf(item("banana"), item("Apple"), item("cherry"))
        assertEquals(listOf("Apple", "banana", "cherry"), sortedNames(items, layout()))
    }

    @Test
    fun `same name keeps creation order (stable)`() {
        val first = item("Milk", dairy).copy(id = "first")
        val second = item("Milk", dairy).copy(id = "second")
        val result = ItemSorter.sort(listOf(second, first), layout(), categories, emptyMap(), false)
        assertEquals(listOf("first", "second"), result.map { it.id })
    }

    // --- checked to end ---

    private val checkedItems by lazy {
        listOf(item("Bread", bakery, checked = true), item("Milk", dairy), item("Carrots", veg, checked = true), item("Cheese", dairy))
    }
    private val checkedLayout by lazy { layout(categories = listOf(bakery, dairy, veg)) }

    @Test
    fun `checked items go to the end, both groups sorted by the layout`() {
        assertEquals(listOf("Cheese", "Milk", "Bread", "Carrots"), sortedNames(checkedItems, checkedLayout, checkedToEnd = true))
    }

    @Test
    fun `without the setting checked items stay in place`() {
        assertEquals(listOf("Bread", "Cheese", "Milk", "Carrots"), sortedNames(checkedItems, checkedLayout, checkedToEnd = false))
    }

    @Test
    fun `empty list sorts to empty`() {
        assertTrue(ItemSorter.sort(emptyList(), layout(), categories, emptyMap(), true).isEmpty())
    }

    // --- app only ---

    @Test
    fun `without a shop items keep the order of adding, checked last if wanted`() {
        val b = item("B", checked = true)
        val a = item("A")
        val c = item("C")
        val items = listOf(c, b, a) // stored order doesn't matter, the creation time does
        assertEquals(listOf("B", "A", "C"), ItemSorter.byAdding(items, checkedToEnd = false).map { it.name })
        assertEquals(listOf("A", "C", "B"), ItemSorter.byAdding(items, checkedToEnd = true).map { it.name })
    }

    @Test
    fun `connected shops are offered first`() {
        val aldi = ShopLayout("a", "Aldi", LayoutMode.CATEGORIES, listIds = listOf("other"), createdAt = t0)
        val rewe = ShopLayout("r", "Rewe", LayoutMode.CATEGORIES, listIds = listOf("groceries"), createdAt = t0)
        val baumarkt = ShopLayout("b", "Baumarkt", LayoutMode.ITEMS, createdAt = t0)
        assertEquals(listOf("Rewe", "Aldi", "Baumarkt"), ItemSorter.shopChoices("groceries", listOf(aldi, baumarkt, rewe)).map { it.name })
    }
}
