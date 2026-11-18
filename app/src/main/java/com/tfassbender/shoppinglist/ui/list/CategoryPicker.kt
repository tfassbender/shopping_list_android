package com.tfassbender.shoppinglist.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tfassbender.shoppinglist.R
import com.tfassbender.shoppinglist.domain.Category
import com.tfassbender.shoppinglist.ui.common.ColorDot
import com.tfassbender.shoppinglist.ui.theme.AppTheme

/**
 * Category field of the item sheets (port of the web app's `CategoryPicker`). A space can have many
 * categories, so this is a field that opens a list right below it, with a search field on top. The first
 * entry is "no category" (or "automatic"), labelled by [noneLabel] - only while not searching: a search is
 * for finding a category. Enter on the keyboard takes the first match. [inactive] (categories the chosen
 * shop doesn't have) come last and greyed out, but can be chosen.
 */
@Composable
fun CategoryPicker(
    label: String,
    noneLabel: String,
    value: String,
    categories: List<Category>,
    onChange: (String) -> Unit,
    inactive: Set<String> = emptySet(),
) {
    val colors = AppTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val selected = categories.firstOrNull { it.id == value }
    val focusManager = LocalFocusManager.current

    val q = query.trim().lowercase()
    val (activeMatches, inactiveMatches) = categories.filter { it.name.lowercase().contains(q) }.partition { it.id !in inactive }
    val matches = activeMatches + inactiveMatches
    val options: List<Category?> = if (q.isNotEmpty()) matches else listOf(null) + matches

    fun choose(categoryId: String) {
        // the search field goes away: don't let the focus jump to another field (and its keyboard)
        focusManager.clearFocus()
        onChange(categoryId)
        open = false
        query = ""
    }

    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(bottom = 4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.raised)
                .border(1.dp, colors.line, RoundedCornerShape(8.dp))
                .clickable(role = Role.DropdownList) {
                    open = !open
                    query = ""
                }
                .semantics { contentDescription = "$label: ${selected?.name ?: noneLabel}" }
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CategoryDot(selected)
            Text(selected?.name ?: noneLabel, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = colors.muted, modifier = Modifier.rotate(if (open) 180f else 0f))
        }
        if (open) {
            Column(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, colors.line, RoundedCornerShape(8.dp))
                    .background(colors.surface),
            ) {
                SearchField(query, { query = it }, onSearch = { if (options.isNotEmpty()) choose(options.first()?.id.orEmpty()) })
                HorizontalDivider(color = colors.line)
                // ~5 rows visible, the rest scrolls - the sheet stays usable on small phones
                Column(Modifier.heightIn(max = 216.dp).verticalScroll(rememberScrollState())) {
                    options.forEachIndexed { index, option ->
                        val id = option?.id.orEmpty()
                        val isSelected = id == value
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .clickable(role = Role.RadioButton) { choose(id) }
                                .semantics { this.selected = isSelected }
                                .alpha(if (id in inactive) 0.45f else 1f)
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CategoryDot(option)
                            Text(
                                option?.name ?: noneLabel,
                                color = colors.fg,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accent)
                        }
                        if (index < options.lastIndex) HorizontalDivider(color = colors.line)
                    }
                    if (options.isEmpty()) {
                        Text(
                            stringResource(R.string.category_noMatch),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.muted,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The category color; an empty ring for "no category". */
@Composable
private fun CategoryDot(category: Category?) {
    if (category != null) ColorDot(parseColor(category.color), 12.dp) else ColorDot(Color.Transparent, 12.dp, outlined = true)
}

/** The search field at the top of the open list: focused at once, Enter takes the first match. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onSearch: () -> Unit) {
    val colors = AppTheme.colors
    val placeholder = stringResource(R.string.category_search)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = query,
        onValueChange = { if (it.length <= 100) onQuery(it) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.fg),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(colors.raised)
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) Text(placeholder, color = colors.muted)
                inner()
            }
        },
    )
}
