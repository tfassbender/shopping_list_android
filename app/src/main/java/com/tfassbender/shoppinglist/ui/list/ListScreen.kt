package com.tfassbender.shoppinglist.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tfassbender.shoppinglist.R
import com.tfassbender.shoppinglist.domain.Category
import com.tfassbender.shoppinglist.domain.Item
import com.tfassbender.shoppinglist.domain.ShopLayout
import com.tfassbender.shoppinglist.ui.common.AppSheet
import com.tfassbender.shoppinglist.ui.common.BarIconButton
import com.tfassbender.shoppinglist.ui.common.ConfirmSheet
import com.tfassbender.shoppinglist.ui.common.ErrorText
import com.tfassbender.shoppinglist.ui.common.Fab
import com.tfassbender.shoppinglist.ui.common.Hint
import com.tfassbender.shoppinglist.ui.common.LinkText
import com.tfassbender.shoppinglist.ui.common.Loading
import com.tfassbender.shoppinglist.ui.common.MenuEntry
import com.tfassbender.shoppinglist.ui.common.NameSheet
import com.tfassbender.shoppinglist.ui.common.Page
import com.tfassbender.shoppinglist.ui.common.PageContent
import com.tfassbender.shoppinglist.ui.common.SectionTitle
import com.tfassbender.shoppinglist.ui.common.SheetOption
import com.tfassbender.shoppinglist.ui.common.pageViewModel
import com.tfassbender.shoppinglist.ui.navigation.LocalNavigator
import com.tfassbender.shoppinglist.ui.navigation.Screen
import com.tfassbender.shoppinglist.ui.theme.AppTheme
import kotlinx.coroutines.launch

private enum class ListSheet { ADD, SHOP, RENAME, DELETE }

@Composable
fun ListScreen(spaceId: String, listId: String) {
    val vm = pageViewModel { ListViewModel(it, spaceId, listId) }
    val navigator = LocalNavigator.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val headers by vm.categoryHeaders.collectAsStateWithLifecycle()
    val isOwner by vm.isOwner.collectAsStateWithLifecycle()
    val shops by vm.shopChoices.collectAsStateWithLifecycle()
    val deleteError by vm.deleteError.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<ListSheet?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val list = ui.list

    if (list == null) {
        Page(stringResource(R.string.app_name)) { padding ->
            PageContent(padding) {
                when (ui.state) {
                    ListUiState.LoadState.SPACE_GONE -> {
                        Hint(stringResource(R.string.list_spaceGone))
                        if (ui.lostChanges > 0) Hint(stringResource(R.string.list_spaceGoneLostChanges, ui.lostChanges.toString()))
                        LinkText(stringResource(R.string.nav_toStart), navigator::toStart)
                    }
                    ListUiState.LoadState.NOT_FOUND -> Hint(stringResource(R.string.error_LIST_NOT_FOUND))
                    else -> Loading()
                }
            }
        }
        return
    }

    val checkedCount = ui.checkedCount
    val menu = buildList {
        add(MenuEntry(stringResource(R.string.list_menu_rename), Icons.Filled.EditNote) { sheet = ListSheet.RENAME })
        add(
            MenuEntry(
                if (checkedCount > 0) stringResource(R.string.list_menu_removeChecked, checkedCount.toString()) else stringResource(R.string.list_menu_removeCheckedNone),
                Icons.Filled.CleaningServices,
                enabled = checkedCount > 0,
            ) { vm.removeChecked() },
        )
        add(MenuEntry(stringResource(R.string.space_catalog), Icons.Filled.Sell, divider = true) { navigator.push(Screen.Catalog(spaceId)) })
        add(MenuEntry(stringResource(R.string.space_layouts), Icons.Filled.Store) { navigator.push(Screen.Layouts(spaceId)) })
        add(MenuEntry(stringResource(R.string.history_title), Icons.Filled.History) { navigator.push(Screen.History(spaceId)) })
        if (isOwner) add(MenuEntry(stringResource(R.string.list_delete), Icons.Filled.Delete, danger = true, divider = true) { sheet = ListSheet.DELETE })
    }

    Page(
        title = list.name,
        sub = { ShopLine(ui.layout) { sheet = ListSheet.SHOP } },
        actions = {
            BarIconButton(Icons.Filled.Sell, stringResource(R.string.list_categoryHeaders), { vm.setCategoryHeaders(!headers) }, dimmed = !headers)
            BarIconButton(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.list_sortChecked), vm::moveCheckedToEnd, enabled = checkedCount > 0)
        },
        menu = menu,
        fab = { Fab(stringResource(R.string.addItem_title), { sheet = ListSheet.ADD }) },
    ) { padding ->
        ItemList(
            padding = padding,
            sections = ui.sections,
            categories = ui.catalog?.categories.orEmpty(),
            colorBars = !headers,
            empty = list.items.isEmpty(),
            error = { ErrorText(deleteError, Modifier.padding(top = 8.dp)) },
            onToggle = vm::toggle,
            onEdit = { editingId = it.id },
        )
    }

    when (sheet) {
        ListSheet.ADD -> AddItemSheet(
            catalog = ui.catalog,
            suggestions = vm::suggestions,
            onAdd = vm::addItem,
            onDismiss = { sheet = null },
        )
        ListSheet.SHOP -> ShopSheet(
            shops = shops,
            selected = ui.layout?.id,
            onSelect = {
                vm.chooseLayout(it)
                sheet = null
            },
            onManage = {
                sheet = null
                navigator.push(Screen.Layouts(spaceId))
            },
            onDismiss = { sheet = null },
        )
        ListSheet.RENAME -> {
            var error by remember { mutableStateOf<String?>(null) }
            NameSheet(
                title = stringResource(R.string.list_menu_rename),
                submitLabel = stringResource(R.string.common_save),
                initialValue = list.name,
                errorCode = error,
                onSubmit = { name ->
                    scope.launch {
                        error = vm.rename(name)
                        if (error == null) sheet = null
                    }
                },
                onDismiss = { sheet = null },
            )
        }
        ListSheet.DELETE -> ConfirmSheet(
            title = stringResource(R.string.list_deleteTitle, list.name),
            text = stringResource(R.string.list_deleteText),
            confirmLabel = stringResource(R.string.list_delete),
            // Back to the page that opened the list (start or space page); back must not lead to the deleted list.
            onConfirm = { vm.deleteList(onDeleted = { if (navigator.canGoBack) navigator.back() else navigator.toStart() }) },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }

    val editing = editingId?.let { id -> list.items.firstOrNull { it.id == id } }
    if (editing != null) {
        ItemEditorSheet(
            item = editing,
            catalog = ui.catalog,
            onSave = { changes -> vm.editItem(editing.id, changes) },
            onDelete = { vm.deleteItem(editing.id) },
            onDismiss = { editingId = null },
        )
    }
}

