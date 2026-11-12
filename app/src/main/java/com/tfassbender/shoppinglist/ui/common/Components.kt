package com.tfassbender.shoppinglist.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tfassbender.shoppinglist.R
import com.tfassbender.shoppinglist.data.remote.ApiError
import com.tfassbender.shoppinglist.ui.theme.AppTheme

/*
 * Shared building blocks so pages look like the web app (`frontend/src/components/ui.tsx` in the server
 * repo). Touch targets are at least 48dp; colors come from AppTheme.colors, which switch with dark mode.
 */

enum class ButtonVariant { PRIMARY, SECONDARY, DANGER, GHOST }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.PRIMARY,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    textColor: Color? = null,
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val (background, foreground) = when (variant) {
        ButtonVariant.PRIMARY -> colors.brand to colors.brandInk
        ButtonVariant.SECONDARY -> colors.surface to colors.fg
        ButtonVariant.DANGER -> colors.danger to Color.White
        ButtonVariant.GHOST -> Color.Transparent to colors.fg
    }
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(background)
            .then(if (variant == ButtonVariant.SECONDARY) Modifier.border(1.dp, colors.line, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = textColor ?: foreground, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = textColor ?: foreground, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

/** A text link (accent color, underlined), e.g. "No account yet? Register". */
@Composable
fun LinkText(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, muted: Boolean = false) {
    val colors = AppTheme.colors
    Text(
        text,
        color = if (muted) colors.muted else colors.accent,
        style = MaterialTheme.typography.bodySmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
    )
}

/** The look of every text input: label above, raised fill, thin line, accent line when focused. */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    maxLength: Int = 100,
    singleLine: Boolean = true,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    autoFocus: Boolean = false,
    isError: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    var focused by remember { mutableStateOf(false) }
    val requester = focusRequester ?: remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(bottom = 4.dp))
        val borderColor = when {
            isError -> colors.danger
            focused -> colors.accent
            else -> colors.line
        }
        BasicTextField(
            value = value,
            onValueChange = { if (it.length <= maxLength) onValueChange(it) },
            enabled = enabled,
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.fg),
            cursorBrush = SolidColor(colors.accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
            ),
            keyboardActions = if (onImeAction != null) KeyboardActions(onAny = { onImeAction() }) else KeyboardActions.Default,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onFocusChanged {
                    focused = it.isFocused
                    onFocusChanged?.invoke(it.isFocused)
                }
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.raised)
                        .border(if (focused || isError) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).padding(vertical = 12.dp)) { inner() }
                    trailing?.invoke()
                }
            },
        )
    }
}

/** A choice from a short list, shown like a text field with a dropdown (the web app's `<select>`). */
@Composable
fun <T> SelectField(
    label: String,
    value: T,
    options: List<Pair<T, String>>,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable (T) -> Unit)? = null,
) {
    val colors = AppTheme.colors
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == value }?.second.orEmpty()
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(bottom = 4.dp))
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.raised)
                    .border(1.dp, colors.line, RoundedCornerShape(8.dp))
                    .clickable(role = Role.DropdownList) { open = true }
                    .semantics { contentDescription = "$label: $current" }
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading?.invoke(value)
                Text(current, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = colors.muted)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surface) {
                options.forEach { (option, text) ->
                    DropdownMenuItem(
                        text = { Text(text, color = colors.fg, fontWeight = if (option == value) FontWeight.SemiBold else FontWeight.Normal) },
                        leadingIcon = leading?.let { { it(option) } },
                        onClick = {
                            open = false
                            onChange(option)
                        },
                    )
                }
            }
        }
    }
}

/** A few exclusive choices side by side (language, theme, access, validity). */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = AppTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.raised)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(2.dp),
    ) {
        options.forEach { (option, text) ->
            val selected = option == value
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) colors.surface else Color.Transparent)
                    .clickable(enabled = enabled, role = Role.RadioButton) { onChange(option) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = colors.fg, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun AppSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val colors = AppTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = colors.accent,
            checkedThumbColor = Color.White,
            checkedBorderColor = colors.accent,
            uncheckedTrackColor = colors.line,
            uncheckedThumbColor = Color.White,
            uncheckedBorderColor = colors.line,
        ),
    )
}

/**
 * One full-width row of a page (settings, members, spaces): a main text with an optional hint below, and
 * something at the end. With [onClick] the whole row is a button. [divider] = false inside a
 * [SortableColumn], which draws the lines between its rows itself.
 */
@Composable
fun ListRow(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    start: (@Composable () -> Unit)? = null,
    end: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = false,
    enabled: Boolean = true,
    divider: Boolean = true,
) {
    val colors = AppTheme.colors
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            start?.invoke()
            Column(Modifier.weight(1f)) {
                title()
                if (!hint.isNullOrEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            end?.invoke()
            if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.muted)
        }
        if (divider) HorizontalDivider(color = colors.line)
    }
}

