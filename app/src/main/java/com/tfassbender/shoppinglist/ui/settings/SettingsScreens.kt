package com.tfassbender.shoppinglist.ui.settings

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tfassbender.shoppinglist.AppContainer
import com.tfassbender.shoppinglist.R
import com.tfassbender.shoppinglist.data.remote.ApiError
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.remote.displayAddress
import com.tfassbender.shoppinglist.domain.ApiToken
import com.tfassbender.shoppinglist.domain.CreatedApiToken
import com.tfassbender.shoppinglist.domain.Theme
import com.tfassbender.shoppinglist.domain.TokenScope
import com.tfassbender.shoppinglist.domain.UserSettings
import com.tfassbender.shoppinglist.ui.common.ActionViewModel
import com.tfassbender.shoppinglist.ui.common.LocalAppContainer
import com.tfassbender.shoppinglist.ui.common.AppButton
import com.tfassbender.shoppinglist.ui.common.AppSheet
import com.tfassbender.shoppinglist.ui.common.AppSwitch
import com.tfassbender.shoppinglist.ui.common.AppTextField
import com.tfassbender.shoppinglist.ui.common.ButtonVariant
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
import com.tfassbender.shoppinglist.ui.common.Segmented
import com.tfassbender.shoppinglist.ui.common.SheetActions
import com.tfassbender.shoppinglist.ui.common.SuccessText
import com.tfassbender.shoppinglist.ui.common.pageViewModel
import com.tfassbender.shoppinglist.ui.common.stateIn
import com.tfassbender.shoppinglist.ui.navigation.LocalNavigator
import com.tfassbender.shoppinglist.ui.navigation.Screen
import com.tfassbender.shoppinglist.ui.space.rememberDateTimeFormatter
import com.tfassbender.shoppinglist.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.format.FormatStyle

// ---- settings ----

enum class Notice { PASSWORD_CHANGED, LOGGED_OUT_EVERYWHERE }

class SettingsViewModel(private val container: AppContainer) : ActionViewModel() {
    private val account = container.account
    val user = account.user.map { it.user }.stateIn(this, null)
    val keepScreenOn = container.uiPreferences.keepScreenOn.stateIn(this, true)
    val online = container.online.stateIn(this, true)
    /** Changes not sent yet - logging out would lose them, so the user is warned. */
    val pendingChanges = container.syncEngine.pendingCount.stateIn(this, 0)

    private val tokenCount = MutableStateFlow<Int?>(null)
    val tokens = tokenCount.asStateFlow()
    private val versionState = MutableStateFlow<String?>(null)
    val version = versionState.asStateFlow()
    private val noticeState = MutableStateFlow<Notice?>(null)
    val notice = noticeState.asStateFlow()

    init {
        viewModelScope.launch {
            account.refresh()
            versionState.value = container.serverVersion()
            (container.apiTokens.list() as? ApiResult.Success)?.let { tokenCount.value = it.value.size }
        }
    }

    fun change(settings: UserSettings) = act({ account.updateSettings(settings) })

    fun rename(name: String, onDone: () -> Unit) = act({ account.updateDisplayName(name) }) { onDone() }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { container.uiPreferences.setKeepScreenOn(enabled) }
    }

    suspend fun changePassword(current: String, new: String): ApiError? =
        when (val result = account.changePassword(current, new)) {
            is ApiResult.Success -> null.also { noticeState.value = Notice.PASSWORD_CHANGED }
            is ApiResult.Failure -> result.error
        }

    fun logoutEverywhere() = act({ account.logoutEverywhere() }) { noticeState.value = Notice.LOGGED_OUT_EVERYWHERE }

    fun logout() {
        viewModelScope.launch { container.auth.logout() }
    }

    fun deleteAccount() = act({ account.deleteAccount() })
}

private enum class SettingsSheet { NAME, PASSWORD, LOGOUT_EVERYWHERE, LOGOUT_PENDING, DELETE, SERVER }

/**
 * Settings in plain sections: profile, display, AI agents, account. Display choices apply at once;
 * everything else opens a sheet. Needs a connection (settings are stored on the server), except
 * "keep the screen on", which belongs to this device.
 */
