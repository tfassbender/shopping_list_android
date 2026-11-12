package com.tfassbender.shoppinglist.data.sync

import android.util.Log
import com.tfassbender.shoppinglist.data.local.JsonFile
import com.tfassbender.shoppinglist.data.local.LocalStore
import com.tfassbender.shoppinglist.data.remote.ApiError
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.domain.ChangeStatus
import com.tfassbender.shoppinglist.domain.ListsById
import com.tfassbender.shoppinglist.domain.MergeWarning
import com.tfassbender.shoppinglist.domain.PullResult
import com.tfassbender.shoppinglist.domain.PushResult
import com.tfassbender.shoppinglist.domain.SyncOp
import com.tfassbender.shoppinglist.domain.WarningType
import com.tfassbender.shoppinglist.domain.applyOp
import com.tfassbender.shoppinglist.domain.applyPull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID

enum class SyncStatus { IDLE, SYNCING, OFFLINE, ERROR }

/** Called when a sync run ends with changes still queued (offline / error) - schedule a background retry. */
fun interface PendingChangesListener {
    fun onChangesPending()
}

/**
 * The offline-first data layer for lists and items - port of the web app's `SyncEngine`
 * (frontend/src/offline/syncEngine.ts in the server repo). The UI reads [lists] (server state + pending
 * changes) and writes via [enqueue]; [sync] pushes the queue and pulls what others changed. All state is
 * persisted per space ([SpaceState]), so changes made offline survive closing the app.
 *
 * Every write to a space's state goes through [JsonFile.update] and transforms the *current* state, so a
 * change enqueued while a sync is running is never lost.
 */
