package com.tfassbender.shoppinglist.data.sync

import com.tfassbender.shoppinglist.api.apis.SyncApi
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.domain.ChangeStatus
import com.tfassbender.shoppinglist.domain.ItemField
import com.tfassbender.shoppinglist.domain.LayoutMode
import com.tfassbender.shoppinglist.domain.SyncOp
import com.tfassbender.shoppinglist.domain.WarningType
import com.tfassbender.shoppinglist.jsonResponse
import com.tfassbender.shoppinglist.testApiClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class ApiSyncGatewayTest {

    private lateinit var server: MockWebServer
    private lateinit var gateway: ApiSyncGateway

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        gateway = ApiSyncGateway(testApiClient(server, { "t" }).createService(SyncApi::class.java))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `push sends the server's change format and maps results and warnings`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"seq":7,"results":[{"changeId":"c1","status":"APPLIED","code":null},{"changeId":"c2","status":"REJECTED","code":"INVALID_NAME"}],
                   "warnings":[{"type":"OVERWRITTEN","listId":"l1","listName":"Groceries","itemId":"i1","itemName":"Milk","field":"QUANTITY","theirValue":"3","yourValue":"2","byName":"Anna"}]}""",
            ),
        )
        val ops = listOf(
            SyncOp.AddItem("c1", "l1", "i1", "Milk", quantity = "2", createdAt = Instant.EPOCH),
            SyncOp.EditItem("c2", "l1", "i1", ItemField.NOTE, null),
            SyncOp.RemoveChecked("c3", "l1", listOf("i2")),
        )

        val result = (gateway.push("s1", 6, ops) as ApiResult.Success).value

        val body = server.takeRequest().body.readUtf8()
        assertEquals(
            """{"baseSeq":6,"changes":[""" +
                """{"changeId":"c1","type":"ADD_ITEM","listId":"l1","itemId":"i1","name":"Milk","quantity":"2"},""" +
                """{"changeId":"c2","type":"EDIT_ITEM","listId":"l1","itemId":"i1","field":"NOTE","value":""},""" +
                """{"changeId":"c3","type":"REMOVE_CHECKED","listId":"l1","itemIds":["i2"]}]}""",
            body,
        )
        assertEquals(listOf(ChangeStatus.APPLIED, ChangeStatus.REJECTED), result.results.map { it.status })
        val warning = result.warnings.single()
        assertEquals(WarningType.OVERWRITTEN, warning.type)
        assertEquals("QUANTITY", warning.field)
        assertEquals("Anna", warning.byName)
        assertEquals("s1", warning.spaceId)
    }

    @Test
    fun `pull maps lists with tombstones, catalog and layouts`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"seq":12,"fullResync":false,
                   "lists":[{"id":"l1","name":"Groceries","createdAt":"2026-11-11T10:00:00Z","seq":12,"deleted":false,
                     "items":[{"id":"i1","name":"Milk","quantity":"2","unit":null,"note":null,"categoryId":"dairy","checked":true,"deleted":false,"createdAt":"2026-11-11T10:00:00Z","seq":12},
                              {"id":"i2","name":"Bread","quantity":null,"unit":null,"note":null,"categoryId":null,"checked":false,"deleted":true,"createdAt":"2026-11-11T10:00:00Z","seq":11}]}],
                   "listIds":["l1"],
                   "catalog":{"categories":[{"id":"dairy","name":"Dairy","color":"#0000ff"}],"units":["l","kg"],"usage":{"milk":4},
                     "history":[{"name":"Milk","categoryId":"dairy","unit":"l","usageCount":4,"lastUsed":"2026-11-11T10:00:00Z"}]},
                   "historyChanges":[],
                   "layouts":[{"id":"a","name":"Aldi","mode":"ITEMS","categoryOrder":[],"itemOrder":["Milk"],"listIds":["l1"],"createdAt":"2026-11-11T10:00:00Z"}],
                   "layoutIds":["a"]}""",
            ),
        )

        val pull = (gateway.pull("s1", 10) as ApiResult.Success).value

        assertEquals("/api/spaces/s1/changes?since=10", server.takeRequest().path)
        assertEquals(12L, pull.seq)
        val items = pull.lists.single().items
        assertEquals(listOf(false, true), items.map { it.deleted })
        assertTrue(items[0].item.checked)
        assertEquals("dairy", items[0].item.categoryId)
        assertEquals(mapOf("milk" to 4), pull.catalog!!.usage)
        assertEquals("l", pull.catalog!!.history.single().unit)
        assertEquals(LayoutMode.ITEMS, pull.layouts!!.single().mode)
    }

    @Test
    fun `pull without catalog and layouts`() = runTest {
        server.enqueue(jsonResponse("""{"seq":3,"fullResync":true,"lists":[],"listIds":[],"catalog":null,"historyChanges":[{"name":"Milk","categoryId":null,"unit":null,"usageCount":1,"lastUsed":"2026-11-11T10:00:00Z"}],"layouts":null,"layoutIds":[]}"""))

        val pull = (gateway.pull("s1", 0) as ApiResult.Success).value

        assertEquals(null, pull.catalog)
        assertEquals(null, pull.layouts)
        assertTrue(pull.fullResync)
    }
}
