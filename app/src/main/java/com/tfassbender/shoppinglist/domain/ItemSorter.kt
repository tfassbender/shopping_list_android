package com.tfassbender.shoppinglist.domain

/**
 * Sorts the items of a list for walking through a shop - on the device, so it works offline. Port of the
 * server's `ItemSorter` (and the web app's `sortForLayout`); the tests mirror the server's `ItemSorterTest`,
 * keep them in sync. Keys, most important first:
 * 1. checked items to the end (if the user wants that),
 * 2. position in the layout – the item's category (mode CATEGORIES) or the item itself (mode ITEMS);
 *    categories missing in the layout follow in the space's category order, items without category or
 *    unknown to the layout come last,
 * 3. most bought first (item history),
 * 4. name (case-insensitive), then creation time for a stable order.
 */
object ItemSorter {

    /** [categories] in the space's order; [usage] keyed by normalized name ([normalizeName]), from [Catalog.usage]. */
    fun sort(
        items: List<Item>,
        layout: ShopLayout,
        categories: List<Category>,
        usage: Map<String, Int>,
        checkedToEnd: Boolean,
    ): List<Item> {
        val position = positionFunction(layout, categories)
        val comparator = compareBy<Item> { if (checkedToEnd && it.checked) 1 else 0 }
            .thenBy(position)
            .thenByDescending { usage[normalizeName(it.name)] ?: 0 }
            .thenBy { it.name.lowercase() }
            .thenBy { it.createdAt }
        return items.sortedWith(comparator)
    }

    private fun positionFunction(layout: ShopLayout, categories: List<Category>): (Item) -> Int = when (layout.mode) {
        LayoutMode.CATEGORIES -> {
            // Layout order first, then remaining categories of the space in their default order.
            val existing = categories.map { it.id }
            val order = layout.categoryOrder.filter { it in existing } + existing.filterNot { it in layout.categoryOrder }
            val index = order.withIndex().associate { (i, id) -> id to i }
            val unknown = order.size
            { item -> item.categoryId?.let { index[it] } ?: unknown }
        }
        LayoutMode.ITEMS -> {
            val index = layout.itemOrder.withIndex().associate { (i, name) -> normalizeName(name) to i }
            val unknown = index.size
            { item -> index[normalizeName(item.name)] ?: unknown }
        }
    }

    /**
     * Without a shop: order of adding (oldest first; the sort is stable for equal times), optionally checked
     * items last (the user's setting).
     */
    fun byAdding(items: List<Item>, checkedToEnd: Boolean): List<Item> =
        items.sortedWith(compareBy<Item> { if (checkedToEnd && it.checked) 1 else 0 }.thenBy { it.createdAt })

    /** The shops offered on a list: layouts connected to it first, then the other layouts of the space. */
    fun shopChoices(listId: String, layouts: List<ShopLayout>): List<ShopLayout> {
        val (connected, others) = layouts.sortedBy { it.name.lowercase() }.partition { listId in it.listIds }
        return connected + others
    }
}
