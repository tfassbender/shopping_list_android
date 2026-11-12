package com.tfassbender.shoppinglist.ui.space

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tfassbender.shoppinglist.AppContainer
import com.tfassbender.shoppinglist.R
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.domain.Category
import com.tfassbender.shoppinglist.domain.HistoryEntry
import com.tfassbender.shoppinglist.ui.common.ActionViewModel
import com.tfassbender.shoppinglist.ui.common.AppButton
import com.tfassbender.shoppinglist.ui.common.AppSheet
import com.tfassbender.shoppinglist.ui.common.AppTextField
import com.tfassbender.shoppinglist.ui.common.ButtonVariant
import com.tfassbender.shoppinglist.ui.common.Chip
import com.tfassbender.shoppinglist.ui.common.ColorDot
import com.tfassbender.shoppinglist.ui.common.ColorSwatch
import com.tfassbender.shoppinglist.ui.common.ConfirmSheet
import com.tfassbender.shoppinglist.ui.common.ErrorText
import com.tfassbender.shoppinglist.ui.common.Hint
import com.tfassbender.shoppinglist.ui.common.ListRow
import com.tfassbender.shoppinglist.ui.common.Loading
import com.tfassbender.shoppinglist.ui.common.OnlineOnly
import com.tfassbender.shoppinglist.ui.common.Page
import com.tfassbender.shoppinglist.ui.common.PageContent
import com.tfassbender.shoppinglist.ui.common.SectionTitle
import com.tfassbender.shoppinglist.ui.common.SheetActions
import com.tfassbender.shoppinglist.ui.common.SortableColumn
import com.tfassbender.shoppinglist.ui.common.pageViewModel
import com.tfassbender.shoppinglist.ui.common.stateIn
import com.tfassbender.shoppinglist.ui.list.parseColor
import com.tfassbender.shoppinglist.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

// ---- categories & units ----

class CatalogViewModel(private val container: AppContainer, private val spaceId: String) : ActionViewModel() {
    private val repo = container.catalog

    /** A new category order shown at once, until the server confirmed (or refused) it. */
    private val pendingOrder = MutableStateFlow<List<String>?>(null)

    val catalog = combine(repo.catalog(spaceId), pendingOrder) { catalog, order ->
        if (catalog == null || order == null) catalog
        else catalog.copy(categories = order.mapNotNull { id -> catalog.categories.firstOrNull { it.id == id } })
    }.stateIn(this, null)
    val online = container.online.stateIn(this, true)

    init {
        viewModelScope.launch { repo.refresh(spaceId) }
    }

    fun reorder(ids: List<String>) = act({
        pendingOrder.value = ids
        repo.reorderCategories(spaceId, ids).also { pendingOrder.value = null }
    })

    fun add(name: String, color: String) = act({ repo.addCategory(spaceId, name, color) })

    /** [onFailed]: e.g. the name exists already - the row shows the saved name again. */
    fun update(category: Category, name: String, color: String, onFailed: () -> Unit = {}) =
        act({ repo.updateCategory(spaceId, category.id, name, color).also { if (it is ApiResult.Failure) onFailed() } })

    fun delete(category: Category) = act({ repo.deleteCategory(spaceId, category.id) })

    fun setUnits(units: List<String>) = act({ repo.setUnits(spaceId, units) })
}

private const val DEFAULT_NEW_COLOR = "#78909c"

