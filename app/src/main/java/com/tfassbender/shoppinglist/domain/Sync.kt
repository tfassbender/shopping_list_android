package com.tfassbender.shoppinglist.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One fine-grained change to a list, made locally and sent to the server with the next sync (see
 * doc/server_api.md, "Offline sync"). Ids of new lists/items and [changeId] are generated on the device,
 * so creating works offline and resending is idempotent.
 */
@Serializable
sealed interface SyncOp {
    val changeId: String
    val listId: String

    @Serializable
    @SerialName("CREATE_LIST")
    data class CreateList(override val changeId: String, override val listId: String, val name: String) : SyncOp

    @Serializable
    @SerialName("RENAME_LIST")
    data class RenameList(override val changeId: String, override val listId: String, val name: String) : SyncOp

    @Serializable
    @SerialName("ADD_ITEM")
    data class AddItem(
        override val changeId: String,
        override val listId: String,
        val itemId: String,
        val name: String,
        val quantity: String? = null,
        val unit: String? = null,
        val note: String? = null,
        val categoryId: String? = null,
    ) : SyncOp

    /** Sets one field; null or blank clears an optional field (the name can't be cleared). */
    @Serializable
    @SerialName("EDIT_ITEM")
    data class EditItem(
        override val changeId: String,
        override val listId: String,
        val itemId: String,
        val field: ItemField,
        val value: String?,
    ) : SyncOp

    @Serializable
    @SerialName("CHECK_ITEM")
    data class CheckItem(override val changeId: String, override val listId: String, val itemId: String) : SyncOp

    @Serializable
    @SerialName("UNCHECK_ITEM")
    data class UncheckItem(override val changeId: String, override val listId: String, val itemId: String) : SyncOp

    @Serializable
    @SerialName("DELETE_ITEM")
    data class DeleteItem(override val changeId: String, override val listId: String, val itemId: String) : SyncOp

    /** Removes the items the user saw checked - not items someone else checked meanwhile. */
    @Serializable
    @SerialName("REMOVE_CHECKED")
    data class RemoveChecked(override val changeId: String, override val listId: String, val itemIds: List<String>) : SyncOp
}

enum class ChangeStatus { APPLIED, DUPLICATE, REJECTED }

data class ChangeResult(val changeId: String, val status: ChangeStatus, val code: String?)

/** Server merge warnings plus [REJECTED] for changes the server refused (e.g. invalid name). */
enum class WarningType { OVERWRITTEN, RESTORED, DELETE_SKIPPED, LIST_DELETED, REJECTED }

/**
 * Something the merge did that the user may want to know, e.g. "Anna had set the quantity of Milk to 3;
 * your change set it to 2". Kept until the user clears the warnings.
 */
@Serializable
data class MergeWarning(
    val id: String,
    val at: SerializableInstant,
    val spaceId: String,
    val type: WarningType,
    val listId: String,
    val listName: String? = null,
    val itemId: String? = null,
    val itemName: String? = null,
    /** OVERWRITTEN: the changed aspect (`NAME` with [itemId] null = the list name); server `ItemAspect`. */
    val field: String? = null,
    val theirValue: String? = null,
    val yourValue: String? = null,
    /** Display name of the other user. */
    val byName: String? = null,
    /** REJECTED: the server's error code. */
    val code: String? = null,
)

data class PushResult(val seq: Long, val results: List<ChangeResult>, val warnings: List<MergeWarning>)

data class SyncItem(val item: Item, val deleted: Boolean)

data class SyncList(
    val id: String,
    val name: String,
    val createdAt: java.time.Instant,
    val seq: Long,
    val deleted: Boolean,
    /** Only the changed items, including deleted ones (tombstones). */
    val items: List<SyncItem>,
)

data class PullResult(
    val seq: Long,
    /** true: [lists] is the complete state - drop everything local that isn't in it. */
    val fullResync: Boolean,
    val lists: List<SyncList>,
    /** Ids of all existing lists. */
    val listIds: List<String>,
    /** Only if changed. */
    val catalog: Catalog?,
    /** All layouts, only if one changed. */
    val layouts: List<ShopLayout>?,
    val layoutIds: List<String>,
)
