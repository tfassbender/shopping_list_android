package com.tfassbender.shoppinglist.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tfassbender.shoppinglist.AppContainer
import com.tfassbender.shoppinglist.data.local.LastList
import com.tfassbender.shoppinglist.data.remote.ApiError
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.repo.InvalidInputException
import com.tfassbender.shoppinglist.domain.Catalog
import com.tfassbender.shoppinglist.domain.Item
import com.tfassbender.shoppinglist.domain.ItemField
import com.tfassbender.shoppinglist.domain.ItemSorter
import com.tfassbender.shoppinglist.domain.LayoutMode
import com.tfassbender.shoppinglist.domain.ShopLayout
import com.tfassbender.shoppinglist.domain.ShoppingList
import com.tfassbender.shoppinglist.domain.Suggestion
import com.tfassbender.shoppinglist.ui.common.stateIn
import com.tfassbender.shoppinglist.ui.home.spaceLists
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the list page shows. [list] null = not on this device yet (loading) or gone - see [state]. */
data class ListUiState(
    val state: LoadState = LoadState.LOADING,
    val list: ShoppingList? = null,
    val sections: List<ItemSection> = emptyList(),
    val layout: ShopLayout? = null,
    val catalog: Catalog? = null,
    /** Unsaved changes dropped when access to the space was lost. */
    val lostChanges: Int = 0,
) {
    enum class LoadState { LOADING, READY, NOT_FOUND, SPACE_GONE }

    val checkedCount: Int get() = list?.checkedCount ?: 0
}

/** The fields of the item editor that changed: field → new value (null clears). */
typealias ItemChanges = Map<ItemField, String?>

/**
 * A shopping list (port of the web app's ListPage): tap an item to check it, tap again to uncheck - checked
 * items stay where they are; the sort button moves the checked ones to the end on demand. All list changes
 * work offline (sync engine); deleting the list needs a connection.
 */
class ListViewModel(private val container: AppContainer, val spaceId: String, val listId: String) : ViewModel() {
    private val lists = container.lists
    private val movedToEnd = MutableStateFlow<Set<String>>(emptySet())

    val categoryHeaders = container.uiPreferences.categoryHeaders.stateIn(this, true)
    val shopChoices = container.layouts.shopChoices(spaceId, listId).stateIn(this, emptyList())
    val isOwner = container.spaces.spaces.map { all -> all.firstOrNull { it.id == spaceId }?.isOwner == true }.stateIn(this, false)

    private val listFlow = combine(container.spaceLists(spaceId), container.syncEngine.accessLost) { lists, lost ->
        Triple(lists, lists?.firstOrNull { it.id == listId }, lost[spaceId])
    }

    val ui = combine(
        listFlow,
        container.catalog.catalog(spaceId),
        container.layouts.layouts(spaceId),
        container.layouts.lastLayout(listId),
        combine(movedToEnd, categoryHeaders) { moved, headers -> moved to headers },
    ) { (spaceLists, list, lost), catalog, layouts, chosenLayoutId, (moved, headers) ->
        when {
            list == null && lost != null -> ListUiState(ListUiState.LoadState.SPACE_GONE, lostChanges = lost)
            spaceLists == null -> ListUiState(ListUiState.LoadState.LOADING)
            list == null -> ListUiState(ListUiState.LoadState.NOT_FOUND)
            else -> {
                // A remembered shop may have been deleted by someone else in the meantime.
                val layout = layouts.firstOrNull { it.id == chosenLayoutId }
                val categories = catalog?.categories.orEmpty()
                // Without a shop the list is exactly in the order of adding - no category groups, which
                // would look like a shop's sorting.
                val grouped = if (layout != null) {
                    groupSorted(ItemSorter.sort(list.items, layout, categories, catalog?.usage.orEmpty(), false), categories, layout.mode, moved)
                } else {
                    groupSorted(ItemSorter.byAdding(list.items, false), categories, LayoutMode.ITEMS, moved)
                }
                ListUiState(
                    state = ListUiState.LoadState.READY,
                    list = list,
                    sections = if (headers) grouped else withoutCategoryHeaders(grouped),
                    layout = layout,
                    catalog = catalog,
                )
            }
        }
    }.stateIn(this, ListUiState())

    private val deleteState = MutableStateFlow<ApiError?>(null)
    val deleteError = deleteState.asStateFlow()

    init {
        viewModelScope.launch {
            // Remember the list as "last used" once it exists here.
            listFlow.first { it.second != null }
            container.uiPreferences.setLastList(LastList(spaceId, listId))
        }
        viewModelScope.launch {
            // An unchecked item returns to its place for good: checking it again must not send it straight
            // back to "Checked" (only the sort button does that). Also covers items unchecked by others.
            listFlow.collect { (_, list, _) ->
                val checked = list?.items?.filter { it.checked }?.map { it.id }?.toSet() ?: return@collect
                movedToEnd.value = movedToEnd.value.intersect(checked)
            }
        }
        viewModelScope.launch { lists.open(spaceId) }
    }

    fun toggle(item: Item) {
        viewModelScope.launch {
            if (item.checked) lists.uncheck(spaceId, listId, item.id) else lists.check(spaceId, listId, item.id)
        }
    }

    /** The sort button: the items checked right now go to a "Checked" section at the end. */
    fun moveCheckedToEnd() {
        movedToEnd.value = ui.value.list?.items?.filter { it.checked }?.map { it.id }?.toSet().orEmpty()
    }

    fun setCategoryHeaders(shown: Boolean) {
        viewModelScope.launch { container.uiPreferences.setCategoryHeaders(shown) }
    }

    fun chooseLayout(layoutId: String?) {
        viewModelScope.launch { container.layouts.setLastLayout(listId, layoutId) }
    }

    /** Returns the error code if the input was refused (shown in the dialog), else null. */
    suspend fun addItem(name: String, quantity: String?, unit: String?, categoryId: String?): String? =
        lists.addItem(spaceId, listId, name, quantity, unit, categoryId = categoryId).exceptionOrNull()?.code()

    /** One change per field, so concurrent edits of other fields by others merge. */
    suspend fun editItem(itemId: String, changes: ItemChanges): String? {
        for ((field, value) in changes) {
            lists.editItem(spaceId, listId, itemId, field, value).exceptionOrNull()?.let { return it.code() }
        }
        return null
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch { lists.deleteItem(spaceId, listId, itemId) }
    }

    fun removeChecked() {
        viewModelScope.launch { lists.removeChecked(spaceId, listId) }
    }

    suspend fun rename(name: String): String? = lists.renameList(spaceId, listId, name).exceptionOrNull()?.code()

    fun deleteList(onDeleted: () -> Unit) {
        viewModelScope.launch {
            deleteState.value = null
            when (val result = lists.deleteList(spaceId, listId)) {
                is ApiResult.Success -> onDeleted()
                is ApiResult.Failure -> deleteState.value = result.error
            }
        }
    }

    suspend fun suggestions(query: String): List<Suggestion> = container.catalog.suggestions(spaceId, query)

    private fun Throwable.code(): String = (this as? InvalidInputException)?.code ?: "unknown"
}
