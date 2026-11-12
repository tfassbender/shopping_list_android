package com.tfassbender.shoppinglist.ui.space

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tfassbender.shoppinglist.AppContainer
import com.tfassbender.shoppinglist.R
import com.tfassbender.shoppinglist.data.remote.ApiError
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.remote.status
import com.tfassbender.shoppinglist.domain.CreatedInvite
import com.tfassbender.shoppinglist.domain.Invite
import com.tfassbender.shoppinglist.domain.InvitePreview
import com.tfassbender.shoppinglist.domain.Member
import com.tfassbender.shoppinglist.domain.Role
import com.tfassbender.shoppinglist.domain.Space
import com.tfassbender.shoppinglist.domain.parseInviteToken
import com.tfassbender.shoppinglist.ui.common.ActionViewModel
import com.tfassbender.shoppinglist.ui.common.AppButton
import com.tfassbender.shoppinglist.ui.common.AppSheet
import com.tfassbender.shoppinglist.ui.common.AppTextField
import com.tfassbender.shoppinglist.ui.common.Avatar
import com.tfassbender.shoppinglist.ui.common.ButtonVariant
import com.tfassbender.shoppinglist.ui.common.ConfirmSheet
import com.tfassbender.shoppinglist.ui.common.ErrorText
import com.tfassbender.shoppinglist.ui.common.Fab
import com.tfassbender.shoppinglist.ui.common.Hint
import com.tfassbender.shoppinglist.ui.common.LinkText
import com.tfassbender.shoppinglist.ui.common.ListRow
import com.tfassbender.shoppinglist.ui.common.LoadError
import com.tfassbender.shoppinglist.ui.common.Loading
import com.tfassbender.shoppinglist.ui.common.MenuEntry
import com.tfassbender.shoppinglist.ui.common.NameSheet
import com.tfassbender.shoppinglist.ui.common.OnlineOnly
import com.tfassbender.shoppinglist.ui.common.Page
import com.tfassbender.shoppinglist.ui.common.PageContent
import com.tfassbender.shoppinglist.ui.common.SectionTitle
import com.tfassbender.shoppinglist.ui.common.Segmented
import com.tfassbender.shoppinglist.ui.common.SheetActions
import com.tfassbender.shoppinglist.ui.common.SheetOption
import com.tfassbender.shoppinglist.ui.common.pageViewModel
import com.tfassbender.shoppinglist.ui.common.stateIn
import com.tfassbender.shoppinglist.ui.home.listsOfSpaces
import com.tfassbender.shoppinglist.ui.navigation.LocalNavigator
import com.tfassbender.shoppinglist.ui.navigation.Screen
import com.tfassbender.shoppinglist.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// ---- "Spaces & sharing" ----

class SpacesViewModel(private val container: AppContainer) : ActionViewModel() {
    val spaces = container.spaces.spaces.stateIn(this, emptyList())
    val lists = container.listsOfSpaces(container.spaces.spaces).stateIn(this, emptyMap())
    val online = container.online.stateIn(this, true)

    init {
        viewModelScope.launch { container.spaces.refresh() }
    }

    fun create(name: String, onCreated: (Space) -> Unit) = act({ container.spaces.create(name) }) { space ->
        onCreated(space)
        viewModelScope.launch { container.lists.open(space.id) }
    }
}