/** Manage the categories (name, color, order) and units of a space. */
@Composable
fun CatalogScreen(spaceId: String) {
    val vm = pageViewModel { CatalogViewModel(it, spaceId) }
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<Category?>(null) }

    Page(stringResource(R.string.catalog_title)) { padding ->
        PageContent(padding) {
            val current = catalog
            ErrorText(error, Modifier.padding(top = 8.dp))
            if (current == null) {
                Loading()
                return@PageContent
            }
            OnlineOnly(online) { enabled ->
                SectionTitle(stringResource(R.string.catalog_categories))
                Text(stringResource(R.string.catalog_categoriesHint), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted, modifier = Modifier.padding(bottom = 12.dp))
                SortableColumn(
                    items = current.categories,
                    key = { it.id },
                    label = { it.name },
                    onReorder = vm::reorder,
                    enabled = enabled,
                ) { category ->
                    CategoryRow(category, enabled, onSave = { name, color, onFailed -> vm.update(category, name, color, onFailed) }, onDelete = { deleting = category })
                }
                NewCategoryForm(enabled, vm::add)
                SectionTitle(stringResource(R.string.catalog_units))
                UnitsEditor(current.units, enabled, vm::setUnits)
            }
        }
    }
    deleting?.let { category ->
        ConfirmSheet(
            title = stringResource(R.string.common_deleteTitle, category.name),
            text = stringResource(R.string.catalog_deleteCategoryText),
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = { vm.delete(category) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun CategoryRow(category: Category, enabled: Boolean, onSave: (String, String, () -> Unit) -> Unit, onDelete: () -> Unit) {
    val colors = AppTheme.colors
    var name by remember(category.name) { mutableStateOf(category.name) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Blank or refused (e.g. the name exists already): show the saved name again.
    fun save() {
        val trimmed = name.trim()
        when {
            trimmed.isEmpty() -> name = category.name
            trimmed != category.name -> onSave(trimmed, category.color) { name = category.name }
        }
    }
    val nameLabel = stringResource(R.string.catalog_categoryName)
    Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ColorSwatch(category.color, stringResource(R.string.catalog_color, category.name), { color -> onSave(category.name, color) {} }, enabled = enabled)
        BasicTextField(
            value = name,
            onValueChange = { if (it.length <= 40) name = it },
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.fg),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .heightIn(min = 44.dp)
                .onFocusChanged {
                    if (focused && !it.isFocused) save()
                    focused = it.isFocused
                }
                .semantics { contentDescription = nameLabel },
            decorationBox = { inner -> Box(Modifier.padding(horizontal = 4.dp, vertical = 11.dp)) { inner() } },
        )
        IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.catalog_deleteCategory, category.name), tint = colors.muted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun NewCategoryForm(enabled: Boolean, onAdd: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf(DEFAULT_NEW_COLOR) }
    val submit = {
        if (name.isNotBlank()) {
            onAdd(name.trim(), color)
            name = ""
        }
    }
    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(bottom = 1.dp)) { ColorSwatch(color, stringResource(R.string.catalog_newColor), { color = it }, size = 48.dp, enabled = enabled) }
        AppTextField(name, { name = it }, stringResource(R.string.catalog_newCategory), Modifier.weight(1f), maxLength = 40, enabled = enabled, onImeAction = submit)
        AppButton(stringResource(R.string.common_create), submit, enabled = enabled && name.isNotBlank())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnitsEditor(units: List<String>, enabled: Boolean, onChange: (List<String>) -> Unit) {
    var newUnit by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (newUnit.isNotBlank()) {
            onChange(units + newUnit.trim())
            newUnit = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            units.forEach { unit ->
                Chip {
                    Text(unit)
                    IconButton(onClick = { onChange(units - unit) }, enabled = enabled, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.catalog_removeUnit, unit), tint = AppTheme.colors.muted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField(newUnit, { newUnit = it }, stringResource(R.string.catalog_newUnit), Modifier.weight(1f), maxLength = 20, enabled = enabled, onImeAction = submit)
            AppButton(stringResource(R.string.common_create), submit, enabled = enabled && newUnit.isNotBlank())
        }
    }
}

// ---- items used before ----

class HistoryViewModel(private val container: AppContainer, private val spaceId: String) : ActionViewModel() {
    val history = container.catalog.history(spaceId).stateIn(this, emptyList())
    val catalog = container.catalog.catalog(spaceId).stateIn(this, null)
    val online = container.online.stateIn(this, true)
    private val loaded = MutableStateFlow(false)
    val isLoaded = loaded.asStateFlow()

    init {
        viewModelScope.launch {
            container.catalog.refreshHistory(spaceId)
            loaded.value = true
        }
    }

    fun rename(entry: HistoryEntry, newName: String, onDone: () -> Unit) =
        act({ container.catalog.renameHistoryItem(spaceId, entry.name, newName) }) { onDone() }

    fun remove(entry: HistoryEntry, onDone: () -> Unit) = act({ container.catalog.removeHistoryItem(spaceId, entry.name) }) { onDone() }
}

/**
 * "Items used before": the item history of a space, which feeds the suggestions and the automatic
 * category. Tap an item to rename it (e.g. a typo) or remove it. A search field filters the list.
 */
@Composable
fun HistoryScreen(spaceId: String) {
    val vm = pageViewModel { HistoryViewModel(it, spaceId) }
    val entries by vm.history.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val loaded by vm.isLoaded.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<HistoryEntry?>(null) }
    val query = filter.trim().lowercase()

    Page(stringResource(R.string.history_title)) { padding ->
        PageContent(padding) {
            OnlineOnly(online) { enabled ->
                Hint(stringResource(R.string.history_explanation))
                if (entries.isNotEmpty()) AppTextField(filter, { filter = it }, stringResource(R.string.history_search), maxLength = 100)
                if (entries.isEmpty()) {
                    if (loaded) Text(stringResource(R.string.history_empty), color = AppTheme.colors.muted, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
                    else Loading()
                }
                Column(Modifier.padding(top = 8.dp)) {
                    entries.filter { it.name.lowercase().contains(query) }.forEach { entry ->
                        val category = catalog?.categories?.firstOrNull { it.id == entry.categoryId }
                        ListRow(
                            entry.name,
                            hint = listOfNotNull(category?.name, entry.unit).joinToString(" · "),
                            start = {
                                // the category color as a dot, like the headers in the list; an empty ring without category
                                if (category != null) ColorDot(parseColor(category.color), 14.dp) else ColorDot(androidx.compose.ui.graphics.Color.Transparent, 14.dp, outlined = true)
                            },
                            onClick = { editing = entry },
                            enabled = enabled,
                            chevron = true,
                        )
                    }
                }
            }
        }
    }
    editing?.let { entry -> HistoryEntrySheet(vm, entry, onDismiss = { editing = null }) }
}

/** Rename (merged with an existing entry of the new name) or remove one remembered item. */
@Composable
private fun HistoryEntrySheet(vm: HistoryViewModel, entry: HistoryEntry, onDismiss: () -> Unit) {
    val error by vm.error.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    var name by rememberSaveable(entry.name) { mutableStateOf(entry.name) }
    LaunchedEffect(entry) { vm.clearError() }
    val save = {
        if (name.isNotBlank() && name.trim() != entry.name) vm.rename(entry, name.trim(), onDismiss) else onDismiss()
    }
    AppSheet(entry.name, onDismiss) {
        AppTextField(name, { name = it }, stringResource(R.string.item_name), maxLength = 100, autoFocus = true, onImeAction = save)
        Text(stringResource(R.string.history_renameHint), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
        ErrorText(error)
        SheetActions(start = {
            AppButton(stringResource(R.string.history_remove), { vm.remove(entry, onDismiss) }, variant = ButtonVariant.GHOST, enabled = !pending, textColor = AppTheme.colors.danger)
        }) {
            AppButton(stringResource(R.string.common_cancel), onDismiss, variant = ButtonVariant.GHOST)
            AppButton(stringResource(R.string.common_save), save, enabled = name.isNotBlank() && !pending)
        }
    }
}
