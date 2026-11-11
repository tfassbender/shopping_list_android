package com.tfassbender.shoppinglist.integration

import com.tfassbender.shoppinglist.FakeCipher
import com.tfassbender.shoppinglist.InMemoryDataStore
import com.tfassbender.shoppinglist.api.apis.APITokensApi
import com.tfassbender.shoppinglist.api.apis.AccountApi
import com.tfassbender.shoppinglist.api.apis.AuthApi
import com.tfassbender.shoppinglist.api.apis.CatalogApi
import com.tfassbender.shoppinglist.api.apis.LayoutsApi
import com.tfassbender.shoppinglist.api.apis.ListsApi
import com.tfassbender.shoppinglist.api.apis.SpacesApi
import com.tfassbender.shoppinglist.api.apis.SyncApi
import com.tfassbender.shoppinglist.data.local.LocalStore
import com.tfassbender.shoppinglist.data.local.SessionStore
import com.tfassbender.shoppinglist.data.local.UiPreferences
import com.tfassbender.shoppinglist.data.remote.ApiClientFactory
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.repo.AccountRepository
import com.tfassbender.shoppinglist.data.repo.ApiTokenRepository
import com.tfassbender.shoppinglist.data.repo.AuthRepository
import com.tfassbender.shoppinglist.data.repo.CatalogRepository
import com.tfassbender.shoppinglist.data.repo.LayoutRepository
import com.tfassbender.shoppinglist.data.repo.ListRepository
import com.tfassbender.shoppinglist.data.repo.LogoutReason
import com.tfassbender.shoppinglist.data.repo.RegisterResult
import com.tfassbender.shoppinglist.data.repo.SpaceRepository
import com.tfassbender.shoppinglist.data.sync.ApiSyncGateway
import com.tfassbender.shoppinglist.data.sync.LiveUpdateListener
import com.tfassbender.shoppinglist.data.sync.LiveUpdates
import com.tfassbender.shoppinglist.data.sync.SpaceEvent
import com.tfassbender.shoppinglist.data.sync.SyncEngine
import com.tfassbender.shoppinglist.domain.ItemField
import com.tfassbender.shoppinglist.domain.LayoutMode
import com.tfassbender.shoppinglist.domain.WarningType
import com.tfassbender.shoppinglist.domain.parseInviteToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * End-to-end tests of the app's data layer against a real server (default `http://localhost:4720/`,
 * override with `-Dshoppinglist.it.baseUrl=...`). Skipped when no server is reachable. The server must
 * run with email verification disabled (dev mode: `./gradlew :backend:quarkusDev` in the server repo).
 *
 * Test users are registered as `android-it-…@example.com` and delete their accounts afterwards.
 */
class IntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val clients = mutableListOf<Client>()

    /** One device of one user: the app's repositories without Android. */
    inner class Client(name: String) {
        val sessionStore = SessionStore(InMemoryDataStore(), FakeCipher())
        val store = LocalStore(tmp.newFolder(name))
        lateinit var auth: AuthRepository
        private fun okHttp() = ApiClientFactory.okHttp(BASE_URL, { runBlocking { sessionStore.current()?.token } }, { auth.onUnauthorized() }, logRequests = false)
        private val api = ApiClientFactory.create(BASE_URL, okHttp())

        init {
            auth = AuthRepository(api.createService(AuthApi::class.java), sessionStore, Clock.systemUTC(), scope)
        }

        val account = AccountRepository(api.createService(AccountApi::class.java), auth, store.me)
        val spaces = SpaceRepository(api.createService(SpacesApi::class.java), store.spaces)
        val engine = SyncEngine(ApiSyncGateway(api.createService(SyncApi::class.java)), store, Clock.systemUTC(), scope, autoSync = false)
        val lists = ListRepository(engine, api.createService(ListsApi::class.java), Clock.systemUTC())
        val catalog = CatalogRepository(api.createService(CatalogApi::class.java), engine)
        val layouts = LayoutRepository(api.createService(LayoutsApi::class.java), engine, UiPreferences(InMemoryDataStore()))
        val apiTokens = ApiTokenRepository(api.createService(APITokensApi::class.java))
        val email = "android-it-${UUID.randomUUID()}@example.com"

        fun liveUpdates(listener: LiveUpdateListener) =
            LiveUpdates(okHttp().readTimeout(60, TimeUnit.SECONDS).build(), BASE_URL + "api/events", { auth.currentSession() != null }, listener, scope)

        init {
            auth.addLogoutHook {
                engine.reset()
                store.clear()
            }
        }
    }

    private suspend fun register(name: String): Client {
        val client = Client(name)
        val result = client.auth.register(client.email, name, PASSWORD, "en")
        assertTrue("register: $result", (result as ApiResult.Success).value is RegisterResult.LoggedIn)
        clients += client
        return client
    }

    @Before
    fun requireServer() {
        val reachable = runCatching {
            OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(BASE_URL + "api/health").build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        assumeTrue("No server at $BASE_URL - integration tests skipped", reachable)
    }

    @After
    fun cleanUp() = runBlocking {
        clients.forEach { client ->
            if (client.auth.currentSession() == null) client.auth.login(client.email, PASSWORD)
            client.account.deleteAccount()
        }
        scope.cancel()
    }

    private fun <T> ApiResult<T>.value(): T = (this as? ApiResult.Success)?.value ?: error("expected success: $this")

    @Test
    fun `two users share a list, edit it offline and get merge warnings`() = runBlocking {
        val anna = register("Anna")
        val ben = register("Ben")

        // Anna: space with default categories, a list with items (all via the sync queue).
        val space = anna.spaces.create("IT Home").value()
        val listId = anna.lists.createList(space.id, "Groceries").getOrThrow()
        val milk = anna.lists.addItem(space.id, listId, "Milk", quantity = "1").getOrThrow()
        anna.lists.addItem(space.id, listId, "Bread").getOrThrow()
        anna.engine.sync(space.id)
        assertEquals(0, anna.engine.pendingCount.first())
        assertTrue(anna.catalog.catalog(space.id).first()!!.categories.isNotEmpty())

        // Ben joins via invite link and sees the list.
        val invite = anna.spaces.createInvite(space.id).value()
        val token = parseInviteToken("Join: ${invite.url}")!!
        assertEquals("IT Home", ben.spaces.previewInvite(token).value().spaceName)
        ben.spaces.acceptInvite(token).value()
        ben.engine.open(space.id)
        assertEquals(listOf("Milk", "Bread"), ben.lists.list(space.id, listId).first()!!.items.map { it.name })

        // Both change the quantity of Milk "offline" (queued), Ben syncs last: later wins + warning for Ben.
        anna.lists.editItem(space.id, listId, milk, ItemField.QUANTITY, "2").getOrThrow()
        ben.lists.editItem(space.id, listId, milk, ItemField.QUANTITY, "3").getOrThrow()
        ben.lists.check(space.id, listId, milk)
        anna.engine.sync(space.id)
        ben.engine.sync(space.id)

        val warning = ben.engine.warnings.first().single()
        assertEquals(WarningType.OVERWRITTEN, warning.type)
        assertEquals("Anna", warning.byName)
        assertEquals("2", warning.theirValue)
        anna.engine.sync(space.id)
        val annasMilk = anna.lists.list(space.id, listId).first()!!.items.first { it.id == milk }
        assertEquals("3", annasMilk.quantity)
        assertTrue(annasMilk.checked)

        // Remove checked on Anna's side, Ben un-checks Milk offline meanwhile: edit wins over delete.
        anna.lists.removeChecked(space.id, listId)
        ben.lists.uncheck(space.id, listId, milk)
        anna.engine.sync(space.id)
        ben.engine.sync(space.id)
        assertEquals(WarningType.RESTORED, ben.engine.warnings.first().last().type)
        anna.engine.sync(space.id)
        assertTrue(anna.lists.list(space.id, listId).first()!!.items.any { it.id == milk })
    }

    @Test
    fun `owner deletes a list a member changed offline`() = runBlocking {
        val anna = register("Anna")
        val ben = register("Ben")
        val space = anna.spaces.create("IT Lists").value()
        val listId = anna.lists.createList(space.id, "Party").getOrThrow()
        anna.engine.sync(space.id)
        ben.spaces.acceptInvite(anna.spaces.createInvite(space.id).value().token).value()
        ben.engine.open(space.id)

        ben.lists.addItem(space.id, listId, "Chips").getOrThrow() // offline change
        assertEquals("OWNER_ONLY", (ben.lists.deleteList(space.id, listId) as ApiResult.Failure).error.let { (it as com.tfassbender.shoppinglist.data.remote.ApiError.Server).code })
        anna.lists.deleteList(space.id, listId).value()
        ben.engine.sync(space.id)

        assertEquals(listOf(WarningType.LIST_DELETED), ben.engine.warnings.first().map { it.type })
        assertNull(ben.lists.list(space.id, listId).first())
    }

    @Test
    fun `shop layouts and favorites sort a list`() = runBlocking {
        val anna = register("Anna")
        val space = anna.spaces.create("IT Shops").value()
        val listId = anna.lists.createList(space.id, "Groceries").getOrThrow()
        anna.engine.sync(space.id)
        val categories = anna.catalog.catalog(space.id).first()!!.categories
        val first = categories.first()
        val last = categories.last()
        anna.lists.addItem(space.id, listId, "A first item", categoryId = first.id).getOrThrow()
        anna.lists.addItem(space.id, listId, "B last item", categoryId = last.id).getOrThrow()
        anna.lists.addItem(space.id, listId, "C last item", categoryId = last.id).getOrThrow()
        anna.engine.sync(space.id)

        val shop = anna.layouts.create(space.id, "Reverse", LayoutMode.CATEGORIES).value()
        anna.layouts.setCategoryOrder(space.id, shop.id, categories.map { it.id }.reversed()).value()
        anna.layouts.connectList(space.id, shop.id, listId).value()
        anna.layouts.addFavorite(space.id, shop.id, "C last item").value()

        val sorted = anna.layouts.sortedItems(space.id, listId, shop.id, checkedToEnd = false).first()
        assertEquals(listOf("C last item", "B last item", "A first item"), sorted.map { it.item.name })
        assertEquals(listOf(shop.id), anna.layouts.shopChoices(space.id, listId).first().map { it.id }.take(1))
        assertTrue(anna.catalog.suggestions(space.id, "B l").any { it.name == "B last item" })
    }

    @Test
    fun `live updates announce changes of other members`() = runBlocking {
        val anna = register("Anna")
        val ben = register("Ben")
        val space = anna.spaces.create("IT Live").value()
        ben.spaces.acceptInvite(anna.spaces.createInvite(space.id).value().token).value()
        val events: MutableList<SpaceEvent> = Collections.synchronizedList(mutableListOf())
        val connected = kotlinx.coroutines.CompletableDeferred<Unit>()
        val updates = ben.liveUpdates(object : LiveUpdateListener {
            override suspend fun onConnected() {
                connected.complete(Unit)
            }

            override suspend fun onEvent(event: SpaceEvent) {
                events += event
            }
        })
        updates.start()
        withTimeout(5_000) { connected.await() }

        anna.lists.createList(space.id, "Announced").getOrThrow()
        anna.engine.sync(space.id)

        withTimeout(5_000) { while (events.none { it.spaceId == space.id }) delay(20) }
        updates.stop()
    }

    @Test
    fun `log out everywhere invalidates the other device`() = runBlocking {
        val phone = register("Anna")
        val tablet = Client("tablet")
        tablet.auth.login(phone.email, PASSWORD).value()
        val loggedOut = async { tablet.auth.loggedOut.first() }

        phone.account.logoutEverywhere().value()
        tablet.spaces.refresh()

        assertEquals(LogoutReason.SESSION_INVALID, withTimeout(5_000) { loggedOut.await() })
        assertNull(tablet.auth.currentSession())
        assertTrue(phone.spaces.refresh() is ApiResult.Success) // this device got a new token
        assertTrue(phone.apiTokens.list() is ApiResult.Success)
    }

    private companion object {
        val BASE_URL: String = System.getProperty("shoppinglist.it.baseUrl") ?: "http://localhost:4720/"
        const val PASSWORD = "integration-secret"
    }
}
