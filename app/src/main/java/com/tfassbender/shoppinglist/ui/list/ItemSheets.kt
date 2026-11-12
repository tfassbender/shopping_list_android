package com.tfassbender.shoppinglist.ui.list

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tfassbender.shoppinglist.R
import com.tfassbender.shoppinglist.domain.Catalog
import com.tfassbender.shoppinglist.domain.Item
import com.tfassbender.shoppinglist.domain.ItemField
import com.tfassbender.shoppinglist.domain.Suggestion
import com.tfassbender.shoppinglist.ui.common.AppButton
import com.tfassbender.shoppinglist.ui.common.AppSheet
import com.tfassbender.shoppinglist.ui.common.AppTextField
import com.tfassbender.shoppinglist.ui.common.ButtonVariant
import com.tfassbender.shoppinglist.ui.common.ColorDot
import com.tfassbender.shoppinglist.ui.common.ErrorCodeText
import com.tfassbender.shoppinglist.ui.common.SelectField
import com.tfassbender.shoppinglist.ui.common.SheetActions
import com.tfassbender.shoppinglist.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The server accepts at most 20 characters for the quantity. */
private const val MAX_AMOUNT_LENGTH = 20

/**
 * Amount and unit side by side. The amount takes only numbers (number keyboard); an invalid number is
 * marked and blocks saving. The unit is picked from the space's units; a unit the item already has but
 * which is not in that list stays selectable, so editing never drops it silently.
 */
@Composable
private fun AmountUnitFields(quantity: String, unit: String, units: List<String>, onQuantity: (String) -> Unit, onUnit: (String) -> Unit) {
    val options = (if (unit.isNotEmpty() && unit !in units) listOf(unit) + units else units)
    val invalid = !isValidAmount(quantity)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField(
                quantity,
                { if (isTypedAmount(it)) onQuantity(it) },
                stringResource(R.string.item_amount),
                modifier = Modifier.weight(1f),
                maxLength = MAX_AMOUNT_LENGTH,
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Next,
                isError = invalid,
            )
            SelectField(
                label = stringResource(R.string.item_unit),
                value = unit,
                options = listOf("" to stringResource(R.string.item_noUnit)) + options.map { it to it },
                onChange = onUnit,
                modifier = Modifier.weight(1f),
            )
        }
        if (invalid) Text(stringResource(R.string.item_invalidAmount), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.danger)
    }
}

@Composable
private fun CategorySelect(label: String, noneLabel: String, value: String, catalog: Catalog?, onChange: (String) -> Unit) {
    val categories = catalog?.categories.orEmpty()
    SelectField(
        label = label,
        value = value,
        options = listOf("" to noneLabel) + categories.map { it.id to it.name },
        onChange = onChange,
        leading = { id ->
            val category = categories.firstOrNull { it.id == id }
            if (category != null) {
                ColorDot(parseColor(category.color), 12.dp)
                Spacer(Modifier.width(8.dp))
            }
        },
    )
}

/**
 * The sheet behind the + button. "Add another" (or Enter) adds the item and keeps the sheet open, empty
 * and focused, for the next one; "Done" adds the item (if one is entered) and closes; "Cancel" closes
 * without adding. Suggestions from the item history appear below the name - tapping one adds it at once
 * with its last category and unit. The category "Automatic" lets the server fill it in from the history.
 */
@Composable
fun AddItemSheet(
    catalog: Catalog?,
    suggestions: suspend (String) -> List<Suggestion>,
    onAdd: suspend (name: String, quantity: String?, unit: String?, categoryId: String?) -> String?,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf("") }
    var errorCode by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    var nameFocused by remember { mutableStateOf(true) }
    var voiceFailed by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val amountValid = isValidAmount(quantity)

    // Wait until the user stops typing (fewer server requests), then ask for suggestions.
    LaunchedEffect(name) {
        if (name.isBlank()) {
            shown = emptyList()
            return@LaunchedEffect
        }
        delay(150)
        shown = suggestions(name)
    }

    fun clear() {
        name = ""
        quantity = ""
        unit = ""
        categoryId = ""
        shown = emptyList()
        runCatching { nameFocus.requestFocus() }
    }

    fun add(itemName: String, itemUnit: String?, itemCategory: String?, close: Boolean) {
        if (!amountValid) return
        scope.launch {
            errorCode = onAdd(itemName, quantity.trim().ifEmpty { null }, itemUnit, itemCategory)
            if (errorCode == null) {
                if (close) onDismiss() else clear()
            }
        }
    }

    fun addCurrent(close: Boolean) {
        if (name.isNotBlank()) add(name.trim(), unit.trim().ifEmpty { null }, categoryId.ifEmpty { null }, close)
        else if (close) onDismiss()
    }

    /** A suggestion brings its last category and unit; an amount or unit chosen before is kept. */
    fun addSuggestion(suggestion: Suggestion) = add(suggestion.name, unit.trim().ifEmpty { suggestion.unit }, suggestion.categoryId, close = false)

    AppSheet(stringResource(R.string.addItem_title), onDismiss) {
        AmountUnitFields(quantity, unit, catalog?.units.orEmpty(), { quantity = it }, { unit = it })
        Column {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(
                    name,
                    {
                        name = it
                        errorCode = null
                    },
                    stringResource(R.string.item_name),
                    modifier = Modifier.weight(1f),
                    autoFocus = true,
                    focusRequester = nameFocus,
                    imeAction = ImeAction.Send,
                    onImeAction = { addCurrent(close = false) },
                    onFocusChanged = { nameFocused = it },
                )
                VoiceButton(onText = { name = it }, onFailed = { voiceFailed = it })
            }
            val visible = if (name.isNotBlank() && nameFocused) shown else emptyList()
            if (visible.isNotEmpty()) SuggestionList(visible, catalog, ::addSuggestion)
        }
        if (voiceFailed) Text(stringResource(R.string.voice_error), style = MaterialTheme.typography.bodySmall, color = colors.danger)
        CategorySelect(stringResource(R.string.item_category), stringResource(R.string.item_categoryAuto), categoryId, catalog) { categoryId = it }
        ErrorCodeText(errorCode)
        SheetActions(start = { AppButton(stringResource(R.string.common_cancel), onDismiss, variant = ButtonVariant.GHOST) }) {
            AppButton(stringResource(R.string.addItem_another), { addCurrent(close = false) }, variant = ButtonVariant.SECONDARY, enabled = amountValid && name.isNotBlank())
            AppButton(stringResource(R.string.common_done), { addCurrent(close = true) }, enabled = amountValid)
        }
    }
}

