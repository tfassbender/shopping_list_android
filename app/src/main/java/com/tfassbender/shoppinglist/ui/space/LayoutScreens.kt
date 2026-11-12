package com.tfassbender.shoppinglist.ui.space

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tfassbender.shoppinglist.AppContainer
import com.tfassbender.shoppinglist.R
import com.tfassbender.shoppinglist.domain.Category
import com.tfassbender.shoppinglist.domain.LayoutMode
import com.tfassbender.shoppinglist.domain.ShopLayout
import com.tfassbender.shoppinglist.domain.normalizeName
import com.tfassbender.shoppinglist.ui.common.ActionViewModel
import com.tfassbender.shoppinglist.ui.common.AppButton
import com.tfassbender.shoppinglist.ui.common.AppSwitch
import com.tfassbender.shoppinglist.ui.common.AppTextField
import com.tfassbender.shoppinglist.ui.common.ButtonVariant
import com.tfassbender.shoppinglist.ui.common.ColorDot
import com.tfassbender.shoppinglist.ui.common.ConfirmSheet
import com.tfassbender.shoppinglist.ui.common.ErrorText
import com.tfassbender.shoppinglist.ui.common.Fab
import com.tfassbender.shoppinglist.ui.common.Hint
import com.tfassbender.shoppinglist.ui.common.ListRow
import com.tfassbender.shoppinglist.ui.common.Loading
import com.tfassbender.shoppinglist.ui.common.NameSheet
import com.tfassbender.shoppinglist.ui.common.OnlineOnly
import com.tfassbender.shoppinglist.ui.common.Page
import com.tfassbender.shoppinglist.ui.common.PageContent
import com.tfassbender.shoppinglist.ui.common.SectionTitle
import com.tfassbender.shoppinglist.ui.common.SortableColumn
import com.tfassbender.shoppinglist.ui.common.pageViewModel
import com.tfassbender.shoppinglist.ui.common.stateIn
import com.tfassbender.shoppinglist.ui.list.parseColor
import com.tfassbender.shoppinglist.ui.navigation.LocalNavigator
import com.tfassbender.shoppinglist.ui.navigation.Screen
import com.tfassbender.shoppinglist.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@Composable
private fun modeName(mode: LayoutMode) = stringResource(if (mode == LayoutMode.CATEGORIES) R.string.layouts_mode_CATEGORIES else R.string.layouts_mode_ITEMS)

// ---- overview ----

class LayoutsViewModel(private val container: AppContainer, private val spaceId: String) : ActionViewModel() {
    val layouts = container.layouts.layouts(spaceId).stateIn(this, null)
    val online = container.online.stateIn(this, true)

    init {
        viewModelScope.launch { container.layouts.refresh(spaceId) }
    }

    fun create(name: String, onCreated: (ShopLayout) -> Unit) = act({ container.layouts.create(spaceId, name, LayoutMode.CATEGORIES) }, onCreated)
}

/** The shop layouts of a space; + creates one. */
@Composable
fun LayoutsScreen(spaceId: String) {
    val vm = pageViewModel { LayoutsViewModel(it, spaceId) }
    val navigator = LocalNavigator.current
    val layouts by vm.layouts.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }

    Page(
        stringResource(R.string.layouts_title),
        fab = { Fab(stringResource(R.string.layouts_new), { creating = true }, enabled = online) },
    ) { padding ->
        PageContent(padding) {
            Hint(stringResource(R.string.layouts_explanation))
            val all = layouts
            when {
                all == null -> Loading()
                all.isEmpty() -> Text(stringResource(R.string.layouts_emptyHint), color = AppTheme.colors.muted, modifier = Modifier.padding(vertical = 24.dp))
                else -> all.forEach { layout ->
                    ListRow(
                        title = { Text(layout.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold) },
                        hint = modeName(layout.mode),
                        onClick = { navigator.push(Screen.LayoutEditor(spaceId, layout.id)) },
                        chevron = true,
                    )
                }
            }
        }
    }
    if (creating) {
        NameSheet(
            title = stringResource(R.string.layouts_new),
            submitLabel = stringResource(R.string.common_create),
            pending = pending,
            error = error,
            onSubmit = { name ->
                vm.create(name) { layout ->
                    creating = false
                    navigator.push(Screen.LayoutEditor(spaceId, layout.id))
                }
            },
            onDismiss = {
                creating = false
                vm.clearError()
            },
        )
    }
}

// ---- one shop ----

/** Category order of a layout for display: its own order (existing categories only), then new ones. */
fun categoriesInLayoutOrder(layout: ShopLayout, categories: List<Category>): List<Category> {
    val byId = categories.associateBy { it.id }
    return layout.categoryOrder.mapNotNull { byId[it] } + categories.filterNot { it.id in layout.categoryOrder }
}

