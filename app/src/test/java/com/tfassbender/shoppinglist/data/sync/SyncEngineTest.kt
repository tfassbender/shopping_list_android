package com.tfassbender.shoppinglist.data.sync

import com.tfassbender.shoppinglist.MutableClock
import com.tfassbender.shoppinglist.data.local.LocalStore
import com.tfassbender.shoppinglist.data.remote.ApiError
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.domain.ChangeResult
import com.tfassbender.shoppinglist.domain.ChangeStatus
import com.tfassbender.shoppinglist.domain.ListsById
import com.tfassbender.shoppinglist.domain.MergeWarning
import com.tfassbender.shoppinglist.domain.PullResult
import com.tfassbender.shoppinglist.domain.PushResult
import com.tfassbender.shoppinglist.domain.ShoppingList
import com.tfassbender.shoppinglist.domain.SyncItem
import com.tfassbender.shoppinglist.domain.SyncList
import com.tfassbender.shoppinglist.domain.SyncOp
import com.tfassbender.shoppinglist.domain.WarningType
import com.tfassbender.shoppinglist.domain.applyOp
import com.tfassbender.shoppinglist.domain.Item
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Instant

/** Port of the web app's `syncEngine.test.ts` (frontend/src/offline in the server repo). */
class SyncEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = MutableClock(Instant.parse("2026-01-02T00:00:00Z"))
    private var ids = 0

    /** A minimal server: applies pushed ops with the client logic and answers pulls with the full state. */
    inner class FakeServer : SyncGateway {
        var lists: ListsById = mapOf("l1" to ShoppingList("l1", SPACE, "Groceries", t0, seq = 1))
        var seq = 10L
        var offline = false
        val pushes = mutableListOf<Pair<Long, List<SyncOp>>>()
        val pulls = mutableListOf<Long>()
        var nextWarnings = listOf<MergeWarning>()
        val rejectCodes = mutableMapOf<String, String>()
        var pullOverride: ((Long) -> PullResult)? = null
        var pushGate: CompletableDeferred<Unit>? = null
        var nextPushError: ApiError? = null
        var nextPullError: ApiError? = null

        override suspend fun push(spaceId: String, baseSeq: Long, changes: List<SyncOp>): ApiResult<PushResult> {
            pushGate?.let { gate -> pushGate = null; pushStarted.complete(Unit); gate.await() }
            if (offline) return ApiResult.Failure(ApiError.Network(IOException("offline")))
            nextPushError?.let { nextPushError = null; return ApiResult.Failure(it) }
            pushes += baseSeq to changes
            seq++
            val results = changes.map { op ->
                val code = rejectCodes[op.changeId]
                if (code == null) lists = applyOp(lists, op, SPACE, t0)
                ChangeResult(op.changeId, if (code == null) ChangeStatus.APPLIED else ChangeStatus.REJECTED, code)
            }
            val warnings = nextWarnings.also { nextWarnings = emptyList() }
            return ApiResult.Success(PushResult(seq, results, warnings))
        }

        override suspend fun pull(spaceId: String, since: Long): ApiResult<PullResult> {
            if (offline) return ApiResult.Failure(ApiError.Network(IOException("offline")))
            nextPullError?.let { nextPullError = null; return ApiResult.Failure(it) }
            pulls += since
            pullOverride?.let { return ApiResult.Success(it(since)) }
            val syncLists = lists.values.map { l -> SyncList(l.id, l.name, l.createdAt, l.seq, false, l.items.map { SyncItem(it, false) }) }
            return ApiResult.Success(PullResult(seq, true, syncLists, lists.keys.toList(), null, null, emptyList()))
        }

        val pushStarted = CompletableDeferred<Unit>()
    }

    private val server = FakeServer()
    private val root by lazy { File(tmp.root, "store") }

    private fun TestScope.engineWith(store: LocalStore = LocalStore(root)) =
        SyncEngine(server, store, clock, backgroundScope, autoSync = false, newId = { "id-${ids++}" })

    private suspend fun SyncEngine.names(listId: String = "l1") = lists(SPACE).first()[listId]?.items?.map { it.name }

    private fun add(engine: SyncEngine, itemId: String, name: String) =
        SyncOp.AddItem(engine.newChangeId(), "l1", itemId, name)

    private suspend fun TestScope.openedEngine(): SyncEngine = engineWith().also { it.open(SPACE) }

    @Test
    fun `loads the server state when a space is opened`() = runTest {
        val engine = openedEngine()

        assertEquals("Groceries", engine.lists(SPACE).first()["l1"]?.name)
        assertEquals(listOf(0L), server.pulls)
    }

    @Test
    fun `applies changes locally at once and sends them with the last pulled seq as base`() = runTest {
        val engine = openedEngine()
        engine.enqueue(SPACE, add(engine, "milk", "Milk"))
        assertEquals(listOf("Milk"), engine.names()) // before any request
        assertEquals(1, engine.pendingCount.first())

        engine.sync(SPACE)

        assertEquals(10L, server.pushes[0].first)
        assertEquals(listOf(0L, 10L), server.pulls) // pull since the base, not since the push seq
        assertEquals(0, engine.pendingCount.first())
        assertEquals(listOf("Milk"), engine.names())
    }

    @Test
    fun `keeps changes while offline and sends them once the server is reachable`() = runTest {
        val engine = openedEngine()
        var pendingNotified = 0
        engine.pendingChangesListener = PendingChangesListener { pendingNotified++ }
        server.offline = true
        engine.enqueue(SPACE, add(engine, "milk", "Milk"))
        engine.enqueue(SPACE, SyncOp.CheckItem(engine.newChangeId(), "l1", "milk"))
        engine.sync(SPACE)

        assertEquals(SyncStatus.OFFLINE, engine.status.value)
        assertEquals(2, engine.pendingCount.first())
        assertTrue(engine.lists(SPACE).first()["l1"]!!.items[0].checked)
        assertEquals(1, pendingNotified)

        server.offline = false
        engine.sync(SPACE)
        assertEquals(SyncStatus.IDLE, engine.status.value)
        assertEquals(0, engine.pendingCount.first())
        assertTrue(server.lists["l1"]!!.items[0].let { it.name == "Milk" && it.checked })
    }

    @Test
    fun `changes made offline survive a restart of the app`() = runTest {
        val first = openedEngine()
        server.offline = true
        first.enqueue(SPACE, add(first, "bread", "Bread"))

        val restarted = engineWith(LocalStore(root))
        restarted.start()
        assertEquals(listOf("Bread"), restarted.names())
        assertEquals(1, restarted.pendingCount.first())
        assertTrue(restarted.hasPendingChanges())

        server.offline = false
        restarted.syncAll()
        assertEquals(listOf("Bread"), server.lists["l1"]!!.items.map { it.name })
    }

    @Test
    fun `changes made during a running sync are kept and sent with the next sync`() = runTest {
        val engine = openedEngine()
        val gate = CompletableDeferred<Unit>()
        server.pushGate = gate
        engine.enqueue(SPACE, add(engine, "a", "A"))
        val running = async { engine.sync(SPACE) }
        server.pushStarted.await() // the first batch is on its way
        engine.enqueue(SPACE, add(engine, "b", "B"))
        gate.complete(Unit)
        running.await()

        assertEquals(listOf("A", "B"), engine.names())
        assertEquals(1, engine.pendingCount.first())
        engine.sync(SPACE)
        assertEquals(listOf(1, 1), server.pushes.map { it.second.size })
        assertEquals(0, engine.pendingCount.first())
    }

    @Test
    fun `accepted changes stay visible if the pull afterwards fails`() = runTest {
        val engine = openedEngine()
        engine.enqueue(SPACE, add(engine, "a", "A"))
        server.nextPullError = ApiError.Network(IOException("gone"))
        engine.sync(SPACE)

        assertEquals(0, engine.pendingCount.first()) // not sent again
        assertEquals(listOf("A"), engine.names())
        engine.sync(SPACE)
        assertEquals(1, server.pushes.size)
        assertEquals(listOf("A"), engine.names())
    }

    @Test
    fun `parallel sync calls share one run`() = runTest {
        val engine = openedEngine()
        engine.enqueue(SPACE, add(engine, "a", "A"))

        listOf(async { engine.sync(SPACE) }, async { engine.sync(SPACE) }, async { engine.syncAll() }).awaitAll()

        assertEquals(1, server.pushes.size)
    }

    @Test
    fun `merges a delta pull - changed and deleted items, deleted and vanished lists`() = runTest {
        val engine = openedEngine()
        engine.enqueue(SPACE, SyncOp.CreateList(engine.newChangeId(), "l2", "Hardware"))
        engine.enqueue(SPACE, add(engine, "milk", "Milk"))
        engine.enqueue(SPACE, add(engine, "bread", "Bread"))
        engine.sync(SPACE)

        fun item(id: String, name: String, deleted: Boolean = false) = SyncItem(Item(id, name, createdAt = t0, seq = 12), deleted)
        server.pullOverride = {
            PullResult(
                seq = 12, fullResync = false,
                lists = listOf(
                    SyncList("l1", "Weekend", t0, 12, false, listOf(item("milk", "Oat milk"), item("bread", "Bread", true), item("eggs", "Eggs"))),
                    SyncList("l2", "Hardware", t0, 12, true, emptyList()),
                ),
                listIds = listOf("l1"), catalog = null, layouts = null, layoutIds = emptyList(),
            )
        }
        engine.sync(SPACE)

        assertEquals(setOf("l1"), engine.lists(SPACE).first().keys)
        assertEquals("Weekend", engine.lists(SPACE).first()["l1"]!!.name)
        assertEquals(listOf("Oat milk", "Eggs"), engine.names())
    }

    @Test
    fun `a full resync replaces everything local`() = runTest {
        val engine = openedEngine()
        engine.enqueue(SPACE, add(engine, "milk", "Milk"))
        engine.sync(SPACE)
        server.pullOverride = {
            PullResult(50, true, listOf(SyncList("l9", "Fresh", t0, 50, false, emptyList())), listOf("l9"), null, null, emptyList())
        }
        engine.sync(SPACE)

        assertEquals(setOf("l9"), engine.lists(SPACE).first().keys)
    }

    @Test
    fun `stores merge warnings and rejected changes until cleared`() = runTest {
        val engine = openedEngine()
        server.nextWarnings = listOf(
            MergeWarning(id = "", at = Instant.EPOCH, spaceId = SPACE, type = WarningType.RESTORED, listId = "l1", listName = "Groceries", itemId = "milk", itemName = "Milk", byName = "Anna"),
        )
        engine.enqueue(SPACE, add(engine, "x", "X"))
        val rename = SyncOp.RenameList(engine.newChangeId(), "l1", "Too long")
        server.rejectCodes[rename.changeId] = "INVALID_NAME"
        engine.enqueue(SPACE, rename)
        engine.sync(SPACE)

        val warnings = engine.warnings.first()
        assertEquals(listOf(WarningType.RESTORED, WarningType.REJECTED), warnings.map { it.type })
        assertEquals("INVALID_NAME", warnings[1].code)
        assertEquals("l1", warnings[1].listId)
        assertEquals(SPACE, warnings[1].spaceId)
        assertEquals(clock.now, warnings[0].at)
        assertEquals("Groceries", engine.lists(SPACE).first()["l1"]!!.name) // rejected change no longer shown

        engine.clearWarnings()
        assertEquals(emptyList<MergeWarning>(), engine.warnings.first())
    }

    @Test
    fun `a rejection because the list was deleted is not reported twice`() = runTest {
        val engine = openedEngine()
        val check = SyncOp.CheckItem(engine.newChangeId(), "l1", "x")
        server.rejectCodes[check.changeId] = "LIST_NOT_FOUND"
        server.nextWarnings = listOf(MergeWarning(id = "", at = Instant.EPOCH, spaceId = SPACE, type = WarningType.LIST_DELETED, listId = "l1", listName = "Groceries"))
        engine.enqueue(SPACE, check)
        engine.sync(SPACE)

        assertEquals(listOf(WarningType.LIST_DELETED), engine.warnings.first().map { it.type })
    }

    @Test
    fun `drops a space the user lost access to`() = runTest {
        val engine = openedEngine()
        server.nextPullError = ApiError.Server(404, "SPACE_NOT_FOUND", null)
        engine.sync(SPACE)

        assertEquals(emptyMap<String, ShoppingList>(), engine.lists(SPACE).first())
        assertFalse(File(root, "spaces/$SPACE.json").exists())
    }

    @Test
    fun `other errors keep the data and report an error status`() = runTest {
        val engine = openedEngine()
        engine.enqueue(SPACE, add(engine, "a", "A"))
        server.nextPushError = ApiError.Server(500, "INTERNAL_ERROR", null)
        engine.sync(SPACE)

        assertEquals(SyncStatus.ERROR, engine.status.value)
        assertEquals(1, engine.pendingCount.first())
    }

    @Test
    fun `quick changes are batched into one push after the debounce`() = runBlocking {
        // Real time: the test scheduler would skip the debounce delay while waiting for file I/O.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val engine = SyncEngine(server, LocalStore(root), clock, scope, debounceMillis = 500, newId = { "id-${ids++}" })
        engine.open(SPACE)
        engine.enqueue(SPACE, add(engine, "a", "A"))
        engine.enqueue(SPACE, add(engine, "b", "B"))
        engine.enqueue(SPACE, add(engine, "c", "C"))
        assertTrue(server.pushes.isEmpty())

        withTimeout(5_000) { while (engine.pendingCount.first() > 0 || engine.status.value != SyncStatus.IDLE) delay(20) }
        scope.cancel()

        assertEquals(listOf(3), server.pushes.map { it.second.size })
    }

    @Test
    fun `live update - sync only if the server is ahead`() = runTest {
        val engine = openedEngine() // seq 10 now

        engine.syncIfBehind(SPACE, 10) // e.g. our own change, already pulled
        assertEquals(listOf(0L), server.pulls)

        engine.syncIfBehind(SPACE, 11)
        assertEquals(listOf(0L, 10L), server.pulls)
    }

    @Test
    fun `reset forgets the in-memory state (logout)`() = runTest {
        val store = LocalStore(root)
        val engine = engineWith(store)
        engine.open(SPACE)
        engine.enqueue(SPACE, add(engine, "a", "A"))

        engine.reset()
        store.clear()

        assertEquals(0, engine.pendingCount.first())
        assertNull(engine.lists(SPACE).first()["l1"])
    }

    private companion object {
        const val SPACE = "s1"
    }
}
