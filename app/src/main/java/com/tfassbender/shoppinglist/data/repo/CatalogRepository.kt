package com.tfassbender.shoppinglist.data.repo

import com.tfassbender.shoppinglist.api.apis.CatalogApi
import com.tfassbender.shoppinglist.api.models.CatalogDto
import com.tfassbender.shoppinglist.api.models.CategoryRequest
import com.tfassbender.shoppinglist.api.models.OrderRequest
import com.tfassbender.shoppinglist.api.models.UnitsRequest
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.remote.apiCall
import com.tfassbender.shoppinglist.data.remote.map
import com.tfassbender.shoppinglist.data.remote.onSuccess
import com.tfassbender.shoppinglist.data.remote.toDomain
import com.tfassbender.shoppinglist.data.sync.SyncEngine
import com.tfassbender.shoppinglist.domain.Catalog
import com.tfassbender.shoppinglist.domain.Suggestion
import com.tfassbender.shoppinglist.domain.localSuggestions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import retrofit2.Response

/**
 * Categories (ordered, colored), units and autocomplete of a space. Reading works offline (the catalog
 * comes with the sync pull); editing needs a connection - every member may edit.
 */
class CatalogRepository(
    private val api: CatalogApi,
    private val engine: SyncEngine,
) {
    /** null until the space was synced once. */
    fun catalog(spaceId: String): Flow<Catalog?> = engine.state(spaceId).map { it.catalog }.distinctUntilChanged()

    suspend fun refresh(spaceId: String): ApiResult<Catalog> = edit(spaceId) { api.getCatalog(spaceId = spaceId) }

    /** [color] as `#RRGGBB`. Added at the end. Errors: `CATEGORY_EXISTS`, `INVALID_COLOR`, `TOO_MANY_CATEGORIES`. */
    suspend fun addCategory(spaceId: String, name: String, color: String): ApiResult<Catalog> =
        edit(spaceId) { api.addCategory(spaceId = spaceId, categoryRequest = CategoryRequest(name = name.trim(), color = color)) }

    suspend fun updateCategory(spaceId: String, categoryId: String, name: String, color: String): ApiResult<Catalog> =
        edit(spaceId) { api.updateCategory(categoryId = categoryId, spaceId = spaceId, categoryRequest = CategoryRequest(name = name.trim(), color = color)) }

    /** Its items become uncategorized. */
    suspend fun deleteCategory(spaceId: String, categoryId: String): ApiResult<Catalog> =
        edit(spaceId) { api.deleteCategory(categoryId = categoryId, spaceId = spaceId) }

    /** [categoryIds] = all category ids in the new order. */
    suspend fun reorderCategories(spaceId: String, categoryIds: List<String>): ApiResult<Catalog> =
        edit(spaceId) { api.reorderCategories(spaceId = spaceId, orderRequest = OrderRequest(categoryIds)) }

    /** Replaces the list of units. */
    suspend fun setUnits(spaceId: String, units: List<String>): ApiResult<Catalog> =
        edit(spaceId) { api.setUnits(spaceId = spaceId, unitsRequest = UnitsRequest(units.map { it.trim() }.filter { it.isNotEmpty() })) }

    /**
     * Autocomplete for the quick-add field: the server's item history when online, otherwise item names of
     * the local lists. Never fails - offline it just gets weaker.
     */
    suspend fun suggestions(spaceId: String, query: String, limit: Int = 8): List<Suggestion> {
        if (query.isBlank()) return emptyList()
        val online = apiCall { api.getSuggestions(spaceId = spaceId, limit = limit, q = query.trim()) }
        if (online is ApiResult.Success) return online.value.map { it.toDomain() }
        val usage = engine.state(spaceId).first().catalog?.usage.orEmpty()
        return localSuggestions(query, engine.currentLists(spaceId).values, usage, limit)
    }

    private suspend fun edit(spaceId: String, call: suspend () -> Response<CatalogDto>): ApiResult<Catalog> =
        apiCall(call).map { it.toDomain() }.onSuccess { catalog -> engine.updateState(spaceId) { it.copy(catalog = catalog) } }
}