class LayoutEditorViewModel(private val container: AppContainer, private val spaceId: String, private val layoutId: String) : ActionViewModel() {
    private val repo = container.layouts

    /** A changed layout shown at once (drag, add, remove), until the server answered. */
    private val pendingLayout = MutableStateFlow<ShopLayout?>(null)

    val layout = combine(repo.layouts(spaceId), pendingLayout) { layouts, pending ->
        pending ?: layouts.firstOrNull { it.id == layoutId }
    }.stateIn(this, null)
    val catalog = container.catalog.catalog(spaceId).stateIn(this, null)
    val lists = container.lists.lists(spaceId).stateIn(this, emptyList())
    val online = container.online.stateIn(this, true)

    private fun optimistic(updated: ShopLayout, call: suspend () -> com.tfassbender.shoppinglist.data.remote.ApiResult<ShopLayout>) = act({
        pendingLayout.value = updated
        call().also { pendingLayout.value = null }
    })

    fun rename(layout: ShopLayout, name: String) = act({ repo.update(spaceId, layoutId, name, layout.mode) })

    fun setMode(layout: ShopLayout, mode: LayoutMode) = optimistic(layout.copy(mode = mode)) { repo.update(spaceId, layoutId, layout.name, mode) }

    fun setCategoryOrder(layout: ShopLayout, ids: List<String>) = optimistic(layout.copy(categoryOrder = ids)) { repo.setCategoryOrder(spaceId, layoutId, ids) }

    fun setItemOrder(layout: ShopLayout, names: List<String>) = optimistic(layout.copy(itemOrder = names)) { repo.setItemOrder(spaceId, layoutId, names) }

    fun setConnected(layout: ShopLayout, listId: String, connect: Boolean) {
        val listIds = if (connect) layout.listIds + listId else layout.listIds - listId
        optimistic(layout.copy(listIds = listIds)) {
            if (connect) repo.connectList(spaceId, layoutId, listId) else repo.disconnectList(spaceId, layoutId, listId)
        }
    }

    fun copy(name: String, onCopied: (ShopLayout) -> Unit) = act({ repo.copy(spaceId, layoutId, name) }, onCopied)

    fun delete(onDeleted: () -> Unit) = act({ repo.delete(spaceId, layoutId) }) { onDeleted() }

    /** Item names of the connected lists that are not in the order yet (each once, case-insensitive). */
    fun itemsOfConnectedLists(layout: ShopLayout): List<String> {
        val known = layout.itemOrder.map(::normalizeName).toMutableSet()
        return lists.value.filter { it.id in layout.listIds }.flatMap { it.items }.map { it.name }
            .filter { known.add(normalizeName(it)) }
    }
}

