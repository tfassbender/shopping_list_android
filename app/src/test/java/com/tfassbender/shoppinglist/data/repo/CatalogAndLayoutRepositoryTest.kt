package com.tfassbender.shoppinglist.data.repo

import com.tfassbender.shoppinglist.InMemoryDataStore
import com.tfassbender.shoppinglist.MutableClock
import com.tfassbender.shoppinglist.api.apis.CatalogApi
import com.tfassbender.shoppinglist.api.apis.LayoutsApi
import com.tfassbender.shoppinglist.api.apis.SyncApi
import com.tfassbender.shoppinglist.data.local.LocalStore
import com.tfassbender.shoppinglist.data.local.UiPreferences
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.sync.ApiSyncGateway
import com.tfassbender.shoppinglist.data.sync.SyncEngine
import com.tfassbender.shoppinglist.domain.ShoppingList
import com.tfassbender.shoppinglist.domain.Item
import com.tfassbender.shoppinglist.jsonResponse
import com.tfassbender.shoppinglist.testApiClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class CatalogAndLayoutRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val t0 = "2026-11-11T10:00:00Z"

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private class Setup(val engine: SyncEngine, val catalog: CatalogRepository, val layouts: LayoutRepository)

    private fun TestScope.setup(): Setup {
        val client = testApiClient(server, { "t" })
        val engine = SyncEngine(ApiSyncGateway(client.createService(SyncApi::class.java)), LocalStore(File(tmp.root, "store")), MutableClock(), backgroundScope, autoSync = false)
        return Setup(
            engine,
            CatalogRepository(client.createService(CatalogApi::class.java), engine),
            LayoutRepository(client.createService(LayoutsApi::class.java), engine, UiPreferences(InMemoryDataStore())),
        )
    }

    private val catalogJson =
        """{"categories":[{"id":"veg","name":"Vegetables","color":"#00ff00"},{"id":"dairy","name":"Dairy","color":"#0000ff"}],"units":["l"],"usage":{"butter":3}}"""

    private fun layoutJson(id: String, name: String, order: String, lists: String = "") =
        """{"id":"$id","name":"$name","mode":"CATEGORIES","categoryOrder":[$order],"itemOrder":[],"listIds":[$lists],"createdAt":"$t0"}"""

    /** One pull that brings a list, the catalog and two layouts. */
    private fun pullJson() =
        """{"seq":5,"fullResync":true,"listIds":["l1"],"layoutIds":["aldi","rewe"],"catalog":$catalogJson,
           "layouts":[${layoutJson("rewe", "Rewe", "\"veg\",\"dairy\"")},${layoutJson("aldi", "Aldi", "\"dairy\",\"veg\"", "\"l1\"")}],
           "lists":[{"id":"l1","name":"Groceries","createdAt":"$t0","seq":5,"deleted":false,"items":[
             ${item("i1", "Carrots", "veg")},${item("i2", "Milk", "dairy")},${item("i3", "Butter", "dairy")}]}]}"""

    private fun item(id: String, name: String, category: String) =
        """{"id":"$id","name":"$name","quantity":null,"unit":null,"note":null,"categoryId":"$category","checked":false,"deleted":false,"createdAt":"$t0","seq":5}"""

    @Test
    fun `the pull brings catalog and layouts, sorting then works offline`() = runTest {
        val s = setup()
        server.enqueue(jsonResponse(pullJson()))
        s.engine.sync("s1")
        server.enqueue(jsonResponse("""{"names":["milk"]}"""))
        s.layouts.refreshFavorites("s1", "aldi")
        server.shutdown() // offline from here on

        assertEquals(listOf("Aldi", "Rewe"), s.layouts.shopChoices("s1", "l1").first().map { it.name })
        assertEquals(listOf("veg", "dairy"), s.catalog.catalog("s1").first()!!.categories.map { it.id })

        val aldi = s.layouts.sortedItems("s1", "l1", "aldi", checkedToEnd = false).first()
        // dairy first; favorite Milk before Butter (most bought)
        assertEquals(listOf("Milk", "Butter", "Carrots"), aldi.map { it.item.name })
        assertEquals(listOf(true, false, false), aldi.map { it.favorite })

        val rewe = s.layouts.sortedItems("s1", "l1", "rewe", checkedToEnd = false).first()
        assertEquals(listOf("Carrots", "Butter", "Milk"), rewe.map { it.item.name })

        val noShop = s.layouts.sortedItems("s1", "l1", null, checkedToEnd = false).first()
        assertEquals(listOf("Carrots", "Milk", "Butter"), noShop.map { it.item.name })
    }

    @Test
    fun `catalog edits are stored`() = runTest {
        val s = setup()
        server.enqueue(jsonResponse(catalogJson.replace("\"units\":[\"l\"]", "\"units\":[\"l\",\"kg\"]")))

        val result = s.catalog.setUnits("s1", listOf(" l ", "kg", " "))

        assertEquals("""{"units":["l","kg"]}""", server.takeRequest().body.readUtf8())
        assertEquals(listOf("l", "kg"), (result as ApiResult.Success).value.units)
        assertEquals(listOf("l", "kg"), s.catalog.catalog("s1").first()!!.units)
    }

    @Test
    fun `layout create, update and delete keep the local copy current`() = runTest {
        val s = setup()
        server.enqueue(jsonResponse(layoutJson("edeka", "Edeka", ""), 201))
        s.layouts.create("s1", " Edeka ")
        server.enqueue(jsonResponse(layoutJson("edeka", "Edeka City", "")))
        s.layouts.update("s1", "edeka", "Edeka City", com.tfassbender.shoppinglist.domain.LayoutMode.CATEGORIES)
        assertEquals(listOf("Edeka City"), s.layouts.layouts("s1").first().map { it.name })

        server.enqueue(MockResponse().setResponseCode(204))
        s.layouts.delete("s1", "edeka")

        assertTrue(s.layouts.layouts("s1").first().isEmpty())
    }

    @Test
    fun `item order is sent as entries`() = runTest {
        val s = setup()
        server.enqueue(jsonResponse(layoutJson("a", "Aldi", "")))

        s.layouts.setItemOrder("s1", "a", listOf("Milk", "Bread"))

        val request = server.takeRequest()
        assertEquals("/api/spaces/s1/layouts/a/item-order", request.path)
        assertEquals("""{"entries":["Milk","Bread"]}""", request.body.readUtf8())
    }

    @Test
    fun `suggestions come from the server, offline from the local lists`() = runTest {
        val s = setup()
        server.enqueue(jsonResponse("""[{"name":"Milk","categoryId":"dairy","unit":"l","usageCount":4}]"""))
        assertEquals("l", s.catalog.suggestions("s1", "mi").single().unit)
        assertTrue(server.takeRequest().path!!.contains("q=mi"))

        s.engine.updateState("s1") {
            it.copy(confirmed = mapOf("l1" to ShoppingList("l1", "s1", "G", Instant.EPOCH, items = listOf(Item("x", "Mints", createdAt = Instant.EPOCH)))))
        }
        server.shutdown()

        assertEquals(listOf("Mints"), s.catalog.suggestions("s1", "mi").map { it.name })
        assertTrue(s.catalog.suggestions("s1", " ").isEmpty())
    }

    @Test
    fun `remembered shop per list`() = runTest {
        val s = setup()
        assertNull(s.layouts.lastLayout("l1").first())
        s.layouts.setLastLayout("l1", "aldi")
        assertEquals("aldi", s.layouts.lastLayout("l1").first())
    }
}