/** [ListRow] with a plain text title. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    danger: Boolean = false,
    start: (@Composable () -> Unit)? = null,
    end: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = false,
    enabled: Boolean = true,
) = ListRow(
    title = { Text(title, color = if (danger) AppTheme.colors.danger else AppTheme.colors.fg) },
    modifier = modifier, hint = hint, start = start, end = end, onClick = onClick, chevron = chevron, enabled = enabled,
)

/** Small uppercase heading above a group of rows ("Members", "Display", a category, ...). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Row(modifier.padding(top = 24.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (color != null) {
            ColorDot(color, 12.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp, fontSize = 14.sp),
            color = AppTheme.colors.muted,
        )
    }
}

@Composable
fun ColorDot(color: Color, size: Dp = 12.dp, outlined: Boolean = false) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .then(if (outlined) Modifier.border(1.dp, AppTheme.colors.line, CircleShape) else Modifier),
    )
}

/** Muted explanation text between rows. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, color = AppTheme.colors.muted, modifier = modifier.padding(vertical = 12.dp))
}

/** The text for a failed call: the server's error code translated, otherwise a generic message. */
@Composable
fun errorText(error: ApiError): String = when (error) {
    is ApiError.Network -> stringResource(R.string.error_NETWORK_ERROR)
    is ApiError.Server -> stringResource(errorMessageRes(error.code) ?: R.string.error_unknown)
    is ApiError.InvalidResponse -> stringResource(R.string.error_unknown)
}

@Composable
fun errorCodeText(code: String?): String = stringResource(errorMessageRes(code) ?: R.string.error_unknown)

/** Shows a translated message for a failed request (or nothing). */
@Composable
fun ErrorText(error: ApiError?, modifier: Modifier = Modifier) {
    if (error != null) MessageBox(errorText(error), danger = true, modifier = modifier)
}

@Composable
fun ErrorCodeText(code: String?, modifier: Modifier = Modifier) {
    if (code != null) MessageBox(errorCodeText(code), danger = true, modifier = modifier)
}

@Composable
fun SuccessText(text: String, modifier: Modifier = Modifier) = MessageBox(text, danger = false, modifier = modifier)

@Composable
private fun MessageBox(text: String, danger: Boolean, modifier: Modifier) {
    val colors = AppTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (danger) colors.dangerFg else colors.accent,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (danger) colors.dangerBg else colors.raised)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.common_loading), color = AppTheme.colors.muted, modifier = modifier.padding(vertical = 16.dp))
}

/** A failed load with a retry button (e.g. server unreachable and nothing cached yet). */
@Composable
fun LoadError(error: ApiError, onRetry: () -> Unit) {
    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ErrorText(error)
        AppButton(stringResource(R.string.common_retry), onRetry, variant = ButtonVariant.SECONDARY)
    }
}

/** A round initial (members, spaces). */
@Composable
fun Avatar(name: String) {
    val colors = AppTheme.colors
    // codePoints: an emoji isn't cut in half
    val initial = name.trim().codePoints().findFirst().let { if (it.isPresent) String(Character.toChars(it.asInt)).uppercase() else "?" }
    Box(Modifier.size(40.dp).clip(CircleShape).background(colors.raised), contentAlignment = Alignment.Center) {
        Text(initial, fontWeight = FontWeight.Bold, color = colors.accent)
    }
}

/** The round + button at the bottom right: the one way to add something on a page (item, space, token). */
@Composable
fun Fab(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = AppTheme.colors
    FloatingActionButton(
        onClick = { if (enabled) onClick() },
        containerColor = colors.brand,
        contentColor = colors.brandInk,
        shape = CircleShape,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
        modifier = Modifier.size(64.dp).alpha(if (enabled) 1f else 0.5f).semantics { contentDescription = label },
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(28.dp))
    }
}

/** Buttons at the bottom of a sheet, right-aligned; they wrap on narrow screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SheetActions(modifier: Modifier = Modifier, start: (@Composable RowScope.() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    FlowRow(
        modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (start != null) {
            start()
            Spacer(Modifier.weight(1f))
        }
        content()
    }
}

/** The content column of a page: 16dp side padding and room at the bottom for the + button. */
@Composable
fun PageContent(padding: PaddingValues, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 112.dp)
            .widthIn(max = 720.dp),
        content = content,
    )
}

/** A chip-like round-rect with a thin outline, e.g. units in the catalog. */
@Composable
fun Chip(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(AppTheme.colors.raised).padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Thin border used around sortable lists. */
@Composable
fun Modifier.outlinedBox(): Modifier = this
    .clip(RoundedCornerShape(12.dp))
    .border(BorderStroke(1.dp, AppTheme.colors.line), RoundedCornerShape(12.dp))