/** "Sorted for Aldi ▾" / "Order of adding ▾" - opens the shop selection. */
@Composable
private fun ShopLine(layout: ShopLayout?, onClick: () -> Unit) {
    Row(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (layout != null) stringResource(R.string.list_sortedFor, layout.name) else stringResource(R.string.list_unsorted),
            fontSize = 15.sp,
            color = AppTheme.colors.brandInk.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = AppTheme.colors.brandInk.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ItemList(
    padding: PaddingValues,
    sections: List<ItemSection>,
    categories: List<Category>,
    colorBars: Boolean,
    empty: Boolean,
    error: @Composable () -> Unit,
    onToggle: (Item) -> Unit,
    onEdit: (Item) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 112.dp),
    ) {
        item(key = "__error") { error() }
        if (empty) {
            item(key = "__empty") {
                Text(
                    stringResource(R.string.list_empty),
                    fontSize = 19.sp,
                    color = AppTheme.colors.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                )
            }
        }
        sections.forEach { section ->
            if (section.kind == ItemSection.Kind.ITEMS) {
                item(key = "${section.key}-gap") { Spacer(Modifier.height(12.dp)) }
            } else {
                item(key = "${section.key}-title") {
                    val title = when (section.kind) {
                        ItemSection.Kind.CATEGORY -> section.category!!.name
                        ItemSection.Kind.CHECKED -> stringResource(R.string.list_section_checked)
                        else -> stringResource(R.string.list_section_other)
                    }
                    SectionTitle(title, color = section.category?.let { parseColor(it.color) })
                }
            }
            items(section.items, key = { it.id }) { item ->
                val bar = if (colorBars) categories.firstOrNull { it.id == item.categoryId }?.let { parseColor(it.color) } ?: Color.Transparent else null
                ItemRow(item, bar, onToggle = { onToggle(item) }, onEdit = { onEdit(item) }, modifier = Modifier.animateItem())
            }
        }
    }
}

/** One item: tapping the row checks/unchecks it; the pencil opens the details. Checked = struck through. */
@Composable
private fun ItemRow(item: Item, barColor: Color?, onToggle: () -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val amount = formatAmount(item.quantity, item.unit)
    val checkedLabel = stringResource(R.string.warnings_checked)
    val uncheckedLabel = stringResource(R.string.warnings_unchecked)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            if (barColor != null) {
                Box(
                    Modifier
                        .padding(vertical = 10.dp)
                        .width(6.dp)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(barColor)
                        .alpha(if (item.checked) 0.4f else 1f),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Checkbox, onClick = onToggle)
                    .semantics { stateDescription = if (item.checked) checkedLabel else uncheckedLabel }
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                val decoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None
                val weight = if (item.checked) FontWeight.Normal else FontWeight.SemiBold
                Text(
                    buildAnnotatedString {
                        if (amount.isNotEmpty()) {
                            withStyle(SpanStyle(fontWeight = weight)) { append(amount) }
                            append(" ")
                        }
                        withStyle(SpanStyle(fontWeight = weight)) { append(item.name) }
                    },
                    fontSize = 19.sp,
                    color = if (item.checked) colors.muted else colors.fg,
                    textDecoration = decoration,
                )
                if (!item.note.isNullOrEmpty()) {
                    Text(item.note, fontSize = 15.sp, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = decoration)
                }
            }
            val editLabel = stringResource(R.string.item_editNamed, item.name)
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onEdit)
                    .semantics { contentDescription = editLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider(color = colors.line)
    }
}

/**
 * "Where are you shopping?" - picks the shop layout the list is sorted by. Shops connected to this list
 * come first. The choice is remembered on this device.
 */
@Composable
private fun ShopSheet(shops: List<ShopLayout>, selected: String?, onSelect: (String?) -> Unit, onManage: () -> Unit, onDismiss: () -> Unit) {
    AppSheet(stringResource(R.string.shop_title), onDismiss) {
        Column {
            SheetOption(stringResource(R.string.shop_none), stringResource(R.string.shop_noneHint), { onSelect(null) }, selected = selected == null)
            shops.forEach { shop ->
                SheetOption(shop.name, stringResource(R.string.shop_layoutHint), { onSelect(shop.id) }, selected = selected == shop.id)
            }
        }
        if (shops.isEmpty()) Text(stringResource(R.string.shop_noShops), color = AppTheme.colors.muted)
        LinkText(stringResource(R.string.shop_manage), onManage)
    }
}

/** `#rrggbb` → Color (grey for anything else). */
fun parseColor(hex: String): Color = runCatching { Color(hex.toColorInt()) }.getOrDefault(Color.Gray)
