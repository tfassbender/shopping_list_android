package com.tfassbender.shoppinglist.data.repo

import com.tfassbender.shoppinglist.MutableClock
import com.tfassbender.shoppinglist.api.apis.ListsApi
import com.tfassbender.shoppinglist.data.local.LocalStore
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.sync.SpaceState
import com.tfassbender.shoppinglist.data.sync.SyncEngine
import com.tfassbender.shoppinglist.data.sync.SyncGateway
import com.tfassbender.shoppinglist.domain.ItemField
import com.tfassbender.shoppinglist.domain.PullResult
import com.tfassbender.shoppinglist.domain.PushResult
import com.tfassbender.shoppinglist.domain.SyncOp
import com.tfassbender.shoppinglist.testApiClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration

class ListRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = MutableClock()
    private var ids = 0

    /** Never reached: these tests only look at the local queue. */
    private val offline = object : SyncGateway {
        override suspend fun push(spaceId: String, baseSeq: Long, changes: List<SyncOp>): ApiResult<PushResult> = error("no sync")
        override suspend fun pull(spaceId: String, since: Long): ApiResult<PullResult> = error("no sync")
    }

    private fun TestScope.setup(): Pair<ListRepository, SyncEngine> {
        val engine = SyncEngine(offline, LocalStore(File(tmp.root, "store")), clock, backgroundScope, autoSync = false)
        val api = testApiClient(MockWebServer(), { null }).createService(ListsApi::class.java)
        return ListRepository(engine, api, clock, newId = { "id-${ids++}" }) to engine
    }

    private suspend fun SyncEngine.queue(): List<SyncOp> = state("s1").first().queue

    @Test
    fun `create a list and items, all queued and visible at once`() = runTest {
        val (repo, engine) = setup()

        val listId = repo.createList("s1", " Groceries ").getOrThrow()
        clock.advance(Duration.ofSeconds(1))
        val milk = repo.addItem("s1", listId, "Milk", quantity = "2").getOrThrow()
        clock.advance(Duration.ofSeconds(1))
        repo.addItem("s1", listId, "Bread").getOrThrow()

        val list = repo.list("s1", listId).first()!!
        assertEquals("Groceries", list.name)
        assertEquals(listOf("Milk", "Bread"), list.items.map { it.name })
        assertEquals(milk, list.items.first().id)
        assertEquals(listOf("CreateList", "AddItem", "AddItem"), engine.queue().map { it::class.simpleName })
    }

    @Test
    fun `the creation time of an unsynced item stays stable`() = runTest {
        val (repo, _) = setup()
        val listId = repo.createList("s1", "Groceries").getOrThrow()
        repo.addItem("s1", listId, "Milk")
        val before = repo.list("s1", listId).first()!!.items.single().createdAt

        clock.advance(Duration.ofHours(1))

        assertEquals(before, repo.list("s1", listId).first()!!.items.single().createdAt)
    }

    @Test
    fun `invalid input is rejected before it is queued`() = runTest {
        val (repo, engine) = setup()
        val listId = repo.createList("s1", "Groceries").getOrThrow()

        assertEquals("INVALID_NAME", (repo.createList("s1", " ").exceptionOrNull() as InvalidInputException).code)
        assertEquals("INVALID_ITEM_NAME", (repo.addItem("s1", listId, "x".repeat(101)).exceptionOrNull() as InvalidInputException).code)
        assertEquals("INVALID_ITEM_FIELD", (repo.addItem("s1", listId, "Milk", note = "x".repeat(201)).exceptionOrNull() as InvalidInputException).code)
        assertEquals("INVALID_ITEM_NAME", (repo.editItem("s1", listId, "i", ItemField.NAME, "").exceptionOrNull() as InvalidInputException).code)
        assertEquals(1, engine.queue().size)
    }

    @Test
    fun `remove checked sends the items checked now, check and uncheck for undo`() = runTest {
        val (repo, engine) = setup()
        val listId = repo.createList("s1", "Groceries").getOrThrow()
        val milk = repo.addItem("s1", listId, "Milk").getOrThrow()
        val bread = repo.addItem("s1", listId, "Bread").getOrThrow()
        val eggs = repo.addItem("s1", listId, "Eggs").getOrThrow()
        repo.check("s1", listId, milk)
        repo.check("s1", listId, bread)
        repo.uncheck("s1", listId, bread) // undo

        repo.removeChecked("s1", listId)

        val removeChecked = engine.queue().last() as SyncOp.RemoveChecked
        assertEquals(listOf(milk), removeChecked.itemIds)
        assertEquals(listOf(bread, eggs), repo.list("s1", listId).first()!!.items.map { it.id })
    }

    @Test
    fun `remove checked without checked items queues nothing`() = runTest {
        val (repo, engine) = setup()
        val listId = repo.createList("s1", "Groceries").getOrThrow()

        repo.removeChecked("s1", listId)

        assertEquals(1, engine.queue().size)
    }

    @Test
    fun `edit and delete`() = runTest {
        val (repo, engine) = setup()
        val listId = repo.createList("s1", "Groceries").getOrThrow()
        val milk = repo.addItem("s1", listId, "Milk", quantity = "2").getOrThrow()

        repo.editItem("s1", listId, milk, ItemField.QUANTITY, null).getOrThrow()
        assertNull(repo.list("s1", listId).first()!!.items.single().quantity)

        repo.deleteItem("s1", listId, milk)
        assertTrue(repo.list("s1", listId).first()!!.items.isEmpty())
        assertEquals(SpaceState().seq, engine.state("s1").first().seq)
    }
}