/** One row per space; + creates a space; ⋮ → join a space with an invite link (the app has no app links yet). */
@Composable
fun SpacesScreen() {
    val vm = pageViewModel { SpacesViewModel(it) }
    val navigator = LocalNavigator.current
    val spaces by vm.spaces.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var joining by rememberSaveable { mutableStateOf(false) }

    Page(
        title = stringResource(R.string.spaces_title),
        menu = listOf(MenuEntry(stringResource(R.string.spaces_join), Icons.Filled.Link, enabled = online) { joining = true }),
        fab = { Fab(stringResource(R.string.spaces_new), { creating = true }, enabled = online) },
    ) { padding ->
        PageContent(padding) {
            Hint(stringResource(R.string.spaces_explanation))
            spaces.forEach { space ->
                val listCount = lists[space.id]?.size ?: 0
                val listsText = if (listCount == 1) stringResource(R.string.spaces_lists_one) else stringResource(R.string.spaces_lists_other, listCount.toString())
                val membersText = if (space.memberCount == 1) stringResource(R.string.spaces_members_one) else stringResource(R.string.spaces_members_other, space.memberCount.toString())
                val role = stringResource(if (space.isOwner) R.string.spaces_role_OWNER else R.string.spaces_role_MEMBER)
                ListRow(
                    title = { Text(space.name, fontSize = 23.sp, fontWeight = FontWeight.SemiBold) },
                    hint = stringResource(R.string.spaces_summary, listsText, membersText) + " · " + role,
                    start = { Avatar(space.name) },
                    onClick = { navigator.push(Screen.SpacePage(space.id)) },
                    chevron = true,
                )
            }
        }
    }
    if (creating) {
        NameSheet(
            title = stringResource(R.string.spaces_new),
            submitLabel = stringResource(R.string.common_create),
            error = error,
            onSubmit = { name ->
                vm.create(name) { space ->
                    creating = false
                    navigator.push(Screen.SpacePage(space.id))
                }
            },
            onDismiss = {
                creating = false
                vm.clearError()
            },
        )
    }
    if (joining) JoinSheet(onDismiss = { joining = false }, onToken = { token ->
        joining = false
        navigator.push(Screen.Invite(token))
    })
}

/** Paste an invite link (or the token alone) - leads to the invite page. */
@Composable
private fun JoinSheet(onDismiss: () -> Unit, onToken: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val submit = {
        val token = parseInviteToken(text)
        if (token == null) invalid = true else onToken(token)
    }
    AppSheet(stringResource(R.string.spaces_join), onDismiss) {
        Text(stringResource(R.string.spaces_joinHint), color = AppTheme.colors.muted)
        AppTextField(text, {
            text = it
            invalid = false
        }, stringResource(R.string.invites_link), maxLength = 500, autoFocus = true, isError = invalid, onImeAction = submit)
        if (invalid) Text(stringResource(R.string.spaces_joinInvalid), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.danger)
        SheetActions {
            AppButton(stringResource(R.string.common_cancel), onDismiss, variant = ButtonVariant.GHOST)
            AppButton(stringResource(R.string.spaces_joinNext), submit, enabled = text.isNotBlank())
        }
    }
}

// ---- one space ----

class SpaceViewModel(private val container: AppContainer, val spaceId: String) : ActionViewModel() {
    val space = container.spaces.spaces.map { all -> all.firstOrNull { it.id == spaceId } }.stateIn(this, null)
    val me = container.account.user.map { it.user }.stateIn(this, null)
    val catalog = container.catalog.catalog(spaceId).stateIn(this, null)
    val layouts = container.layouts.layouts(spaceId).stateIn(this, emptyList())
    val history = container.catalog.history(spaceId).stateIn(this, emptyList())
    val online = container.online.stateIn(this, true)

    private val membersState = MutableStateFlow<List<Member>?>(null)
    val members = membersState.asStateFlow()
    private val invitesState = MutableStateFlow<List<Invite>>(emptyList())
    val invites = invitesState.asStateFlow()
    private val loadState = MutableStateFlow<ApiError?>(null)
    val loadError = loadState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            loadState.value = null
            when (val result = container.spaces.members(spaceId)) {
                is ApiResult.Success -> membersState.value = result.value
                is ApiResult.Failure -> loadState.value = result.error
            }
            if (space.value?.isOwner == true || container.spaces.cached().firstOrNull { it.id == spaceId }?.isOwner == true) loadInvites()
        }
    }

    private suspend fun loadInvites() {
        (container.spaces.invites(spaceId) as? ApiResult.Success)?.let { invitesState.value = it.value }
    }

    fun rename(name: String, onDone: () -> Unit) = act({ container.spaces.rename(spaceId, name) }) { onDone() }

    fun delete(onDone: () -> Unit) = act({ container.spaces.delete(spaceId) }) { onDone() }

    fun leave(onDone: () -> Unit) = act({ container.spaces.leave(spaceId) }) { onDone() }

    fun removeMember(member: Member) = act({ container.spaces.removeMember(spaceId, member.userId) }) { load() }

    fun makeOwner(member: Member) = act({ container.spaces.transferOwnership(spaceId, member.userId) }) {
        invitesState.value = emptyList()
        load()
    }

    fun revokeInvite(invite: Invite) = act({ container.spaces.revokeInvite(spaceId, invite.id) }) {
        invitesState.value = invitesState.value - invite
    }

    suspend fun createInvite(hours: Long): ApiResult<CreatedInvite> =
        container.spaces.createInvite(spaceId, hours).also { if (it is ApiResult.Success) loadInvites() }
}

