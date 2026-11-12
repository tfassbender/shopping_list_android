package com.tfassbender.shoppinglist.ui.list

import com.tfassbender.shoppinglist.domain.Category
import com.tfassbender.shoppinglist.domain.Item
import com.tfassbender.shoppinglist.domain.LayoutMode

// Pure presentation logic of the list page - ports of the web app's `lists/amount.ts` and
// `lists/sortItems.ts` (server repo); the tests mirror theirs.

/**
 * "2 l" / "2" / "" - quantity and unit as one text, shown in front of the item name. A unit without an
 * amount is not shown ("l Oat milk" reads oddly); it stays saved and reappears with an amount.
 */
fun formatAmount(quantity: String?, unit: String?): String {
    if (quantity.isNullOrEmpty()) return ""
    return listOfNotNull(quantity, unit?.takeIf { it.isNotEmpty() }).joinToString(" ")
}

private val VALID_AMOUNT = Regex("""^\d*(?:[.,]\d*)?$""")
private val TYPED_AMOUNT = Regex("""^[\d.,]*$""")

/** A valid amount: empty, or digits with at most one decimal separator ("2", "0,5", "1.25", "1."). */
fun isValidAmount(quantity: String): Boolean = VALID_AMOUNT.matches(quantity.trim())

/**
 * What the amount field accepts while typing: digits and decimal separators. A second separator is kept
 * as typed and marked invalid - dropping it would silently turn the input into another number.
 */
fun isTypedAmount(text: String): Boolean = TYPED_AMOUNT.matches(text)

/** A visible block of the list: one category (colored header), uncategorized items, the checked items, or all items. */
data class ItemSection(val key: String, val kind: Kind, val category: Category? = null, val items: List<Item>) {
    enum class Kind { CATEGORY, OTHER, CHECKED, ITEMS }
}

/**
 * Sections for a list in the given order:
 * - mode CATEGORIES: a new section starts whenever the category changes (so headers follow the shop),
 * - mode ITEMS: one section without header (items carry their category color).
 *
 * Checked items stay where they are; only those in [movedToEnd] (moved by the sort button) go to a last
 * "Checked" section - and only while they are still checked.
 */
fun groupSorted(sorted: List<Item>, categories: List<Category>, mode: LayoutMode, movedToEnd: Set<String> = emptySet()): List<ItemSection> {
    val (atEnd, inPlace) = sorted.partition { it.checked && it.id in movedToEnd }
    val byId = categories.associateBy { it.id }
    val sections = mutableListOf<ItemSection>()
    if (mode == LayoutMode.ITEMS) {
        if (inPlace.isNotEmpty()) sections += ItemSection("__items", ItemSection.Kind.ITEMS, items = inPlace)
    } else {
        for (item in inPlace) {
            val category = item.categoryId?.let { byId[it] }
            val key = category?.id ?: "__other"
            val last = sections.lastOrNull()
            if (last != null && last.key.substringBefore('#') == key) {
                sections[sections.lastIndex] = last.copy(items = last.items + item)
            } else {
                val kind = if (category != null) ItemSection.Kind.CATEGORY else ItemSection.Kind.OTHER
                sections += ItemSection("$key#${sections.size}", kind, category, listOf(item))
            }
        }
    }
    if (atEnd.isNotEmpty()) sections += ItemSection("__checked", ItemSection.Kind.CHECKED, items = atEnd)
    return sections
}

/**
 * The same items in the same order, but without category headers: one header-less section. Only the
 * "Checked" section (moved there by the sort button) stays separate.
 */
fun withoutCategoryHeaders(sections: List<ItemSection>): List<ItemSection> {
    val items = sections.filter { it.kind != ItemSection.Kind.CHECKED }.flatMap { it.items }
    val checked = sections.filter { it.kind == ItemSection.Kind.CHECKED }
    return (if (items.isNotEmpty()) listOf(ItemSection("__items", ItemSection.Kind.ITEMS, items = items)) else emptyList()) + checked
}
