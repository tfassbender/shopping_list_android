package com.tfassbender.shoppinglist.domain

import java.time.Instant

/** The lists of a space by id. */
typealias ListsById = Map<String, ShoppingList>

private fun blankToNull(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Applies one queued change to the local lists - the optimistic view the user sees until the server
 * confirms. Port of the web app's `applyOp.ts`: it mirrors the server's change logic closely enough for
 * display; the server's answer after the next sync is the truth (e.g. the server also fills in the
 * category of new items from the item history).
 */
fun applyOp(lists: ListsById, op: SyncOp, spaceId: String, now: Instant): ListsById {
    if (op is SyncOp.CreateList) {
        if (op.listId in lists) return lists
        return lists + (op.listId to ShoppingList(op.listId, spaceId, op.name.trim(), op.createdAt ?: now))
    }
    val list = lists[op.listId] ?: return lists // list deleted meanwhile: the server will reject the change
    return lists + (op.listId to applyToList(list, op, now))
}

private fun applyToList(list: ShoppingList, op: SyncOp, now: Instant): ShoppingList {
    fun update(itemId: String, change: (Item) -> Item) =
        list.copy(items = list.items.map { if (it.id == itemId) change(it) else it })

    return when (op) {
        is SyncOp.CreateList -> list
        is SyncOp.RenameList -> list.copy(name = op.name.trim())
        is SyncOp.AddItem -> if (list.items.any { it.id == op.itemId }) list else list.copy(
            items = list.items + Item(
                id = op.itemId,
                name = op.name.trim(),
                quantity = blankToNull(op.quantity),
                unit = blankToNull(op.unit),
                note = blankToNull(op.note),
                categoryId = blankToNull(op.categoryId),
                createdAt = op.createdAt ?: now,
            ),
        )
        is SyncOp.EditItem -> update(op.itemId) { item -> item.withField(op.field, op.value) }
        is SyncOp.CheckItem -> update(op.itemId) { it.copy(checked = true) }
        is SyncOp.UncheckItem -> update(op.itemId) { it.copy(checked = false) }
        is SyncOp.DeleteItem -> list.copy(items = list.items.filterNot { it.id == op.itemId })
        is SyncOp.RemoveChecked -> op.itemIds.toSet().let { ids -> list.copy(items = list.items.filterNot { it.id in ids }) }
    }
}

private fun Item.withField(field: ItemField, value: String?): Item = when (field) {
    ItemField.NAME -> copy(name = blankToNull(value) ?: name) // the name can't be cleared
    ItemField.QUANTITY -> copy(quantity = blankToNull(value))
    ItemField.UNIT -> copy(unit = blankToNull(value))
    ItemField.NOTE -> copy(note = blankToNull(value))
    ItemField.CATEGORY -> copy(categoryId = blankToNull(value))
}

/** Merges a pull answer into the confirmed (server) state. Port of the web app's `applyPull`. */
fun applyPull(lists: ListsById, pull: PullResult, spaceId: String): ListsById {
    val result = if (pull.fullResync) linkedMapOf() else LinkedHashMap(lists)
    for (delta in pull.lists) {
        if (delta.deleted) {
            result.remove(delta.id)
            continue
        }
        val items = LinkedHashMap<String, Item>()
        result[delta.id]?.items?.forEach { items[it.id] = it }
        for (change in delta.items) {
            if (change.deleted) items.remove(change.item.id) else items[change.item.id] = change.item
        }
        result[delta.id] = ShoppingList(delta.id, spaceId, delta.name, delta.createdAt, delta.seq, items.values.toList())
    }
    // Lists that disappeared (deleted, or their tombstone is already gone) are dropped.
    val existing = pull.listIds.toSet()
    result.keys.retainAll(existing)
    return result
}
