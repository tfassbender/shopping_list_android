package com.tfassbender.shoppinglist.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tfassbender.shoppinglist.AppContainer
import com.tfassbender.shoppinglist.R
import com.tfassbender.shoppinglist.data.local.LastList
import com.tfassbender.shoppinglist.data.remote.ApiError
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.domain.ShoppingList
import com.tfassbender.shoppinglist.domain.Space
import com.tfassbender.shoppinglist.ui.common.AppButton
import com.tfassbender.shoppinglist.ui.common.AppSheet
import com.tfassbender.shoppinglist.ui.common.AppTextField
import com.tfassbender.shoppinglist.ui.common.BarIconButton
import com.tfassbender.shoppinglist.ui.common.ButtonVariant
import com.tfassbender.shoppinglist.ui.common.ErrorCodeText
import com.tfassbender.shoppinglist.ui.common.Fab
import com.tfassbender.shoppinglist.ui.common.Hint
import com.tfassbender.shoppinglist.ui.common.LoadError
import com.tfassbender.shoppinglist.ui.common.Loading
import com.tfassbender.shoppinglist.ui.common.NameSheet
import com.tfassbender.shoppinglist.ui.common.Page
import com.tfassbender.shoppinglist.ui.common.PageContent
import com.tfassbender.shoppinglist.ui.common.SectionTitle
import com.tfassbender.shoppinglist.ui.common.SheetActions
import com.tfassbender.shoppinglist.ui.common.pageViewModel
import com.tfassbender.shoppinglist.ui.common.stateIn
import com.tfassbender.shoppinglist.ui.navigation.LocalNavigator
import com.tfassbender.shoppinglist.ui.navigation.Screen
import com.tfassbender.shoppinglist.ui.theme.AppTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The lists of a space on this device; null = nothing on this device yet (never synced). */
fun AppContainer.spaceLists(spaceId: String): Flow<List<ShoppingList>?> =
    combine(syncEngine.state(spaceId), lists.lists(spaceId)) { state, lists ->
        if (state.seq == 0L && state.queue.isEmpty()) null else lists
    }

/** Lists of all given spaces, keyed by space id. */
@OptIn(ExperimentalCoroutinesApi::class)
fun AppContainer.listsOfSpaces(spaces: Flow<List<Space>>): Flow<Map<String, List<ShoppingList>?>> =
    spaces.flatMapLatest { all ->
        if (all.isEmpty()) flowOf(emptyMap())
        else combine(all.map { space -> spaceLists(space.id).map { space.id to it } }) { it.toMap() }
    }

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    /** null until the first answer of the server when nothing is cached (avoids a wrong "no spaces"). */
    private val refreshed = MutableStateFlow(false)
    private val error = MutableStateFlow<ApiError?>(null)
    val loadError = error.asStateFlow()

    val spaces = combine(container.spaces.spaces, refreshed) { spaces, refreshed ->
        if (spaces.isEmpty() && !refreshed) null else spaces
    }.stateIn(this, null)
    val lists = container.listsOfSpaces(container.spaces.spaces).stateIn(this, emptyMap())
    val lastList = container.uiPreferences.lastList.stateIn(this, null)
    val online = container.online.stateIn(this, true)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            error.value = null
            when (val result = container.spaces.refresh()) {
                is ApiResult.Success -> refreshed.value = true
                is ApiResult.Failure -> {
                    error.value = result.error
                    if (container.spaces.cached().isNotEmpty()) refreshed.value = true
                }
            }
        }
    }

    /** Creates the list locally (works offline) and returns its id; null for an invalid name. */
    suspend fun createList(spaceId: String, name: String): Result<String> = container.lists.createList(spaceId, name)

    private val createSpaceState = MutableStateFlow<ApiError?>(null)
    val createSpaceError = createSpaceState.asStateFlow()

    fun createSpace(name: String, onCreated: (Space) -> Unit) {
        viewModelScope.launch {
            createSpaceState.value = null
            when (val result = container.spaces.create(name)) {
                is ApiResult.Success -> {
                    refreshed.value = true
                    onCreated(result.value)
                    container.lists.open(result.value.id)
                }
                is ApiResult.Failure -> createSpaceState.value = result.error
            }
        }
    }
}

/**
 * Start page: all lists of all spaces (in the user's order of spaces) - opening a list is one tap. + at
 * the bottom creates a list; spaces and settings are icons in the bar. Works offline (lists come from
 * the local store).
 */