/** The suggestions below the name: about three rows high, scrolls when there are more. */
@Composable
private fun SuggestionList(suggestions: List<Suggestion>, catalog: Catalog?, onPick: (Suggestion) -> Unit) {
    val colors = AppTheme.colors
    val label = stringResource(R.string.addItem_suggestions)
    Column(
        Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .heightIn(max = 152.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.line, RoundedCornerShape(8.dp))
            .background(colors.surface)
            .verticalScroll(rememberScrollState())
            .semantics { contentDescription = label },
    ) {
        suggestions.forEachIndexed { index, suggestion ->
            val category = catalog?.categories?.firstOrNull { it.id == suggestion.categoryId }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onPick(suggestion) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ColorDot(category?.let { parseColor(it.color) } ?: Color.Transparent, 12.dp)
                Text(suggestion.name, modifier = Modifier.weight(1f))
                if (category != null) Text(category.name, fontSize = 15.sp, color = colors.muted)
            }
            if (index < suggestions.lastIndex) HorizontalDivider(color = colors.line)
        }
    }
}

/**
 * Speak an item: Android's speech recognizer (on-device where available). The recognized text goes into
 * the field; the user confirms with "Add" (speech is sometimes wrong). Hidden if the device has none.
 */
@Composable
private fun VoiceButton(onText: (String) -> Unit, onFailed: (Boolean) -> Unit) {
    val context = LocalContext.current
    if (!remember { SpeechRecognizer.isRecognitionAvailable(context) }) return
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    val colors = AppTheme.colors
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        when {
            result.resultCode == Activity.RESULT_OK && !text.isNullOrBlank() -> onText(text.trim())
            result.resultCode != Activity.RESULT_CANCELED -> onFailed(true)
        }
    }
    val label = stringResource(R.string.voice_start)
    Box(
        Modifier
            .size(50.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.line, RoundedCornerShape(8.dp))
            .clickable {
                onFailed(false)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                try {
                    launcher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    onFailed(true)
                }
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Mic, contentDescription = null, tint = colors.muted)
    }
}

/**
 * Details of one item (opened with the pencil). Only changed fields are sent, so concurrent edits of other
 * fields by others are not overwritten.
 */
@Composable
fun ItemEditorSheet(
    item: Item,
    catalog: Catalog?,
    onSave: suspend (Map<ItemField, String?>) -> String?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(item.id) { mutableStateOf(item.name) }
    var quantity by rememberSaveable(item.id) { mutableStateOf(item.quantity.orEmpty()) }
    var unit by rememberSaveable(item.id) { mutableStateOf(item.unit.orEmpty()) }
    var categoryId by rememberSaveable(item.id) { mutableStateOf(item.categoryId.orEmpty()) }
    var note by rememberSaveable(item.id) { mutableStateOf(item.note.orEmpty()) }
    var errorCode by remember { mutableStateOf<String?>(null) }
    val valid = name.isNotBlank() && isValidAmount(quantity)

    fun save() {
        if (!valid) return
        val values = mapOf(
            ItemField.NAME to name, ItemField.QUANTITY to quantity, ItemField.UNIT to unit,
            ItemField.CATEGORY to categoryId, ItemField.NOTE to note,
        )
        val old = mapOf(
            ItemField.NAME to item.name, ItemField.QUANTITY to item.quantity, ItemField.UNIT to item.unit,
            ItemField.CATEGORY to item.categoryId, ItemField.NOTE to item.note,
        )
        val changed = values.filter { (field, value) -> value.trim() != old[field].orEmpty() }.mapValues { (_, value) -> value.trim().ifEmpty { null } }
        if (changed.isEmpty()) return onDismiss()
        scope.launch {
            errorCode = onSave(changed)
            if (errorCode == null) onDismiss()
        }
    }

    AppSheet(item.name, onDismiss) {
        AmountUnitFields(quantity, unit, catalog?.units.orEmpty(), { quantity = it }, { unit = it })
        AppTextField(name, { name = it }, stringResource(R.string.item_name), maxLength = 100, imeAction = ImeAction.Next, isError = name.isBlank())
        CategorySelect(stringResource(R.string.item_category), stringResource(R.string.item_noCategory), categoryId, catalog) { categoryId = it }
        AppTextField(note, { note = it }, stringResource(R.string.item_note), maxLength = 200, onImeAction = ::save)
        ErrorCodeText(errorCode)
        SheetActions(start = {
            AppButton(stringResource(R.string.common_delete), {
                onDelete()
                onDismiss()
            }, variant = ButtonVariant.GHOST, textColor = AppTheme.colors.danger)
        }) {
            AppButton(stringResource(R.string.common_cancel), onDismiss, variant = ButtonVariant.GHOST)
            AppButton(stringResource(R.string.common_save), ::save, enabled = valid)
        }
    }
}
