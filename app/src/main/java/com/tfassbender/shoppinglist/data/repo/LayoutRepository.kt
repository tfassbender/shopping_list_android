package com.tfassbender.shoppinglist.data.repo

import com.tfassbender.shoppinglist.api.apis.LayoutsApi
import com.tfassbender.shoppinglist.api.models.CopyLayoutRequest
import com.tfassbender.shoppinglist.api.models.CreateLayoutRequest
import com.tfassbender.shoppinglist.api.models.EntriesRequest
import com.tfassbender.shoppinglist.api.models.LayoutDto
import com.tfassbender.shoppinglist.api.models.UpdateLayoutRequest
import com.tfassbender.shoppinglist.data.local.UiPreferences
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.remote.apiCall
import com.tfassbender.shoppinglist.data.remote.apiCallNoContent
import com.tfassbender.shoppinglist.data.remote.map
import com.tfassbender.shoppinglist.data.remote.onSuccess
import com.tfassbender.shoppinglist.data.remote.toDomain
import com.tfassbender.shoppinglist.data.remote.toDto
import com.tfassbender.shoppinglist.data.sync.SyncEngine
import com.tfassbender.shoppinglist.domain.Item
import com.tfassbender.shoppinglist.domain.ItemSorter
import com.tfassbender.shoppinglist.domain.LayoutMode
import com.tfassbender.shoppinglist.domain.ShopLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import retrofit2.Response

/**
 * Shop layouts (the walking order through a shop). Reading and sorting work offline (layouts come with the
 * sync pull); editing needs a connection.
 */
class LayoutRepository(
    private val api: LayoutsApi,
    private val engine: SyncEngine,
    private val preferences: UiPreferences,
) {
    /** Layouts of a space by name. */
    fun layouts(spaceId: String): Flow<List<ShopLayout>> =
        engine.state(spaceId).map { state -> state.layouts.sortedBy { it.name.lowercase() } }.distinctUntilChanged()

    /** The shops offered on a list: connected layouts first, then the others. */
    fun shopChoices(spaceId: String, listId: String): Flow<List<ShopLayout>> =
        layouts(spaceId).map { ItemSorter.shopChoices(listId, it) }

    suspend fun refresh(spaceId: String): ApiResult<List<ShopLayout>> =
        apiCall { api.listLayouts(spaceId = spaceId) }.map { dtos -> dtos.map { it.toDomain() } }
            .onSuccess { layouts -> engine.updateState(spaceId) { it.copy(layouts = layouts) } }

    /** Starts with the space's category order. Errors: `LAYOUT_EXISTS`, `INVALID_NAME`. */
    suspend fun create(spaceId: String, name: String, mode: LayoutMode = LayoutMode.CATEGORIES): ApiResult<ShopLayout> =
        upsert(spaceId) { api.createLayout(spaceId = spaceId, createLayoutRequest = CreateLayoutRequest(name = name.trim(), mode = mode.toDto())) }

    /** A copy with mode, category / item order and connected lists. Errors: `LAYOUT_EXISTS`, `INVALID_NAME`. */
    suspend fun copy(spaceId: String, layoutId: String, name: String): ApiResult<ShopLayout> =
        upsert(spaceId) { api.copyLayout(layoutId = layoutId, spaceId = spaceId, copyLayoutRequest = CopyLayoutRequest(name = name.trim())) }

    /** Rename / switch the mode. */
    suspend fun update(spaceId: String, layoutId: String, name: String, mode: LayoutMode): ApiResult<ShopLayout> =
        upsert(spaceId) { api.updateLayout(layoutId = layoutId, spaceId = spaceId, updateLayoutRequest = UpdateLayoutRequest(name = name.trim(), mode = mode.toDto())) }

    suspend fun delete(spaceId: String, layoutId: String): ApiResult<Unit> =
        apiCallNoContent { api.deleteLayout(layoutId = layoutId, spaceId = spaceId) }.onSuccess {
            engine.updateState(spaceId) { state -> state.copy(layouts = state.layouts.filterNot { it.id == layoutId }) }
        }

    /** Mode CATEGORIES: the complete new order of category ids. */
    suspend fun setCategoryOrder(spaceId: String, layoutId: String, categoryIds: List<String>): ApiResult<ShopLayout> =
        upsert(spaceId) { api.setLayoutCategoryOrder(layoutId = layoutId, spaceId = spaceId, entriesRequest = EntriesRequest(categoryIds)) }

    /** Mode ITEMS: the complete new order of item names (case-insensitive, unique). Errors: `INVALID_ORDER`. */
    suspend fun setItemOrder(spaceId: String, layoutId: String, itemNames: List<String>): ApiResult<ShopLayout> =
        upsert(spaceId) { api.setLayoutItemOrder(layoutId = layoutId, spaceId = spaceId, entriesRequest = EntriesRequest(itemNames)) }

    /** Connected lists offer this shop first. */
    suspend fun connectList(spaceId: String, layoutId: String, listId: String): ApiResult<ShopLayout> =
        upsert(spaceId) { api.connectLayoutToList(layoutId = layoutId, listId = listId, spaceId = spaceId) }

    suspend fun disconnectList(spaceId: String, layoutId: String, listId: String): ApiResult<ShopLayout> =
        upsert(spaceId) { api.disconnectLayoutFromList(layoutId = layoutId, listId = listId, spaceId = spaceId) }

    // ---- sorting ----

    /** The shop last used on this list on this device (null = order of adding). */
    fun lastLayout(listId: String): Flow<String?> = preferences.lastLayout(listId)

    suspend fun setLastLayout(listId: String, layoutId: String?) = preferences.setLastLayout(listId, layoutId)

    /**
     * The items of a list sorted for walking through [layoutId]'s shop (null or unknown layout: order of
     * adding). Computed on the device, so it works offline. [checkedToEnd] is the user's setting.
     */
    fun sortedItems(spaceId: String, listId: String, layoutId: String?, checkedToEnd: Boolean): Flow<List<Item>> =
        combine(engine.lists(spaceId), engine.state(spaceId)) { lists, state ->
            val items = lists[listId]?.items.orEmpty()
            val layout = state.layouts.firstOrNull { it.id == layoutId }
            if (layout == null) {
                ItemSorter.byAdding(items, checkedToEnd)
            } else {
                ItemSorter.sort(items, layout, state.catalog?.categories.orEmpty(), state.catalog?.usage.orEmpty(), checkedToEnd)
            }
        }.distinctUntilChanged()

    // ---- internals ----

    private suspend fun upsert(spaceId: String, call: suspend () -> Response<LayoutDto>): ApiResult<ShopLayout> =
        apiCall(call).map { it.toDomain() }.onSuccess { layout ->
            engine.updateState(spaceId) { state -> state.copy(layouts = state.layouts.filterNot { it.id == layout.id } + layout) }
        }
}
