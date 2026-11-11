package com.tfassbender.shoppinglist.data.sync

import com.tfassbender.shoppinglist.api.apis.SyncApi
import com.tfassbender.shoppinglist.api.models.PullResponse
import com.tfassbender.shoppinglist.api.models.PushRequest
import com.tfassbender.shoppinglist.api.models.PushResponse
import com.tfassbender.shoppinglist.api.models.SyncChangeDto
import com.tfassbender.shoppinglist.api.models.SyncChangeType
import com.tfassbender.shoppinglist.api.models.WarningDto
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.remote.apiCall
import com.tfassbender.shoppinglist.data.remote.map
import com.tfassbender.shoppinglist.data.remote.toDomain
import com.tfassbender.shoppinglist.domain.ChangeResult
import com.tfassbender.shoppinglist.domain.ChangeStatus
import com.tfassbender.shoppinglist.domain.Item
import com.tfassbender.shoppinglist.domain.MergeWarning
import com.tfassbender.shoppinglist.domain.PullResult
import com.tfassbender.shoppinglist.domain.PushResult
import com.tfassbender.shoppinglist.domain.SyncItem
import com.tfassbender.shoppinglist.domain.SyncList
import com.tfassbender.shoppinglist.domain.SyncOp
import com.tfassbender.shoppinglist.domain.WarningType
import java.time.Instant
import com.tfassbender.shoppinglist.api.models.ItemField as ItemFieldDto

/** The two sync calls (port, so the engine can be tested with a fake server). */
interface SyncGateway {
    suspend fun push(spaceId: String, baseSeq: Long, changes: List<SyncOp>): ApiResult<PushResult>
    suspend fun pull(spaceId: String, since: Long): ApiResult<PullResult>
}

/** The real server, via the generated client. */
class ApiSyncGateway(private val api: SyncApi) : SyncGateway {

    override suspend fun push(spaceId: String, baseSeq: Long, changes: List<SyncOp>): ApiResult<PushResult> =
        apiCall { api.pushChanges(spaceId = spaceId, pushRequest = PushRequest(baseSeq = baseSeq, changes = changes.map { it.toDto() })) }
            .map { it.toDomain(spaceId) }

    override suspend fun pull(spaceId: String, since: Long): ApiResult<PullResult> =
        apiCall { api.pullChanges(spaceId = spaceId, since = since) }.map { it.toDomain() }
}

internal fun SyncOp.toDto(): SyncChangeDto = when (this) {
    is SyncOp.CreateList -> SyncChangeDto(changeId = changeId, type = SyncChangeType.CREATE_LIST, listId = listId, name = name)
    is SyncOp.RenameList -> SyncChangeDto(changeId = changeId, type = SyncChangeType.RENAME_LIST, listId = listId, name = name)
    is SyncOp.AddItem -> SyncChangeDto(
        changeId = changeId, type = SyncChangeType.ADD_ITEM, listId = listId, itemId = itemId, name = name,
        quantity = quantity, unit = unit, note = note, categoryId = categoryId,
    )
    // null would be left out of the JSON; "" clears the field as well and is explicit.
    is SyncOp.EditItem -> SyncChangeDto(
        changeId = changeId, type = SyncChangeType.EDIT_ITEM, listId = listId, itemId = itemId,
        field = ItemFieldDto.valueOf(field.name), value = value ?: "",
    )
    is SyncOp.CheckItem -> SyncChangeDto(changeId = changeId, type = SyncChangeType.CHECK_ITEM, listId = listId, itemId = itemId)
    is SyncOp.UncheckItem -> SyncChangeDto(changeId = changeId, type = SyncChangeType.UNCHECK_ITEM, listId = listId, itemId = itemId)
    is SyncOp.DeleteItem -> SyncChangeDto(changeId = changeId, type = SyncChangeType.DELETE_ITEM, listId = listId, itemId = itemId)
    is SyncOp.RemoveChecked -> SyncChangeDto(changeId = changeId, type = SyncChangeType.REMOVE_CHECKED, listId = listId, itemIds = itemIds)
}

/** Warnings get their id and time from the engine. */
private fun PushResponse.toDomain(spaceId: String) = PushResult(
    seq = seq,
    results = results.map { ChangeResult(it.changeId, ChangeStatus.valueOf(it.status.name), it.code) },
    warnings = warnings.map { it.toDomain(spaceId) },
)

private fun WarningDto.toDomain(spaceId: String) = MergeWarning(
    id = "", at = Instant.EPOCH, spaceId = spaceId, type = WarningType.valueOf(type.name), listId = listId,
    listName = listName, itemId = itemId, itemName = itemName, field = field?.name,
    theirValue = theirValue, yourValue = yourValue, byName = byName,
)

private fun PullResponse.toDomain() = PullResult(
    seq = seq,
    fullResync = fullResync,
    lists = lists.map { list ->
        SyncList(
            list.id, list.name, list.createdAt.toInstant(), list.seq, list.deleted,
            list.items.map {
                SyncItem(
                    Item(it.id, it.name, it.quantity, it.unit, it.note, it.categoryId, it.checked, it.createdAt.toInstant(), it.seq),
                    it.deleted,
                )
            },
        )
    },
    listIds = listIds,
    catalog = catalog?.toDomain(),
    layouts = layouts?.map { it.toDomain() },
    layoutIds = layoutIds,
)
