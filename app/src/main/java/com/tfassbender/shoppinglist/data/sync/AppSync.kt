package com.tfassbender.shoppinglist.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.tfassbender.shoppinglist.data.repo.AccountRepository
import com.tfassbender.shoppinglist.data.repo.AuthRepository
import com.tfassbender.shoppinglist.data.repo.SpaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import java.util.concurrent.atomic.AtomicBoolean

/**
 * When the app syncs (see doc/tech_stack.md, "Offline sync" and "Live updates"):
 * - app comes to the foreground (and after a login): refresh the session, the user and the spaces, sync all spaces, then
 *   every 5 minutes as fallback; live updates ([LiveUpdates], SSE) while in the foreground;
 * - network comes back while in the foreground: sync all spaces;
 * - app goes to the background with queued changes: [SyncWorker] sends them when there is a network.
 *
 * Nothing is fetched in the background (decided: no push notifications).
 */
class AppSync(
    private val context: Context,
    private val auth: AuthRepository,
    private val account: AccountRepository,
    private val spaces: SpaceRepository,
    private val engine: SyncEngine,
    private val scope: CoroutineScope,
    liveUpdatesFactory: (LiveUpdateListener) -> LiveUpdates,
) {
    private val inForeground = AtomicBoolean(false)
    private var periodic: Job? = null

    private val liveUpdates = liveUpdatesFactory(object : LiveUpdateListener {
        override suspend fun onConnected() = syncEverything()

        override suspend fun onEvent(event: SpaceEvent) {
            val spaceId = event.spaceId ?: return
            // A space we don't know yet: e.g. an invite accepted on another device.
            if (spaces.cached().none { it.id == spaceId }) spaces.refresh()
            engine.syncIfBehind(spaceId, event.seq)
        }
    })

    /** Call once from Application.onCreate (main thread). */
    fun start() {
        engine.pendingChangesListener = PendingChangesListener { SyncWorker.schedule(context) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onForeground()
            override fun onStop(owner: LifecycleOwner) = onBackground()
        })
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (inForeground.get()) scope.launch { syncEverything() }
            }
        })
        // After a login: load everything for the new session (the app start is covered by onForeground).
        scope.launch {
            auth.isLoggedIn.distinctUntilChanged().drop(1).collect { loggedIn ->
                if (loggedIn) {
                    if (inForeground.get()) liveUpdates.start()
                    refreshAndSync()
                } else {
                    liveUpdates.stop()
                }
            }
        }
    }


    private fun onForeground() {
        inForeground.set(true)
        liveUpdates.start()
        periodic?.cancel()
        periodic = scope.launch {
            refreshAndSync()
            while (isActive) {
                delay(PERIODIC_SYNC_MILLIS)
                syncEverything()
            }
        }
    }

    private fun onBackground() {
        inForeground.set(false)
        liveUpdates.stop()
        periodic?.cancel()
        scope.launch {
            if (auth.currentSession() != null && engine.hasPendingChanges()) SyncWorker.schedule(context)
        }
    }

    private suspend fun refreshAndSync() {
        if (auth.currentSession() == null) return
        auth.refreshIfNeeded()
        account.refresh()
        spaces.refresh()
        syncEverything()
    }

    /** All spaces of the user - also those never opened on this device, so they are available offline. */
    suspend fun syncEverything() {
        if (auth.currentSession() == null) return
        engine.syncAll(spaces.cached().map { it.id })
    }

    private companion object {
        const val PERIODIC_SYNC_MILLIS = 5 * 60 * 1000L
    }
}
