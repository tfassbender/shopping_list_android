package com.tfassbender.shoppinglist

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.tfassbender.shoppinglist.api.apis.APITokensApi
import com.tfassbender.shoppinglist.api.apis.AccountApi
import com.tfassbender.shoppinglist.api.apis.AuthApi
import com.tfassbender.shoppinglist.api.apis.ListsApi
import com.tfassbender.shoppinglist.api.apis.SpacesApi
import com.tfassbender.shoppinglist.api.apis.SyncApi
import com.tfassbender.shoppinglist.data.local.LocalStore
import com.tfassbender.shoppinglist.data.local.SessionStore
import com.tfassbender.shoppinglist.data.local.TokenCipher
import com.tfassbender.shoppinglist.data.remote.ApiClientFactory
import com.tfassbender.shoppinglist.data.remote.EMULATOR_HOST
import com.tfassbender.shoppinglist.data.remote.LOCAL_HOSTS
import com.tfassbender.shoppinglist.data.repo.AccountRepository
import com.tfassbender.shoppinglist.data.repo.ApiTokenRepository
import com.tfassbender.shoppinglist.data.repo.AuthRepository
import com.tfassbender.shoppinglist.data.repo.ListRepository
import com.tfassbender.shoppinglist.data.repo.SpaceRepository
import com.tfassbender.shoppinglist.data.sync.ApiSyncGateway
import com.tfassbender.shoppinglist.data.sync.AppSync
import com.tfassbender.shoppinglist.data.sync.SyncEngine
import com.tfassbender.shoppinglist.data.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Clock

private val Context.sessionDataStore by preferencesDataStore(name = "session")

/** Manual dependency injection: the app-wide singletons, created once in [ShoppingListApp]. */
class AppContainer(context: Context) {

    /** Lives as long as the app process; for work that must not be cancelled with a screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock: Clock = Clock.systemUTC()

    private val sessionStore = SessionStore(context.sessionDataStore, TokenCipher())

    private val okHttp = ApiClientFactory.okHttp(
        baseUrl = BuildConfig.API_BASE_URL,
        // Runs on OkHttp's worker thread; DataStore serves this from memory after the first read.
        tokenProvider = { runBlocking { sessionStore.current()?.token } },
        unauthorizedListener = { auth.onUnauthorized() },
        logRequests = BuildConfig.DEBUG,
        // Release builds: HTTPS only. Debug builds: also the local dev server (emulator host / adb reverse).
        plainHttpHosts = if (BuildConfig.DEBUG) LOCAL_HOSTS + EMULATOR_HOST else emptySet(),
    )
    private val apiClient = ApiClientFactory.create(BuildConfig.API_BASE_URL, okHttp)

    val auth: AuthRepository = AuthRepository(apiClient.createService(AuthApi::class.java), sessionStore, clock, appScope)

    private val store = LocalStore(File(context.filesDir, "store"))

    val account = AccountRepository(apiClient.createService(AccountApi::class.java), auth, store.me)

    val spaces = SpaceRepository(apiClient.createService(SpacesApi::class.java), store.spaces)

    val syncEngine = SyncEngine(ApiSyncGateway(apiClient.createService(SyncApi::class.java)), store, clock, appScope)

    val lists = ListRepository(syncEngine, apiClient.createService(ListsApi::class.java), clock)

    val appSync = AppSync(context, auth, account, spaces, syncEngine, appScope)

    val apiTokens = ApiTokenRepository(apiClient.createService(APITokensApi::class.java))

    init {
        auth.addLogoutHook {
            syncEngine.reset()
            SyncWorker.cancel(context)
            store.clear()
        }
        spaces.addSpaceRemovedListener { syncEngine.drop(it) }
    }
}
