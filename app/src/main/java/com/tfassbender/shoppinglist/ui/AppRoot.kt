package com.tfassbender.shoppinglist.ui

import android.app.Activity
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tfassbender.shoppinglist.AppContainer
import com.tfassbender.shoppinglist.data.repo.LogoutReason
import com.tfassbender.shoppinglist.domain.Theme
import com.tfassbender.shoppinglist.ui.auth.ForgotPasswordScreen
import com.tfassbender.shoppinglist.ui.auth.LoginScreen
import com.tfassbender.shoppinglist.ui.auth.RegisterScreen
import com.tfassbender.shoppinglist.ui.common.LocalAppContainer
import com.tfassbender.shoppinglist.ui.home.HomeScreen
import com.tfassbender.shoppinglist.ui.home.spaceLists
import com.tfassbender.shoppinglist.ui.list.ListScreen
import com.tfassbender.shoppinglist.ui.navigation.NavHost
import com.tfassbender.shoppinglist.ui.navigation.Screen
import com.tfassbender.shoppinglist.ui.navigation.rememberNavigator
import com.tfassbender.shoppinglist.ui.settings.ApiTokensScreen
import com.tfassbender.shoppinglist.ui.settings.SettingsScreen
import com.tfassbender.shoppinglist.ui.space.CatalogScreen
import com.tfassbender.shoppinglist.ui.space.HistoryScreen
import com.tfassbender.shoppinglist.ui.space.InviteScreen
import com.tfassbender.shoppinglist.ui.space.LayoutEditorScreen
import com.tfassbender.shoppinglist.ui.space.LayoutsScreen
import com.tfassbender.shoppinglist.ui.space.SpaceScreen
import com.tfassbender.shoppinglist.ui.space.SpacesScreen
import com.tfassbender.shoppinglist.ui.theme.AppTheme
import com.tfassbender.shoppinglist.ui.theme.ShoppingListTheme
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private val AUTH_SCREENS = setOf(Screen.Login, Screen.Register, Screen.ForgotPassword)

/**
 * The whole UI. Logged out it shows the login pages, logged in the start page; theme and language follow
 * the user's settings (shared by all their devices), before login the device's language or the EN/DE
 * toggle. While the app is open the screen stays on (per-device setting).
 */
@Composable
fun AppRoot(container: AppContainer) {
    val loggedIn by container.auth.isLoggedIn.collectAsStateWithLifecycle(null)
    // The pages the app opens with - decided once per app start (a few ms: session and local files).
    val start by produceState<List<Screen>?>(null) { value = startScreens(container) }
    val cachedUser by container.account.user.collectAsStateWithLifecycle(null)
    val deviceLanguage by container.uiPreferences.deviceLanguage.collectAsStateWithLifecycle(null)
    val keepScreenOn by container.uiPreferences.keepScreenOn.collectAsStateWithLifecycle(true)
    val user = cachedUser?.user.takeIf { loggedIn == true }
    val language = user?.settings?.language ?: deviceLanguage

    KeepScreenOn(keepScreenOn)
    Localized(language) {
        ShoppingListTheme(user?.settings?.theme ?: Theme.SYSTEM) {
            CompositionLocalProvider(LocalAppContainer provides container) {
                Box(Modifier.fillMaxSize().background(AppTheme.colors.surface)) {
                    val startScreens = start
                    if (loggedIn != null && startScreens != null) Pages(container, loggedIn == true, startScreens)
                }
            }
        }
    }
}

@Composable
private fun Pages(container: AppContainer, loggedIn: Boolean, start: List<Screen>) {
    val navigator = rememberNavigator(start)
    // Why the user was logged out - shown on the login page (e.g. session expired elsewhere).
    var logoutReason by rememberSaveable { mutableStateOf<LogoutReason?>(null) }

    LaunchedEffect(Unit) {
        container.auth.loggedOut.collect { reason -> logoutReason = reason }
    }
    LaunchedEffect(loggedIn) {
        val onAuthPage = navigator.current.screen in AUTH_SCREENS
        if (loggedIn && onAuthPage) {
            logoutReason = null
            navigator.resetTo(Screen.Home)
        } else if (!loggedIn && !onAuthPage) {
            navigator.resetTo(Screen.Login)
        }
    }
    BackHandler(enabled = navigator.canGoBack) { navigator.back() }

    NavHost(navigator) { screen ->
        when (screen) {
            Screen.Login -> LoginScreen(sessionExpired = logoutReason == LogoutReason.SESSION_INVALID)
            Screen.Register -> RegisterScreen()
            Screen.ForgotPassword -> ForgotPasswordScreen()
            Screen.Home -> HomeScreen()
            is Screen.ListPage -> ListScreen(screen.spaceId, screen.listId)
            Screen.Spaces -> SpacesScreen()
            is Screen.SpacePage -> SpaceScreen(screen.spaceId)
            is Screen.Invite -> InviteScreen(screen.token)
            is Screen.Catalog -> CatalogScreen(screen.spaceId)
            is Screen.History -> HistoryScreen(screen.spaceId)
            is Screen.Layouts -> LayoutsScreen(screen.spaceId)
            is Screen.LayoutEditor -> LayoutEditorScreen(screen.spaceId, screen.layoutId)
            Screen.Settings -> SettingsScreen()
            Screen.ApiTokens -> ApiTokensScreen()
        }
    }
}

/**
 * Logged in, the app opens on the list that was open last on this device (stored locally, not on the
 * server), with the start page below it for "back". A list that is gone meanwhile: the start page.
 * After the app was killed in the background, the saved back stack wins (see [rememberNavigator]).
 */
private suspend fun startScreens(container: AppContainer): List<Screen> {
    if (!container.auth.isLoggedIn.first()) return listOf(Screen.Login)
    val last = container.uiPreferences.lastList.first() ?: return listOf(Screen.Home)
    // null = the space isn't on this device yet: open the list anyway, its page waits for the sync.
    val lists = withTimeoutOrNull(1_000) { container.spaceLists(last.spaceId).first() }
    if (lists != null && lists.none { it.id == last.listId }) return listOf(Screen.Home)
    return listOf(Screen.Home, Screen.ListPage(last.spaceId, last.listId))
}

/** Texts in [language] (`en`/`de`; null = the device's language) - independent of the system setting. */
@Composable
private fun Localized(language: String?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val base = LocalConfiguration.current
    if (language == null || base.locales[0].language == language) {
        content()
        return
    }
    val configuration = remember(base, language) {
        Configuration(base).apply { setLocale(Locale.forLanguageTag(language)) }
    }
    val localized = remember(context, configuration) { context.createConfigurationContext(configuration) }
    CompositionLocalProvider(
        LocalConfiguration provides configuration,
        LocalContext provides localized,
        LocalResources provides localized.resources,
        content = content,
    )
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(activity, enabled) {
        if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
