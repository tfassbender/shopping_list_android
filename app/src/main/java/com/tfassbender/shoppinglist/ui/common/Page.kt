package com.tfassbender.shoppinglist.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tfassbender.shoppinglist.R
import com.tfassbender.shoppinglist.ui.navigation.LocalNavigator
import com.tfassbender.shoppinglist.ui.navigation.Screen
import com.tfassbender.shoppinglist.ui.theme.AppTheme

/** An entry of the ⋮ menu; [divider] draws a line above it. */
data class MenuEntry(
    val label: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val divider: Boolean = false,
    val onSelect: () -> Unit,
)

private val AUTH_SCREENS = setOf(Screen.Login, Screen.Register, Screen.ForgotPassword)

/**
 * A page: the light blue bar at the top (back arrow, title, sync badges, actions, ⋮ menu) and the content
 * below. [sub] is a second line below the title (e.g. the shop of a list); the badges move there then, so
 * the title keeps its room. A tap on the title leads to the start page (except on the start page itself).
 */
@Composable
fun Page(
    title: String,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
    sub: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    menu: List<MenuEntry> = emptyList(),
    fab: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val navigator = LocalNavigator.current
    val screen = navigator.current.screen
    val loggedIn = screen !in AUTH_SCREENS
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = AppTheme.colors.surface,
        contentColor = AppTheme.colors.fg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppBar(
                title = title,
                onBack = if (showBack && navigator.canGoBack) navigator::back else null,
                onTitle = if (loggedIn && screen != Screen.Home) navigator::toStart else null,
                sub = sub,
                badges = loggedIn,
                languageToggle = !loggedIn,
                actions = actions,
                menu = menu,
            )
        },
        floatingActionButton = { if (fab != null) Box(Modifier.navigationBarsPadding()) { fab() } },
    ) { padding ->
        Box(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
            content(padding)
        }
    }
}

@Composable
private fun AppBar(
    title: String,
    onBack: (() -> Unit)?,
    onTitle: (() -> Unit)?,
    sub: (@Composable () -> Unit)?,
    badges: Boolean,
    languageToggle: Boolean,
    actions: @Composable RowScope.() -> Unit,
    menu: List<MenuEntry>,
) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.brand).statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (sub != null) 56.dp else 64.dp)
                .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = colors.brandInk)
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .then(if (onTitle != null) Modifier.clickable(onClickLabel = stringResource(R.string.nav_toStart), onClick = onTitle) else Modifier),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    title,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.brandInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (badges && sub == null) SyncIndicator(compact = false)
            if (languageToggle) LanguageToggle()
            actions()
            if (menu.isNotEmpty()) OverflowMenu(menu)
        }
        if (sub != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .padding(start = if (onBack != null) 56.dp else 16.dp, end = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(Modifier.weight(1f)) { sub() }
                if (badges) SyncIndicator(compact = true)
            }
        }
    }
}

/** An icon button in the app bar (brand ink on light blue). */
@Composable
fun BarIconButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, dimmed: Boolean = false) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).alpha(if (enabled) 1f else 0.4f)) {
        Icon(icon, contentDescription = label, tint = AppTheme.colors.brandInk, modifier = Modifier.size(24.dp).alpha(if (dimmed) 0.45f else 1f))
    }
}

@Composable
private fun OverflowMenu(entries: List<MenuEntry>) {
    val colors = AppTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        BarIconButton(Icons.Filled.MoreVert, stringResource(R.string.nav_menu), { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surface, modifier = Modifier.width(280.dp)) {
            entries.forEach { entry ->
                if (entry.divider) HorizontalDivider(color = colors.line, modifier = Modifier.padding(vertical = 6.dp))
                DropdownMenuItem(
                    enabled = entry.enabled,
                    text = { Text(entry.label, fontSize = 19.sp, color = if (entry.danger) colors.danger else colors.fg) },
                    leadingIcon = entry.icon?.let { icon ->
                        { Icon(icon, contentDescription = null, tint = if (entry.danger) colors.danger else colors.muted, modifier = Modifier.size(20.dp)) }
                    },
                    onClick = {
                        open = false
                        entry.onSelect()
                    },
                    modifier = Modifier.heightIn(min = 52.dp),
                )
            }
        }
    }
}

/**
 * A modal sheet sliding up from the bottom (thumb-friendly, as the web app's dialogs on phones). With
 * text fields the keyboard pushes it up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = AppTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
        contentColor = colors.fg,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/** Confirmation for destructive actions (delete, remove, leave). */
@Composable
fun ConfirmSheet(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppSheet(title, onDismiss) {
        Text(text, color = AppTheme.colors.muted)
        SheetActions {
            AppButton(stringResource(R.string.common_cancel), onDismiss, variant = ButtonVariant.GHOST)
            AppButton(confirmLabel, {
                onConfirm()
                onDismiss()
            }, variant = ButtonVariant.DANGER)
        }
    }
}

/** A text field sheet for creating or renaming something (space, list, shop, display name). */
@Composable
fun NameSheet(
    title: String,
    submitLabel: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    label: String = stringResource(R.string.common_name),
    initialValue: String = "",
    maxLength: Int = 60,
    pending: Boolean = false,
    error: com.tfassbender.shoppinglist.data.remote.ApiError? = null,
    errorCode: String? = null,
) {
    var value by remember { mutableStateOf(initialValue) }
    val submit = { if (value.isNotBlank() && !pending) onSubmit(value.trim()) }
    AppSheet(title, onDismiss) {
        AppTextField(value, { value = it }, label, maxLength = maxLength, autoFocus = true, onImeAction = submit)
        ErrorText(error)
        ErrorCodeText(errorCode)
        SheetActions {
            AppButton(stringResource(R.string.common_cancel), onDismiss, variant = ButtonVariant.GHOST)
            AppButton(submitLabel, submit, enabled = value.isNotBlank() && !pending)
        }
    }
}

/** A choice row inside a sheet (shop selection, member options): text, hint, optional check mark. */
@Composable
fun SheetOption(title: String, hint: String?, onClick: () -> Unit, selected: Boolean = false, danger: Boolean = false, divider: Boolean = true) {
    val colors = AppTheme.colors
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 19.sp, color = if (danger) colors.danger else colors.fg)
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            if (selected) {
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accent)
            }
        }
        if (divider) HorizontalDivider(color = colors.line)
    }
}

/**
 * Wraps content that needs the server (space management, categories, shops, settings). Offline, a short
 * hint explains why the controls are disabled; the content gets [online] = false to disable them.
 */
@Composable
fun OnlineOnly(online: Boolean, content: @Composable (enabled: Boolean) -> Unit) {
    if (!online) {
        Text(
            stringResource(R.string.offline_needsConnection),
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.muted,
            modifier = Modifier
                .padding(top = 16.dp, bottom = 4.dp)
                .fillMaxWidth()
                .background(AppTheme.colors.raised, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
    Box(Modifier.alpha(if (online) 1f else 0.6f)) {
        Column { content(online) }
    }
}