private data class Confirm(val title: String, val text: String, val confirmLabel: String, val onConfirm: () -> Unit)

/**
 * A space: its members, open invite links (owner) and what all its lists share (categories, items used
 * before, shops). Rename and delete (owner) or leave (member) are in the ⋮ menu. Needs a connection.
 */
@Composable
fun SpaceScreen(spaceId: String) {
    val vm = pageViewModel { SpaceViewModel(it, spaceId) }
    val navigator = LocalNavigator.current
    val space by vm.space.collectAsStateWithLifecycle()
    val me by vm.me.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val invites by vm.invites.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val layouts by vm.layouts.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    var renaming by rememberSaveable { mutableStateOf(false) }
    var inviting by rememberSaveable { mutableStateOf(false) }
    var memberOptions by remember { mutableStateOf<Member?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val gone = { navigator.replace(Screen.Spaces) } // back must not lead to the deleted space

    val current = space
    // A 404 means the space is gone (or the user was removed): don't show cached data.
    if (current == null || (loadError as? ApiError.Server)?.status == 404) {
        Page(stringResource(R.string.spaces_title)) { padding ->
            PageContent(padding) {
                if (loadError != null) LoadError(loadError!!, vm::load) else Loading()
            }
        }
        return
    }
    val isOwner = current.isOwner
    val menu = if (isOwner) {
        val deleteTitle = stringResource(R.string.space_deleteTitle, current.name)
        val deleteText = stringResource(R.string.space_deleteConfirm, current.name)
        val deleteLabel = stringResource(R.string.space_delete)
        listOf(
            MenuEntry(stringResource(R.string.space_rename), Icons.Filled.EditNote, enabled = online) { renaming = true },
            MenuEntry(deleteLabel, Icons.Filled.Delete, danger = true, divider = true, enabled = online) {
                confirm = Confirm(deleteTitle, deleteText, deleteLabel) { vm.delete(gone) }
            },
        )
    } else {
        val title = stringResource(R.string.members_leaveTitle, current.name)
        val text = stringResource(R.string.members_leaveConfirm)
        val label = stringResource(R.string.members_leave)
        listOf(MenuEntry(label, Icons.AutoMirrored.Filled.Logout, danger = true, enabled = online) { confirm = Confirm(title, text, label) { vm.leave(gone) } })
    }

    Page(current.name, menu = menu) { padding ->
        PageContent(padding) {
            OnlineOnly(online) { enabled ->
                ErrorText(error ?: loadError, Modifier.padding(top = 8.dp))
                SectionTitle(stringResource(R.string.space_members))
                members?.forEach { member ->
                    val isMe = member.userId == me?.id
                    ListRow(
                        title = {
                            Text(buildAnnotatedString {
                                append(member.displayName)
                                if (isMe) withStyle(SpanStyle(color = AppTheme.colors.muted)) { append(" (" + stringResource(R.string.members_you) + ")") }
                            })
                        },
                        hint = stringResource(if (member.role == Role.OWNER) R.string.spaces_role_OWNER else R.string.spaces_role_MEMBER),
                        start = { Avatar(member.displayName) },
                        end = if (isOwner && !isMe) {
                            {
                                IconButton(onClick = { memberOptions = member }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.members_options, member.displayName), tint = AppTheme.colors.muted)
                                }
                            }
                        } else null,
                    )
                } ?: Loading()
                if (isOwner) {
                    AppButton(stringResource(R.string.invites_title), { inviting = true }, Modifier.padding(vertical = 16.dp), enabled = enabled)
                    if (invites.isNotEmpty()) OpenInvites(invites, enabled = enabled && !pending, onRevoke = vm::revokeInvite)
                } else {
                    Hint(stringResource(R.string.space_onlyOwnerInvites))
                }
                SectionTitle(stringResource(R.string.space_usedByLists))
                ListRow(
                    stringResource(R.string.space_catalog),
                    hint = stringResource(R.string.space_categoryCount, (catalog?.categories?.size ?: 0).toString()),
                    onClick = { navigator.push(Screen.Catalog(spaceId)) },
                    chevron = true,
                )
                ListRow(
                    stringResource(R.string.history_title),
                    hint = stringResource(R.string.history_count, history.size.toString()),
                    onClick = { navigator.push(Screen.History(spaceId)) },
                    chevron = true,
                )
                ListRow(
                    stringResource(R.string.space_layouts),
                    hint = if (layouts.isNotEmpty()) layouts.joinToString(", ") { it.name } else stringResource(R.string.layouts_empty),
                    onClick = { navigator.push(Screen.Layouts(spaceId)) },
                    chevron = true,
                )
            }
        }
    }

    if (renaming) {
        NameSheet(
            title = stringResource(R.string.space_rename),
            submitLabel = stringResource(R.string.common_save),
            initialValue = current.name,
            pending = pending,
            error = error,
            onSubmit = { name -> vm.rename(name) { renaming = false } },
            onDismiss = { renaming = false },
        )
    }
    if (inviting) InviteSheet(vm, onDismiss = { inviting = false })
    memberOptions?.let { member ->
        val transferTitle = stringResource(R.string.members_transferTitle, member.displayName)
        val transferText = stringResource(R.string.members_transferConfirm, member.displayName)
        val makeOwner = stringResource(R.string.members_makeOwner)
        val removeTitle = stringResource(R.string.members_removeTitle, member.displayName)
        val removeText = stringResource(R.string.members_removeConfirm, member.displayName, current.name)
        val remove = stringResource(R.string.members_remove)
        AppSheet(member.displayName, { memberOptions = null }) {
            Column {
                SheetOption(makeOwner, stringResource(R.string.members_makeOwnerHint), {
                    memberOptions = null
                    confirm = Confirm(transferTitle, transferText, makeOwner) { vm.makeOwner(member) }
                })
                SheetOption(remove, removeText, {
                    memberOptions = null
                    confirm = Confirm(removeTitle, removeText, remove) { vm.removeMember(member) }
                }, danger = true, divider = false)
            }
            SheetActions { AppButton(stringResource(R.string.common_cancel), { memberOptions = null }, variant = ButtonVariant.GHOST) }
        }
    }
    confirm?.let { c -> ConfirmSheet(c.title, c.text, c.confirmLabel, c.onConfirm, onDismiss = { confirm = null }) }
}