@Composable
fun HomeScreen() {
    val vm = pageViewModel { HomeViewModel(it) }
    val navigator = LocalNavigator.current
    val spaces by vm.spaces.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val lastList by vm.lastList.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    var newListFor by rememberSaveable { mutableStateOf<String?>(null) }
    var newList by rememberSaveable { mutableStateOf(false) }
    var newSpace by rememberSaveable { mutableStateOf(false) }

    fun openNewList(spaceId: String?) {
        val all = spaces.orEmpty()
        newListFor = spaceId ?: lastList?.spaceId?.takeIf { id -> all.any { it.id == id } }
        newList = true
    }

    Page(
        title = stringResource(R.string.start_title),
        actions = {
            BarIconButton(Icons.Filled.Group, stringResource(R.string.spaces_title), { navigator.push(Screen.Spaces) })
            BarIconButton(Icons.Filled.Settings, stringResource(R.string.nav_settings), { navigator.push(Screen.Settings) })
        },
        // Without a space there is nowhere to put a list: the page asks to create a space instead.
        fab = { if (!spaces.isNullOrEmpty()) Fab(stringResource(R.string.newList_title), { openNewList(null) }) },
    ) { padding ->
        PageContent(padding) {
            val all = spaces
            when {
                all == null && loadError != null -> LoadError(loadError!!, vm::refresh)
                all == null -> Loading()
                all.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.start_noSpaces), color = AppTheme.colors.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    AppButton(stringResource(R.string.start_createSpace), { newSpace = true })
                }
                else -> all.forEach { space ->
                    SpaceLists(
                        space = space,
                        lists = lists[space.id],
                        showName = all.size > 1,
                        lastList = lastList,
                        online = online,
                        onOpen = { list -> navigator.push(Screen.ListPage(space.id, list.id)) },
                        onNewList = { openNewList(space.id) },
                    )
                }
            }
        }
    }

    if (newList && spaces != null) {
        NewListSheet(
            spaces = spaces!!,
            defaultSpaceId = newListFor,
            create = vm::createList,
            onCreated = { spaceId, listId ->
                newList = false
                navigator.push(Screen.ListPage(spaceId, listId))
            },
            onDismiss = { newList = false },
        )
    }
    if (newSpace) {
        val error by vm.createSpaceError.collectAsStateWithLifecycle()
        NameSheet(
            title = stringResource(R.string.spaces_new),
            submitLabel = stringResource(R.string.common_create),
            error = error,
            onSubmit = { name -> vm.createSpace(name) { newSpace = false } },
            onDismiss = { newSpace = false },
        )
    }
}

@Composable
private fun SpaceLists(
    space: Space,
    lists: List<ShoppingList>?,
    showName: Boolean,
    lastList: LastList?,
    online: Boolean,
    onOpen: (ShoppingList) -> Unit,
    onNewList: () -> Unit,
) {
    val colors = AppTheme.colors
    Column(Modifier.semantics { contentDescription = space.name }) {
        if (showName) SectionTitle(space.name) else Spacer(Modifier.height(12.dp))
        when {
            // Nothing on this device yet: offline it would load forever, so say why nothing shows.
            lists == null -> if (online) Loading() else Hint(stringResource(R.string.start_notLoadedOffline))
            lists.isEmpty() -> Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.start_noLists) + " ", color = colors.muted)
                Text(
                    stringResource(R.string.newList_title),
                    color = colors.accent,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                    modifier = Modifier.clickable(onClick = onNewList),
                )
            }
            else -> lists.forEach { list ->
                val open = list.openCount
                val showLastUsed = lastList?.listId == list.id && (lists.size > 1 || showName)
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onOpen(list) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)) { append(list.name) }
                                if (showLastUsed) {
                                    withStyle(SpanStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.accent)) {
                                        append(" · " + stringResource(R.string.start_lastUsed))
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        val countLabel = stringResource(R.string.lists_openCount, open.toString())
                        Box(
                            Modifier
                                .padding(start = 12.dp)
                                .heightIn(min = 28.dp)
                                .widthIn(min = 28.dp)
                                .clip(CircleShape)
                                .background(colors.raised)
                                .semantics { contentDescription = countLabel }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (open > 0) open.toString() else "✓",
                                fontSize = 14.sp,
                                fontWeight = if (open > 0) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (open > 0) colors.fg else colors.muted,
                            )
                        }
                    }
                    HorizontalDivider(color = colors.line)
                }
            }
        }
    }
}

/**
 * New list: name plus the space (as buttons, only when there are several spaces). Works offline: the list
 * is created locally and synced later. Opens the new list right away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewListSheet(
    spaces: List<Space>,
    defaultSpaceId: String?,
    create: suspend (String, String) -> Result<String>,
    onCreated: (spaceId: String, listId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    var name by rememberSaveable { mutableStateOf("") }
    var spaceId by rememberSaveable { mutableStateOf(defaultSpaceId ?: spaces.firstOrNull()?.id.orEmpty()) }
    var errorCode by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val submit = {
        if (name.isNotBlank() && spaceId.isNotEmpty()) {
            scope.launch {
                create(spaceId, name).fold(
                    onSuccess = { onCreated(spaceId, it) },
                    onFailure = { errorCode = (it as? com.tfassbender.shoppinglist.data.repo.InvalidInputException)?.code ?: "unknown" },
                )
            }
        }
    }
    AppSheet(stringResource(R.string.newList_title), onDismiss) {
        AppTextField(name, { name = it }, stringResource(R.string.common_name), maxLength = 60, autoFocus = true, onImeAction = { submit() })
        if (spaces.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.newList_space), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    spaces.forEach { space ->
                        val selected = space.id == spaceId
                        Box(
                            Modifier
                                .heightIn(min = 44.dp)
                                .clip(CircleShape)
                                .background(if (selected) colors.brand else colors.surface)
                                .border(1.dp, if (selected) colors.brand else colors.line, CircleShape)
                                .clickable { spaceId = space.id }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(space.name, color = if (selected) colors.brandInk else colors.fg, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                Text(stringResource(R.string.newList_spaceHint), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        }
        ErrorCodeText(errorCode)
        SheetActions {
            AppButton(stringResource(R.string.common_cancel), onDismiss, variant = ButtonVariant.GHOST)
            AppButton(stringResource(R.string.common_create), { submit() }, enabled = name.isNotBlank())
        }
    }
}
