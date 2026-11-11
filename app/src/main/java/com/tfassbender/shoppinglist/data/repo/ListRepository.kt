package com.tfassbender.shoppinglist.data.repo

import com.tfassbender.shoppinglist.api.apis.ListsApi
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.remote.apiCallNoContent
import com.tfassbender.shoppinglist.data.remote.onSuccess
import com.tfassbender.shoppinglist.data.sync.SyncEngine
import com.tfassbender.shoppinglist.domain.ItemField
import com.tfassbender.shoppinglist.domain.Limits
import com.tfassbender.shoppinglist.domain.ShoppingList
import com.tfassbender.shoppinglist.domain.SyncOp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID

/** Input the server would reject; [code] is the server's error code (e.g. `INVALID_ITEM_NAME`). */
class InvalidInputException(val code: String) : IllegalArgumentException(code)

/**
 * Lists and items of a space - **offline first**: every change is applied locally at once and queued for
 * the server (see [SyncEngine]). Changes are validated here, because a queued change is only checked by
 * the server at the next sync; invalid input gives `Result.failure(InvalidInputException)`.
 */
class ListRepository(
    private val engine: SyncEngine,
    private val api: ListsApi,
    private val clock: Clock,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** The lists of a space in the order they were created. */
    fun lists(spaceId: String): Flow<List<ShoppingList>> =
        engine.lists(spaceId).map { lists -> lists.values.sortedBy { it.createdAt } }.distinctUntilChanged()

    /** One list (null if it doesn't exist (anymore)). Items in the order of adding - see ItemSorter for shops. */
    fun list(spaceId: String, listId: String): Flow<ShoppingList?> =
        engine.lists(spaceId).map { it[listId] }.distinctUntilChanged()

    /** Loads the space from storage and syncs it - call when a space is opened. */
    suspend fun open(spaceId: String) = engine.open(spaceId)

    /** Returns the new list's id. */
    suspend fun createList(spaceId: String, name: String): Result<String> = validated(Limits.checkName(name)) {
        val listId = newId()
        enqueue(spaceId, SyncOp.CreateList(engine.newChangeId(), listId, name.trim(), clock.instant()))
        listId
    }

    suspend fun renameList(spaceId: String, listId: String, name: String): Result<Unit> = validated(Limits.checkName(name)) {
        enqueue(spaceId, SyncOp.RenameList(engine.newChangeId(), listId, name.trim()))
    }

    /** Owner only, needs a connection (deleting isn't a sync change). Errors: `OWNER_ONLY`. */
    suspend fun deleteList(spaceId: String, listId: String): ApiResult<Unit> =
        apiCallNoContent { api.deleteList(listId = listId, spaceId = spaceId) }.onSuccess { engine.sync(spaceId) }

    /**
     * Adds an item; returns its id. Without [categoryId] the server fills in the category from the item
     * history (visible after the next sync).
     */
    suspend fun addItem(
        spaceId: String,
        listId: String,
        name: String,
        quantity: String? = null,
        unit: String? = null,
        note: String? = null,
        categoryId: String? = null,
    ): Result<String> {
        val error = Limits.checkItemName(name)
            ?: Limits.checkItemField(ItemField.QUANTITY, quantity)
            ?: Limits.checkItemField(ItemField.UNIT, unit)
            ?: Limits.checkItemField(ItemField.NOTE, note)
            ?: listFull(spaceId, listId)
        return validated(error) {
            val itemId = newId()
            enqueue(spaceId, SyncOp.AddItem(engine.newChangeId(), listId, itemId, name.trim(), quantity, unit, note, categoryId, clock.instant()))
            itemId
        }
    }

    /** Sets one field; null or blank clears an optional field (not the name). */
    suspend fun editItem(spaceId: String, listId: String, itemId: String, field: ItemField, value: String?): Result<Unit> =
        validated(Limits.checkItemField(field, value)) {
            enqueue(spaceId, SyncOp.EditItem(engine.newChangeId(), listId, itemId, field, value?.trim()))
        }

    /** Undo of a check = [uncheck]. */
    suspend fun check(spaceId: String, listId: String, itemId: String) =
        enqueue(spaceId, SyncOp.CheckItem(engine.newChangeId(), listId, itemId))

    suspend fun uncheck(spaceId: String, listId: String, itemId: String) =
        enqueue(spaceId, SyncOp.UncheckItem(engine.newChangeId(), listId, itemId))

    suspend fun deleteItem(spaceId: String, listId: String, itemId: String) =
        enqueue(spaceId, SyncOp.DeleteItem(engine.newChangeId(), listId, itemId))

    /** Removes the items checked *now* on this device - items someone else checks meanwhile stay. */
    suspend fun removeChecked(spaceId: String, listId: String) {
        val checked = engine.currentLists(spaceId)[listId]?.items?.filter { it.checked }?.map { it.id }.orEmpty()
        if (checked.isNotEmpty()) enqueue(spaceId, SyncOp.RemoveChecked(engine.newChangeId(), listId, checked))
    }

    private suspend fun enqueue(spaceId: String, op: SyncOp) = engine.enqueue(spaceId, op)

    private suspend fun listFull(spaceId: String, listId: String): String? {
        val count = engine.currentLists(spaceId)[listId]?.items?.size ?: 0
        return if (count >= Limits.ITEMS_PER_LIST) "LIST_FULL" else null
    }

    private inline fun <T> validated(error: String?, action: () -> T): Result<T> =
        if (error != null) Result.failure(InvalidInputException(error)) else Result.success(action())
}