@Composable
fun rememberDateTimeFormatter(dateStyle: FormatStyle = FormatStyle.MEDIUM, timeStyle: FormatStyle? = FormatStyle.SHORT): (Instant) -> String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale, dateStyle, timeStyle) {
        (if (timeStyle != null) DateTimeFormatter.ofLocalizedDateTime(dateStyle, timeStyle) else DateTimeFormatter.ofLocalizedDate(dateStyle)).withLocale(locale)
    }
    return { instant -> formatter.format(instant.atZone(ZoneId.systemDefault())) }
}

/** Open (unused, unexpired) invite links with "Revoke". */
@Composable
private fun OpenInvites(invites: List<Invite>, enabled: Boolean, onRevoke: (Invite) -> Unit) {
    val format = rememberDateTimeFormatter()
    SectionTitle(stringResource(R.string.invites_open))
    invites.forEach { invite ->
        ListRow(
            stringResource(R.string.invites_forOnePerson),
            hint = stringResource(R.string.invites_validUntil, format(invite.expiresAt)),
            end = { AppButton(stringResource(R.string.invites_revoke), { onRevoke(invite) }, variant = ButtonVariant.SECONDARY, enabled = enabled) },
        )
    }
}

/**
 * Invite someone: first how long the link is valid, then the link - shown once (the server stores only a
 * hash) with Copy and Share (the system's share menu).
 */