@Composable
fun SettingsScreen() {
    val vm = pageViewModel { SettingsViewModel(it) }
    val navigator = LocalNavigator.current
    val user by vm.user.collectAsStateWithLifecycle()
    val keepScreenOn by vm.keepScreenOn.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val version by vm.version.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val pendingChanges by vm.pendingChanges.collectAsStateWithLifecycle()
    val serverUrl by LocalAppContainer.current.server.url.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<SettingsSheet?>(null) }
    val colors = AppTheme.colors

    Page(stringResource(R.string.settings_title)) { padding ->
        PageContent(padding) {
            val current = user
            if (current == null) {
                Loading()
                return@PageContent
            }
            val settings = current.settings
            OnlineOnly(online) { enabled ->
                ErrorText(error.takeIf { sheet != SettingsSheet.NAME }, Modifier.padding(top = 8.dp))
                when (notice) {
                    Notice.PASSWORD_CHANGED -> SuccessText(stringResource(R.string.settings_passwordChanged), Modifier.padding(top = 8.dp))
                    Notice.LOGGED_OUT_EVERYWHERE -> SuccessText(stringResource(R.string.settings_loggedOutEverywhere), Modifier.padding(top = 8.dp))
                    null -> Unit
                }

                SectionTitle(stringResource(R.string.settings_profile))
                ListRow(
                    stringResource(R.string.auth_displayName),
                    hint = stringResource(R.string.auth_displayNameHint),
                    end = { ValueText(current.displayName) },
                    onClick = { sheet = SettingsSheet.NAME },
                    enabled = enabled,
                    chevron = true,
                )
                ListRow(stringResource(R.string.auth_email), end = { ValueText(current.email) })

                SectionTitle(stringResource(R.string.settings_appearance))
                SettingBlock(stringResource(R.string.settings_language)) {
                    Segmented(
                        listOf("de" to stringResource(R.string.settings_language_de), "en" to stringResource(R.string.settings_language_en)),
                        settings.language,
                        { vm.change(settings.copy(language = it)) },
                        enabled = enabled,
                    )
                }
                SettingBlock(stringResource(R.string.settings_theme)) {
                    Segmented(
                        listOf(
                            Theme.SYSTEM to stringResource(R.string.settings_theme_SYSTEM),
                            Theme.LIGHT to stringResource(R.string.settings_theme_LIGHT),
                            Theme.DARK to stringResource(R.string.settings_theme_DARK),
                        ),
                        settings.theme,
                        { vm.change(settings.copy(theme = it)) },
                        enabled = enabled,
                    )
                }
                // Stored as "ignore merge warnings" - the switch shows the positive form.
                SwitchRow(stringResource(R.string.settings_mergeNotes), stringResource(R.string.settings_mergeNotesHint), !settings.ignoreMergeWarnings, enabled) {
                    vm.change(settings.copy(ignoreMergeWarnings = !it))
                }
                SwitchRow(stringResource(R.string.settings_keepScreenOn), stringResource(R.string.settings_keepScreenOnHint), keepScreenOn, true, vm::setKeepScreenOn)

                SectionTitle(stringResource(R.string.settings_aiAgents))
                ListRow(
                    stringResource(R.string.tokens_title),
                    hint = stringResource(R.string.settings_tokensHint),
                    end = { Text(tokens?.toString().orEmpty(), color = colors.muted) },
                    onClick = { navigator.push(Screen.ApiTokens) },
                    chevron = true,
                )

                SectionTitle(stringResource(R.string.settings_account))
                ListRow(stringResource(R.string.settings_password), onClick = { sheet = SettingsSheet.PASSWORD }, enabled = enabled, chevron = true)
                ListRow(stringResource(R.string.settings_logoutEverywhere), onClick = { sheet = SettingsSheet.LOGOUT_EVERYWHERE }, enabled = enabled)
                // Logging out works offline too (it only forgets this device's data).
                ListRow(stringResource(R.string.nav_logout), onClick = { if (pendingChanges > 0) sheet = SettingsSheet.LOGOUT_PENDING else vm.logout() })
                ListRow(stringResource(R.string.settings_deleteAccount), danger = true, onClick = { sheet = SettingsSheet.DELETE }, enabled = enabled)

                // Works offline too: a moved or wrong server is exactly when it's needed.
                SectionTitle(stringResource(R.string.server_title))
                ListRow(
                    stringResource(R.string.server_address),
                    hint = serverUrl.displayAddress(),
                    onClick = { sheet = SettingsSheet.SERVER },
                    chevron = true,
                )
                Text(
                    stringResource(R.string.app_name) + (version?.let { " " + stringResource(R.string.settings_version, it) } ?: ""),
                    fontSize = 15.sp,
                    color = colors.muted,
                    modifier = Modifier.padding(start = 8.dp, top = 24.dp),
                )
            }
        }
    }

    val current = user ?: return
    when (sheet) {
        SettingsSheet.NAME -> NameSheet(
            title = stringResource(R.string.auth_displayName),
            label = stringResource(R.string.auth_displayName),
            initialValue = current.displayName,
            maxLength = 40,
            submitLabel = stringResource(R.string.common_save),
            pending = pending,
            error = error,
            onSubmit = { name -> vm.rename(name) { sheet = null } },
            onDismiss = {
                sheet = null
                vm.clearError()
            },
        )
        SettingsSheet.PASSWORD -> PasswordSheet(vm, onDismiss = { sheet = null })
        SettingsSheet.LOGOUT_EVERYWHERE -> ConfirmSheet(
            title = stringResource(R.string.settings_logoutEverywhereTitle),
            text = stringResource(R.string.settings_logoutEverywhereConfirm),
            confirmLabel = stringResource(R.string.settings_logoutEverywhereButton),
            onConfirm = vm::logoutEverywhere,
            onDismiss = { sheet = null },
        )
        SettingsSheet.LOGOUT_PENDING -> ConfirmSheet(
            title = stringResource(R.string.settings_logoutPendingTitle),
            text = stringResource(R.string.settings_logoutPendingText, pendingChanges.toString()),
            confirmLabel = stringResource(R.string.nav_logout),
            onConfirm = vm::logout,
            onDismiss = { sheet = null },
        )
        SettingsSheet.DELETE -> ConfirmSheet(
            title = stringResource(R.string.settings_deleteAccountTitle),
            text = stringResource(R.string.settings_deleteAccountConfirm),
            confirmLabel = stringResource(R.string.settings_deleteAccount),
            onConfirm = vm::deleteAccount,
            onDismiss = { sheet = null },
        )
        SettingsSheet.SERVER -> ServerSheet(loggedIn = true, pendingChanges = pendingChanges, onDismiss = { sheet = null })
        null -> Unit
    }
}

