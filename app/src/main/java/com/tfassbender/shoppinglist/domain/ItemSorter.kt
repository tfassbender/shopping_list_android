package com.tfassbender.shoppinglist.domain

/**
 * Sorts the items of a list for walking through a shop - on the device, so it works offline. Port of the
 * server's `ItemSorter` (and the web app's `sortForLayout`); the tests mirror the server's `ItemSorterTest`,
 * keep them in sync. Keys, most important first:
 * 1. checked items to the end (if the user wants that),
 * 2. position in the layout – the item's category (mode CATEGORIES) or the item itself (mode ITEMS);
 *    categories missing in the layout follow in the space's category order, items without category or
 *    unknown to the layout come last,
 * 3. personal favorites of this layout first,
 * 4. most bought first (item history),
 * 5. name (case-insensitive), then creation time for a stable order.
 */
object ItemSorter {

    data class SortedItem(val item: Item, val favorite: Boolean)

    /**
     * [categories] in the space's order; [favorites] and [usage] keyed by normalized name
     * ([normalizeName]) - favorites as the server returns them, usage from [Catalog.usage].
     */
    fun sort(
        items: List<Item>,
        layout: ShopLayout,
        categories: List<Category>,
        favorites: Set<String>,
        usage: Map<String, Int>,
        checkedToEnd: Boolean,
    ): List<SortedItem> {
        val normalizedFavorites = favorites.map(::normalizeName).toSet()
        val position = positionFunction(layout, categories)
        val isFavorite = { item: Item -> normalizeName(item.name) in normalizedFavorites }
        val comparator = compareBy<Item> { if (checkedToEnd && it.checked) 1 else 0 }
            .thenBy(position)
            .thenBy { if (isFavorite(it)) 0 else 1 }
            .thenByDescending { usage[normalizeName(it.name)] ?: 0 }
            .thenBy { it.name.lowercase() }
            .thenBy { it.createdAt }
        return items.sortedWith(comparator).map { SortedItem(it, isFavorite(it)) }
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

    /** Without a shop: order of adding, optionally checked items last (the user's setting). */
    fun byAdding(items: List<Item>, checkedToEnd: Boolean): List<Item> =
        if (checkedToEnd) items.sortedBy { if (it.checked) 1 else 0 } else items

    /** The shops offered on a list: layouts connected to it first, then the other layouts of the space. */
    fun shopChoices(listId: String, layouts: List<ShopLayout>): List<ShopLayout> {
        val (connected, others) = layouts.sortedBy { it.name.lowercase() }.partition { listId in it.listIds }
        return connected + others
    }
}