class SyncEngine(
    private val gateway: SyncGateway,
    private val store: LocalStore,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 300,
    /** false: no automatic (debounced) syncs - tests trigger [sync] themselves. */
    private val autoSync: Boolean = true,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val inflight = HashMap<String, Deferred<Unit>>()
    private val scheduled = HashMap<String, Job>()
    private val statusFlow = MutableStateFlow(SyncStatus.IDLE)

    /** Pending change counts and warnings per space, for app-wide indicators without loading every file. */
    private val summaries = MutableStateFlow<Map<String, Summary>>(emptyMap())
    private data class Summary(val pending: Int, val warnings: List<MergeWarning>)

    /**
     * Spaces the server answered with 404 (deleted, or the user was removed / left) -> number of unsaved
     * changes that had to be dropped. Cleared when the space syncs again (e.g. invited again) or on [reset].
     */
    private val lostFlow = MutableStateFlow<Map<String, Int>>(emptyMap())
    val accessLost: StateFlow<Map<String, Int>> = lostFlow.asStateFlow()

    var pendingChangesListener: PendingChangesListener? = null

    val status: StateFlow<SyncStatus> = statusFlow.asStateFlow()

    /** Number of changes not yet sent, over all spaces. */
    val pendingCount: Flow<Int> = summaries.map { all -> all.values.sumOf { it.pending } }.distinctUntilChanged()

    /** Merge warnings and rejected changes of all spaces, oldest first, until [clearWarnings]. */
    val warnings: Flow<List<MergeWarning>> =
        summaries.map { all -> all.values.flatMap { it.warnings }.sortedBy { it.at } }.distinctUntilChanged()

    // ---- reading ----

    fun state(spaceId: String): Flow<SpaceState> = file(spaceId).flow

    /** The lists of a space as the user should see them: confirmed state with acked + queued changes applied. */
    fun lists(spaceId: String): Flow<ListsById> = state(spaceId).map { view(spaceId, it) }.distinctUntilChanged()

    suspend fun currentLists(spaceId: String): ListsById = view(spaceId, file(spaceId).get())

    private fun view(spaceId: String, state: SpaceState): ListsById {
        val now = clock.instant()
        return (state.acked + state.queue).fold(state.confirmed) { lists, op -> applyOp(lists, op, spaceId, now) }
    }

    // ---- loading ----

    /** App start: loads all spaces stored on this device and syncs them. */
    suspend fun start() {
        store.storedSpaceIds().forEach { refreshSummary(it, file(it).get()) }
        syncAll()
    }

    /** Makes a space available (from storage, then from the server). */
    suspend fun open(spaceId: String) {
        refreshSummary(spaceId, file(spaceId).get())
        sync(spaceId)
    }

    // ---- writing ----

    /** Applies a change locally right away and queues it for the server. */
    suspend fun enqueue(spaceId: String, op: SyncOp) {
        updateState(spaceId) { it.copy(queue = it.queue + op) }
        scheduleSync(spaceId)
    }

    /** Changes other parts of a space's state (catalog, layouts) - see the repositories. */
    suspend fun updateState(spaceId: String, transform: (SpaceState) -> SpaceState): SpaceState {
        val updated = file(spaceId).update(transform)
        refreshSummary(spaceId, updated)
        return updated
    }

    suspend fun clearWarnings() {
        summaries.value.filterValues { it.warnings.isNotEmpty() }.keys.forEach { spaceId ->
            updateState(spaceId) { it.copy(warnings = emptyList()) }
        }
    }

    fun newChangeId(): String = newId()

    // ---- syncing ----

    /** Syncs all known spaces plus [spaceIds] (e.g. all spaces of the user - reconnect, app foreground, interval). */
    suspend fun syncAll(spaceIds: Collection<String> = emptyList()) {
        val ids = summaries.value.keys + store.storedSpaceIds() + spaceIds
        ids.map { id -> scope.async { sync(id) } }.awaitAll()
    }

    /**
     * A live update says the space changed on the server at [seq]: sync if we're behind. Our own changes are
     * announced too - after our sync we already have that seq, so nothing happens.
     */
    suspend fun syncIfBehind(spaceId: String, seq: Long?) {
        if (seq == null || seq > file(spaceId).get().seq) sync(spaceId)
    }

    /**
     * Pushes pending changes, then pulls everything changed since the last pull. One run per space at a
     * time: a call during a running sync joins it - and runs once more afterwards if changes are still
     * queued (that run may have started before them), so everything queued before the call is sent.
     */
    suspend fun sync(spaceId: String) {
        var joined = false
        val run = synchronized(inflight) {
            inflight[spaceId]?.also { joined = true }
                ?: scope.async(start = CoroutineStart.LAZY) { runSync(spaceId) }.also { inflight[spaceId] = it }
        }
        try {
            run.await()
        } finally {
            synchronized(inflight) { if (inflight[spaceId] === run) inflight.remove(spaceId) }
        }
        if (joined && hasQueued(spaceId) && statusFlow.value == SyncStatus.IDLE) return sync(spaceId)
        afterSync(spaceId)
    }

    private fun hasQueued(spaceId: String) = (summaries.value[spaceId]?.pending ?: 0) > 0

    private fun afterSync(spaceId: String) {
        if (!hasQueued(spaceId)) return
        // Changes made while syncing go out with the next run. After failures the next attempt comes from
        // reconnect / interval / background work, not a tight retry loop.
        if (statusFlow.value == SyncStatus.IDLE) scheduleSync(spaceId) else pendingChangesListener?.onChangesPending()
    }

    private suspend fun runSync(spaceId: String) {
        try {
            pushAndPull(spaceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unexpected (e.g. storage full): keep the queue, report an error, retry with the next trigger.
            Log.w(TAG, "Sync of space $spaceId failed", e)
            statusFlow.value = SyncStatus.ERROR
        }
    }

    private suspend fun pushAndPull(spaceId: String) {
        val base = file(spaceId).get().seq
        statusFlow.value = SyncStatus.SYNCING
        val batch = file(spaceId).get().queue.take(MAX_BATCH)
        if (batch.isNotEmpty()) {
            when (val pushed = gateway.push(spaceId, base, batch)) {
                is ApiResult.Failure -> return handleError(spaceId, pushed.error)
                is ApiResult.Success -> handlePushResult(spaceId, batch, pushed.value)
            }
        }
        // Pull since the base (not the push seq): other users' changes may lie in between.
        when (val pulled = gateway.pull(spaceId, base)) {
            is ApiResult.Failure -> return handleError(spaceId, pulled.error)
            is ApiResult.Success -> {
                updateState(spaceId) { it.withPull(spaceId, pulled.value) }
                lostFlow.update { it - spaceId }
            }
        }
        statusFlow.value = SyncStatus.IDLE
    }

    private suspend fun handlePushResult(spaceId: String, batch: List<SyncOp>, pushed: PushResult) {
        val done = pushed.results.map { it.changeId }.toSet()
        val rejected = pushed.results.filter { it.status == ChangeStatus.REJECTED }
        val rejectedIds = rejected.map { it.changeId }.toSet()
        val at = clock.instant()
        val warnings = pushed.warnings.map { it.copy(id = newId(), at = at, spaceId = spaceId) } +
            rejected
                // A rejected change on a deleted list is already explained by the LIST_DELETED warning.
                .filter { it.code != "LIST_NOT_FOUND" }
                .map { result ->
                    MergeWarning(
                        id = newId(), at = at, spaceId = spaceId, type = WarningType.REJECTED,
                        listId = batch.firstOrNull { it.changeId == result.changeId }?.listId.orEmpty(), code = result.code,
                    )
                }
        val sent = batch.filter { it.changeId in done && it.changeId !in rejectedIds }
        updateState(spaceId) { state ->
            state.copy(
                queue = state.queue.filterNot { it.changeId in done },
                acked = state.acked + sent,
                warnings = state.warnings + warnings,
            )
        }
    }

    private fun SpaceState.withPull(spaceId: String, pull: PullResult): SpaceState {
        val layoutIds = pull.layoutIds.toSet()
        val newLayouts = pull.layouts ?: layouts.filter { it.id in layoutIds }
        return copy(
            seq = pull.seq,
            confirmed = applyPull(confirmed, pull, spaceId),
            acked = emptyList(),
            // A complete catalog replaces the local one; otherwise only changed history entries come. Without
            // a local catalog they are ignored - the first pull always brings the complete catalog.
            catalog = pull.catalog ?: catalog?.mergeHistory(pull.historyChanges),
            layouts = newLayouts,
        )
    }

    private suspend fun handleError(spaceId: String, error: ApiError) {
        when {
            error is ApiError.Network -> statusFlow.value = SyncStatus.OFFLINE // keep everything, retry later
            error is ApiError.Server && error.status == 404 -> {
                // The space is gone or we were removed: drop its local data (unsaved changes can't be saved anywhere).
                val dropped = file(spaceId).get().queue.size
                lostFlow.update { it + (spaceId to (it[spaceId] ?: 0) + dropped) }
                drop(spaceId)
                statusFlow.value = SyncStatus.IDLE
            }
            else -> statusFlow.value = SyncStatus.ERROR
        }
    }

    private fun scheduleSync(spaceId: String) {
        if (!autoSync) return
        synchronized(scheduled) {
            scheduled.remove(spaceId)?.cancel()
            // A short delay batches quick taps (check, check, check) into one request.
            scheduled[spaceId] = scope.launch {
                delay(debounceMillis)
                synchronized(scheduled) { scheduled.remove(spaceId) }
                sync(spaceId)
            }
        }
    }

    // ---- forgetting ----

    /** Forgets a space (deleted, left, removed from it). */
    suspend fun drop(spaceId: String) {
        synchronized(scheduled) { scheduled.remove(spaceId)?.cancel() }
        store.deleteSpace(spaceId)
        summaries.update { it - spaceId }
    }

    /** Logout: cancels pending syncs; the files are deleted by the [LocalStore]. */
    fun reset() {
        synchronized(scheduled) {
            scheduled.values.forEach { it.cancel() }
            scheduled.clear()
        }
        summaries.value = emptyMap()
        lostFlow.value = emptyMap()
        statusFlow.value = SyncStatus.IDLE
    }

    suspend fun hasPendingChanges(): Boolean =
        store.storedSpaceIds().any { file(it).get().queue.isNotEmpty() }

    // ---- internals ----

    private fun file(spaceId: String): JsonFile<SpaceState> = store.spaceFile(spaceId, SpaceState.serializer()) { SpaceState() }

    private fun refreshSummary(spaceId: String, state: SpaceState) {
        summaries.update { it + (spaceId to Summary(state.queue.size, state.warnings)) }
    }

    companion object {
        const val MAX_BATCH = 200
        private const val TAG = "SyncEngine"
    }
}