@Composable
private fun ValueText(text: String) {
    Text(text, color = AppTheme.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 180.dp))
}

@Composable
private fun SettingBlock(title: String, content: @Composable () -> Unit) {
    Column {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title)
            content()
        }
        HorizontalDivider(color = AppTheme.colors.line)
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
            }
            AppSwitch(checked, onChange, enabled)
        }
        HorizontalDivider(color = AppTheme.colors.line)
    }
}

/** Change password; the answer is a new session (all other devices are logged out). */
@Composable
private fun PasswordSheet(vm: SettingsViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<ApiError?>(null) }
    var pending by remember { mutableStateOf(false) }
    val submit = {
        if (current.isNotEmpty() && next.isNotEmpty() && !pending) {
            pending = true
            scope.launch {
                error = vm.changePassword(current, next)
                pending = false
                if (error == null) onDismiss()
            }
        }
    }
    AppSheet(stringResource(R.string.settings_password), onDismiss) {
        AppTextField(current, { current = it }, stringResource(R.string.auth_currentPassword), password = true, autoFocus = true, maxLength = 200, imeAction = androidx.compose.ui.text.input.ImeAction.Next)
        AppTextField(next, { next = it }, stringResource(R.string.auth_newPassword), password = true, maxLength = 200, onImeAction = { submit() })
        Text(stringResource(R.string.settings_passwordHint), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
        ErrorText(error)
        SheetActions {
            AppButton(stringResource(R.string.common_cancel), onDismiss, variant = ButtonVariant.GHOST)
            AppButton(stringResource(R.string.settings_passwordChange), { submit() }, enabled = !pending && current.isNotEmpty() && next.isNotEmpty())
        }
    }
}

// ---- API tokens ----

class ApiTokensViewModel(private val container: AppContainer) : ActionViewModel() {
    private val tokensState = MutableStateFlow<List<ApiToken>?>(null)
    val tokens = tokensState.asStateFlow()
    val online = container.online.stateIn(this, true)

    init {
        load()
    }

    fun load() = act({ container.apiTokens.list() }) { tokensState.value = it }

    fun revoke(token: ApiToken) = act({ container.apiTokens.revoke(token.id) }) { tokensState.value = tokensState.value.orEmpty() - token }

    suspend fun create(name: String, scope: TokenScope, days: Long?): ApiResult<CreatedApiToken> =
        container.apiTokens.create(name, scope, days).also { result ->
            if (result is ApiResult.Success) tokensState.value = tokensState.value.orEmpty() + result.value.token
        }
}