@Composable
fun LayoutEditorScreen(spaceId: String, layoutId: String) {
    val vm = pageViewModel { LayoutEditorViewModel(it, spaceId, layoutId) }
    val navigator = LocalNavigator.current
    val layout by vm.layout.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    var copying by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val current = layout
    if (current == null) {
        Page(stringResource(R.string.layouts_title)) { padding ->
            PageContent(padding) { Hint(stringResource(R.string.error_LAYOUT_NOT_FOUND)) }
        }
        return
    }

    Page(current.name) { padding ->
        PageContent(padding) {
            ErrorText(error, Modifier.padding(top = 8.dp))
            OnlineOnly(online) { enabled ->
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    RenameForm(current.name, enabled) { vm.rename(current, it) }
                    ModeChoice(current.mode, enabled) { vm.setMode(current, it) }
                    if (current.mode == LayoutMode.CATEGORIES) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.layouts_dragHint), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
                            SortableColumn(
                                items = categoriesInLayoutOrder(current, catalog?.categories.orEmpty()),
                                key = { it.id },
                                label = { it.name },
                                onReorder = { vm.setCategoryOrder(current, it) },
                                enabled = enabled,
                            ) { category ->
                                Row(Modifier.heightIn(min = 48.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ColorDot(parseColor(category.color), 12.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(category.name)
                                }
                            }
                            AppButton(
                                stringResource(R.string.layouts_editCategories),
                                { navigator.push(Screen.Catalog(spaceId)) },
                                variant = ButtonVariant.SECONDARY,
                                icon = Icons.Filled.Sell,
                            )
                        }
                    } else {
                        ItemOrderEditor(current, enabled, onChange = { vm.setItemOrder(current, it) }, fromLists = { vm.itemsOfConnectedLists(current) })
                    }
                    if (lists.isNotEmpty()) {
                        Column {
                            SectionTitle(stringResource(R.string.layouts_connectedLists))
                            Text(stringResource(R.string.layouts_connectedHint), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
                            lists.forEach { list ->
                                val connected = list.id in current.listIds
                                Column {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 56.dp)
                                            .clickable(enabled = enabled, role = Role.Switch) { vm.setConnected(current, list.id, !connected) }
                                            .padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(list.name, modifier = Modifier.weight(1f))
                                        AppSwitch(connected, { vm.setConnected(current, list.id, it) }, enabled = enabled)
                                    }
                                    HorizontalDivider(color = AppTheme.colors.line)
                                }
                            }
                        }
                    }
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton(stringResource(R.string.layouts_copy), { copying = true }, variant = ButtonVariant.SECONDARY, icon = Icons.Filled.ContentCopy, enabled = enabled)
                        AppButton(stringResource(R.string.layouts_delete), { confirmDelete = true }, variant = ButtonVariant.DANGER, enabled = enabled)
                    }
                }
            }
        }
    }
    if (copying) {
        NameSheet(
            title = stringResource(R.string.layouts_copy),
            // the server allows 60 characters; a long name is cut so the suggestion is always valid
            initialValue = stringResource(R.string.layouts_copyName, current.name).take(60),
            submitLabel = stringResource(R.string.common_create),
            pending = pending,
            error = error,
            onSubmit = { name ->
                vm.copy(name) { copy ->
                    copying = false
                    navigator.replace(Screen.LayoutEditor(spaceId, copy.id))
                }
            },
            onDismiss = { copying = false },
        )
    }
    if (confirmDelete) {
        ConfirmSheet(
            title = stringResource(R.string.common_deleteTitle, current.name),
            text = stringResource(R.string.layouts_deleteText),
            confirmLabel = stringResource(R.string.layouts_delete),
            onConfirm = { vm.delete(navigator::back) },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun RenameForm(name: String, enabled: Boolean, onRename: (String) -> Unit) {
    var value by rememberSaveable(name) { mutableStateOf(name) }
    val submit = { if (value.isNotBlank() && value.trim() != name) onRename(value.trim()) }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppTextField(value, { value = it }, stringResource(R.string.common_name), Modifier.weight(1f), maxLength = 60, enabled = enabled, onImeAction = submit)
        AppButton(stringResource(R.string.common_rename), submit, enabled = enabled && value.isNotBlank() && value.trim() != name)
    }
}

@Composable
private fun ModeChoice(mode: LayoutMode, enabled: Boolean, onChange: (LayoutMode) -> Unit) {
    Column {
        Text(stringResource(R.string.layouts_sortBy), fontWeight = FontWeight.Medium, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp))
        LayoutMode.entries.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(enabled = enabled, role = Role.RadioButton) { onChange(option) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = mode == option, onClick = null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = AppTheme.colors.accent))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(modeName(option))
                    Text(
                        stringResource(if (option == LayoutMode.CATEGORIES) R.string.layouts_modeHint_CATEGORIES else R.string.layouts_modeHint_ITEMS),
                        fontSize = 13.sp,
                        color = AppTheme.colors.muted,
                    )
                }
            }
        }
    }
}

/** Mode ITEMS: an ordered list of item names, filled by hand or from the items of the connected lists. */
@Composable
private fun ItemOrderEditor(layout: ShopLayout, enabled: Boolean, onChange: (List<String>) -> Unit, fromLists: () -> List<String>) {
    var newEntry by rememberSaveable { mutableStateOf("") }
    var duplicate by remember { mutableStateOf<String?>(null) }
    // Same normalization as the server, which refuses entries that differ only in case or spaces.
    val known = layout.itemOrder.map(::normalizeName).toSet()
    val add = {
        val name = newEntry.trim()
        if (name.isNotEmpty()) {
            if (normalizeName(name) in known) {
                duplicate = name // keep the text, so the user sees what was refused
            } else {
                duplicate = null
                onChange(layout.itemOrder + name)
                newEntry = ""
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.layouts_itemsHint), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
        if (layout.itemOrder.isNotEmpty()) {
            SortableColumn(layout.itemOrder, key = { it }, label = { it }, onReorder = onChange, enabled = enabled) { name ->
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onChange(layout.itemOrder - name) }, enabled = enabled, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.layouts_removeEntry, name), tint = AppTheme.colors.muted, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField(newEntry, {
                newEntry = it
                duplicate = null
            }, stringResource(R.string.layouts_newEntry), Modifier.weight(1f), maxLength = 100, enabled = enabled, onImeAction = add)
            AppButton(stringResource(R.string.list_add), add, enabled = enabled && newEntry.isNotBlank())
        }
        duplicate?.let { Text(stringResource(R.string.layouts_entryExists, it), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted) }
        if (layout.listIds.isNotEmpty()) {
            AppButton(stringResource(R.string.layouts_importItems), {
                val added = fromLists()
                if (added.isNotEmpty()) onChange(layout.itemOrder + added)
            }, variant = ButtonVariant.SECONDARY, enabled = enabled)
        }
    }
}
