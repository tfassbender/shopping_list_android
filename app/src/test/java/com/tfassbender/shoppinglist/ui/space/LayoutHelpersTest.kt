package com.tfassbender.shoppinglist.ui.space

import com.tfassbender.shoppinglist.domain.Category
import com.tfassbender.shoppinglist.domain.LayoutMode
import com.tfassbender.shoppinglist.domain.ShopLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Mirrors the web app's layout helper tests (`LayoutPages.test.tsx`). */
class LayoutHelpersTest {

    private val categories = listOf(Category("veg", "Vegetables", "#00ff00"), Category("dairy", "Dairy", "#0000ff"), Category("bakery", "Bakery", "#aa8800"))
    private val layout = ShopLayout("shop", "Shop", LayoutMode.CATEGORIES, createdAt = Instant.EPOCH)

    @Test
    fun `shows switched-off categories last in the editor, without changing the saved order`() {
        val withOff = layout.copy(categoryOrder = listOf("bakery", "dairy", "veg"), disabledCategories = listOf("bakery"))
        assertEquals(listOf("dairy", "veg", "bakery"), categoriesForEditor(withOff, categories).map { it.id })
        // switched on again: back at its old place
        assertEquals(listOf("bakery", "dairy", "veg"), categoriesForEditor(withOff.copy(disabledCategories = emptyList()), categories).map { it.id })
    }

    @Test
    fun `a drag in the editor keeps the saved place of switched-off categories`() {
        val off = setOf("dairy")
        // saved: bakery, dairy (off), veg - shown: bakery, veg, dairy - dragged: veg, bakery, dairy
        assertEquals(listOf("veg", "dairy", "bakery"), savedOrderAfterDrag(listOf("bakery", "dairy", "veg"), listOf("veg", "bakery", "dairy"), off))
        assertEquals(listOf("bakery", "dairy", "veg"), savedOrderAfterDrag(listOf("bakery", "dairy", "veg"), listOf("bakery", "veg", "dairy"), off))
    }

    @Test
    fun `only mode CATEGORIES has inactive categories`() {
        val withOff = layout.copy(disabledCategories = listOf("bakery"))
        assertEquals(setOf("bakery"), withOff.inactiveCategories)
        assertEquals(emptySet<String>(), withOff.copy(mode = LayoutMode.ITEMS).inactiveCategories)
    }
}