/** Personal API tokens for AI agents: list with "Revoke"; + creates one, shown once with "Copy". */
@Composable
fun ApiTokensScreen() {
    val vm = pageViewModel { ApiTokensViewModel(it) }
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<ApiToken?>(null) }
    val format = rememberDateTimeFormatter(timeStyle = null)

    Page(
        stringResource(R.string.tokens_title),
        fab = { Fab(stringResource(R.string.tokens_create), { creating = true }, enabled = online) },
    ) { padding ->
        PageContent(padding) {
            OnlineOnly(online) { enabled ->
                Hint(stringResource(R.string.tokens_explanation))
                ErrorText(error)
                val all = tokens
                when {
                    all == null -> if (error == null) Loading()
                    all.isEmpty() -> Text(stringResource(R.string.tokens_empty), color = AppTheme.colors.muted, modifier = Modifier.padding(vertical = 24.dp))
                    else -> all.forEach { token ->
                        val scope = stringResource(if (token.scope == TokenScope.READ) R.string.tokens_scope_READ else R.string.tokens_scope_FULL)
                        val expiry = token.expiresAt?.let { stringResource(R.string.tokens_expires, format(it)) } ?: stringResource(R.string.tokens_neverExpires)
                        val lastUse = token.lastUsedAt?.let { stringResource(R.string.tokens_lastUsed, format(it)) } ?: stringResource(R.string.tokens_neverUsed)
                        ListRow(
                            token.name,
                            hint = "$scope · $expiry · $lastUse",
                            end = { AppButton(stringResource(R.string.tokens_revoke), { revoking = token }, variant = ButtonVariant.SECONDARY, enabled = enabled) },
                        )
                    }
                }
            }
        }
    }
    if (creating) NewTokenSheet(vm, onDismiss = { creating = false })
    revoking?.let { token ->
        ConfirmSheet(
            title = stringResource(R.string.tokens_revokeTitle, token.name),
            text = stringResource(R.string.tokens_revokeConfirm),
            confirmLabel = stringResource(R.string.tokens_revoke),
            onConfirm = { vm.revoke(token) },
            onDismiss = { revoking = null },
        )
    }
}

/** Name, access and validity; afterwards the raw token, which the server never shows again. */
@Composable
private fun NewTokenSheet(vm: ApiTokensViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var name by rememberSaveable { mutableStateOf("") }
    var access by rememberSaveable { mutableStateOf(TokenScope.READ) }
    var validity by rememberSaveable { mutableStateOf<Long?>(30L) }
    var created by remember { mutableStateOf<CreatedApiToken?>(null) }
    var error by remember { mutableStateOf<ApiError?>(null) }
    var pending by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    val token = created
    if (token != null) {
        AppSheet(stringResource(R.string.tokens_copyTitle), onDismiss) {
            SelectionContainer {
                Text(
                    token.rawToken,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().background(AppTheme.colors.raised, RoundedCornerShape(8.dp)).padding(12.dp),
                )
            }
            Text(stringResource(R.string.tokens_onceHint), color = AppTheme.colors.muted)
            SheetActions {
                AppButton(stringResource(R.string.common_done), onDismiss, variant = ButtonVariant.GHOST)
                AppButton(stringResource(if (copied) R.string.invites_copied else R.string.tokens_copy), {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("token", token.rawToken)))
                        copied = true
                    }
                })
            }
        }
        return
    }
    val submit = {
        if (name.isNotBlank() && !pending) {
            pending = true
            scope.launch {
                when (val result = vm.create(name.trim(), access, validity)) {
                    is ApiResult.Success -> created = result.value
                    is ApiResult.Failure -> error = result.error
                }
                pending = false
            }
        }
    }
    AppSheet(stringResource(R.string.tokens_create), onDismiss) {
        AppTextField(name, { name = it }, stringResource(R.string.tokens_name), maxLength = 60, autoFocus = true, onImeAction = { submit() })
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tokens_scope), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
            Segmented(listOf(TokenScope.READ to stringResource(R.string.tokens_scope_READ), TokenScope.FULL to stringResource(R.string.tokens_scope_FULL)), access, { access = it })
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tokens_validFor), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
            Segmented(
                listOf(30L to stringResource(R.string.tokens_days, "30"), 365L to stringResource(R.string.tokens_oneYear), null to stringResource(R.string.tokens_never)),
                validity,
                { validity = it },
            )
        }
        ErrorText(error)
        SheetActions {
            AppButton(stringResource(R.string.common_cancel), onDismiss, variant = ButtonVariant.GHOST)
            AppButton(stringResource(R.string.common_create), { submit() }, enabled = name.isNotBlank() && !pending)
        }
    }
}
