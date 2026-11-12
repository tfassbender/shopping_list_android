package com.tfassbender.shoppinglist.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * The pages of the app - the same as the web app's routes (`frontend/src/App.tsx` in the server repo).
 * Serializable, so the back stack survives the app being killed in the background.
 */
@Serializable
sealed interface Screen {
    @Serializable data object Login : Screen
    @Serializable data object Register : Screen
    @Serializable data object ForgotPassword : Screen

    /** Start page: all lists of all spaces. */
    @Serializable data object Home : Screen
    @Serializable data class ListPage(val spaceId: String, val listId: String) : Screen
    @Serializable data object Spaces : Screen
    @Serializable data class SpacePage(val spaceId: String) : Screen
    @Serializable data class Invite(val token: String) : Screen
    @Serializable data class Catalog(val spaceId: String) : Screen
    @Serializable data class History(val spaceId: String) : Screen
    @Serializable data class Layouts(val spaceId: String) : Screen
    @Serializable data class LayoutEditor(val spaceId: String, val layoutId: String) : Screen
    @Serializable data object Settings : Screen
    @Serializable data object ApiTokens : Screen
}

/** One page on the back stack; [id] keys its saved UI state and its ViewModels. */
@Serializable
data class Entry(val screen: Screen, val id: String = UUID.randomUUID().toString())

/**
 * A plain back stack (no navigation library, as in the homeautomation app). The top entry is shown;
 * "back" pops it - so a page opened from a list leads back to that list, like the web app's `state.back`.
 */
class Navigator(initial: List<Entry>) {
    val stack = mutableStateListOf<Entry>().apply { addAll(initial) }

    val current: Entry get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    fun push(screen: Screen) {
        if (stack.lastOrNull()?.screen != screen) stack.add(Entry(screen))
    }

    fun back() {
        if (canGoBack) stack.removeAt(stack.lastIndex)
    }

    /** Replaces the current page (e.g. a deleted space must not be reachable with back). */
    fun replace(screen: Screen) {
        stack[stack.lastIndex] = Entry(screen)
    }

    /** Back to the start page; everything above it is dropped. */
    fun toStart() = resetTo(Screen.Home)

    fun resetTo(vararg screens: Screen) {
        stack.clear()
        stack.addAll(screens.map { Entry(it) })
    }

    companion object {
        val saver: Saver<Navigator, String> = Saver(
            save = { navigator -> Json.encodeToString(navigator.stack.toList()) },
            restore = { saved -> Navigator(Json.decodeFromString<List<Entry>>(saved)) },
        )
    }
}

@Composable
fun rememberNavigator(initial: List<Screen>): Navigator =
    rememberSaveable(saver = Navigator.saver) { Navigator(initial.map { Entry(it) }) }

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("no Navigator provided") }

/** Keeps one [ViewModelStore] per back stack entry; survives configuration changes (it is a ViewModel itself). */
class EntryViewModelStores : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    fun owner(entryId: String): ViewModelStoreOwner {
        val store = stores.getOrPut(entryId) { ViewModelStore() }
        return object : ViewModelStoreOwner {
            override val viewModelStore = store
        }
    }

    /** Clears the ViewModels of entries that left the back stack. */
    fun retain(entryIds: Set<String>) {
        stores.keys.filterNot { it in entryIds }.forEach { stores.remove(it)?.clear() }
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/**
 * Shows the top entry of [navigator]: its saved UI state (rememberSaveable) and its ViewModels live as long
 * as the entry is on the stack.
 */
@Composable
fun NavHost(navigator: Navigator, content: @Composable (Screen) -> Unit) {
    val stores: EntryViewModelStores = viewModel()
    val stateHolder = rememberSaveableStateHolder()
    LaunchedEffect(navigator) {
        var previous = emptySet<String>()
        snapshotFlow { navigator.stack.map { it.id }.toSet() }.collect { ids ->
            stores.retain(ids)
            (previous - ids).forEach(stateHolder::removeState)
            previous = ids
        }
    }
    val entry = navigator.current
    CompositionLocalProvider(LocalNavigator provides navigator, LocalViewModelStoreOwner provides stores.owner(entry.id)) {
        stateHolder.SaveableStateProvider(entry.id) {
            content(entry.screen)
        }
    }
}
