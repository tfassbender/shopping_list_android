package com.tfassbender.shoppinglist.domain

/**
 * Sorts the items of a list for walking through a shop - on the device, so it works offline. Port of the
 * server's `ItemSorter` (and the web app's `sortForLayout`); the tests mirror the server's `ItemSorterTest`,
 * keep them in sync. Keys, most important first:
 * 1. checked items to the end (if the user wants that),
 * 2. position in the layout – the item's category (mode CATEGORIES) or the item itself (mode ITEMS);
 *    categories missing in the layout follow in the space's category order, then items without category,
 *    then the categories the shop doesn't have (disabled in the layout) - or, in mode ITEMS, items
 *    unknown to the layout come last,
 * 3. the user's [ItemOrder]: order of adding (creation time, then name) or name (case-insensitive, then
 *    creation time). Deliberately nothing that changes when an item is checked (like "most bought"), so
 *    checking an item never moves it.
 */
object ItemSorter {

    /** [categories] in the space's order. */
    fun sort(
        items: List<Item>,
        layout: ShopLayout,
        categories: List<Category>,
        checkedToEnd: Boolean,
        itemOrder: ItemOrder = ItemOrder.ADDED,
    ): List<Item> {
        val position = positionFunction(layout, categories)
        val comparator = compareBy<Item> { if (checkedToEnd && it.checked) 1 else 0 }
            .thenBy(position)
        val withinPosition = when (itemOrder) {
            ItemOrder.ADDED -> comparator.thenBy { it.createdAt }.thenBy { it.name.lowercase() }
            ItemOrder.ALPHABETICAL -> comparator.thenBy { it.name.lowercase() }.thenBy { it.createdAt }
        }
        return items.sortedWith(withinPosition)
    }

    private fun positionFunction(layout: ShopLayout, categories: List<Category>): (Item) -> Int = when (layout.mode) {
        LayoutMode.CATEGORIES -> {
            // Layout order first, then remaining categories of the space in their default order; the
            // disabled ones are moved behind the uncategorized items (the shop doesn't have them).
            val existing = categories.map { it.id }
            val order = layout.categoryOrder.filter { it in existing } + existing.filterNot { it in layout.categoryOrder }
            val (disabled, enabled) = order.partition { it in layout.disabledCategories }
            val unknown = enabled.size
            val disabledIndex = disabled.withIndex().associate { (i, id) -> id to unknown + 1 + i }
            val index = enabled.withIndex().associate { (i, id) -> id to i } + disabledIndex
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