@Composable
private fun InviteSheet(vm: SpaceViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val validity = listOf(1L to stringResource(R.string.invites_hours_1), 24L to stringResource(R.string.invites_hours_24), 168L to stringResource(R.string.invites_hours_168))
    var hours by rememberSaveable { mutableStateOf(24L) }
    var created by remember { mutableStateOf<CreatedInvite?>(null) }
    var error by remember { mutableStateOf<ApiError?>(null) }
    var pending by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val appName = stringResource(R.string.app_name)

    val link = created
    if (link != null) {
        AppSheet(stringResource(R.string.invites_linkTitle), onDismiss) {
            SelectionContainer {
                Text(
                    link.url,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppTheme.colors.raised, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                )
            }
            Text(stringResource(R.string.invites_onceHint, validity.first { it.first == hours }.second), color = AppTheme.colors.muted)
            SheetActions {
                AppButton(stringResource(R.string.common_done), onDismiss, variant = ButtonVariant.GHOST)
                AppButton(stringResource(R.string.invites_share), {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link.url).putExtra(Intent.EXTRA_SUBJECT, appName)
                    context.startActivity(Intent.createChooser(send, null))
                }, variant = ButtonVariant.SECONDARY)
                AppButton(stringResource(if (copied) R.string.invites_copied else R.string.invites_copy), {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(appName, link.url)))
                        copied = true
                    }
                })
            }
        }
        return
    }
    AppSheet(stringResource(R.string.invites_title), onDismiss) {
        Text(stringResource(R.string.invites_explanation), color = AppTheme.colors.muted)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.invites_validFor), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
            Segmented(validity, hours, { hours = it })
        }
        ErrorText(error)
        SheetActions {
            AppButton(stringResource(R.string.common_cancel), onDismiss, variant = ButtonVariant.GHOST)
            AppButton(stringResource(R.string.invites_create), {
                pending = true
                scope.launch {
                    when (val result = vm.createInvite(hours)) {
                        is ApiResult.Success -> created = result.value
                        is ApiResult.Failure -> error = result.error
                    }
                    pending = false
                }
            }, enabled = !pending)
        }
    }
}

// ---- accepting an invite ----

class InviteViewModel(private val container: AppContainer, private val token: String) : ActionViewModel() {
    private val previewState = MutableStateFlow<InvitePreview?>(null)
    val preview = previewState.asStateFlow()
    private val previewError = MutableStateFlow<ApiError?>(null)
    val loadError = previewError.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = container.spaces.previewInvite(token)) {
                is ApiResult.Success -> previewState.value = result.value
                is ApiResult.Failure -> previewError.value = result.error
            }
        }
    }

    fun accept(onJoined: () -> Unit) = act({ container.spaces.acceptInvite(token) }) { space ->
        viewModelScope.launch { container.lists.open(space.id) }
        onJoined()
    }
}

/** "{name} invites you to the space {space}. Join?" */
@Composable
fun InviteScreen(token: String) {
    val vm = pageViewModel { InviteViewModel(it, token) }
    val navigator = LocalNavigator.current
    val preview by vm.preview.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()

    Page(stringResource(R.string.invite_title)) { padding ->
        PageContent(padding) {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val p = preview
                when {
                    // Invalid/used link or already a member: say so and offer the way to the start page.
                    loadError != null -> {
                        ErrorText(loadError)
                        LinkText(stringResource(R.string.nav_toStart), navigator::toStart)
                    }
                    p == null -> Loading()
                    p.alreadyMember -> {
                        Hint(stringResource(R.string.invite_alreadyMember, p.spaceName))
                        LinkText(stringResource(R.string.nav_toStart), navigator::toStart)
                    }
                    else -> {
                        Text(stringResource(R.string.invite_question, p.invitedBy, p.spaceName))
                        ErrorText(error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton(stringResource(R.string.invite_accept), { vm.accept(navigator::toStart) }, enabled = !pending)
                            AppButton(stringResource(R.string.common_cancel), navigator::back, variant = ButtonVariant.SECONDARY)
                        }
                    }
                }
            }
        }
    }
}
