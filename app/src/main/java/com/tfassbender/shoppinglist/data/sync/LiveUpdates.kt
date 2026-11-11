package com.tfassbender.shoppinglist.data.sync

import com.tfassbender.shoppinglist.data.local.StoreJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.coroutines.resume

/** A notification from `GET /api/events`: `SPACE_CHANGED` (with space id and seq) or `PING` (heartbeat). */
@Serializable
data class SpaceEvent(val type: String, val spaceId: String? = null, val seq: Long? = null) {
    val isSpaceChanged: Boolean get() = type == "SPACE_CHANGED" && spaceId != null
}

/** What happens with the events (see [AppSync]). */
interface LiveUpdateListener {
    /** After every (re)connect - events may have been missed while disconnected. */
    suspend fun onConnected()

    suspend fun onEvent(event: SpaceEvent)
}

/**
 * Listens to the server's live update stream while the app is in the foreground ([start] / [stop]).
 * When the connection drops it reconnects with growing delays (1 s, 2 s, 4 s ... max 30 s), like the web
 * app. A 401/403 ends it (logged out - the AuthInterceptor already reported the 401).
 *
 * [client] must carry the AuthInterceptor; its read timeout detects dead connections (the server sends a
 * PING every 25 s).
 */
class LiveUpdates(
    private val client: OkHttpClient,
    private val url: String,
    private val hasSession: suspend () -> Boolean,
    private val listener: LiveUpdateListener,
    private val scope: CoroutineScope,
    private val minDelayMillis: Long = 1_000,
    private val maxDelayMillis: Long = 30_000,
) {
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run() {
        var delayMillis = minDelayMillis
        while (scope.isActive && hasSession()) {
            when (connectOnce { delayMillis = minDelayMillis }) {
                Outcome.REJECTED -> return
                Outcome.ENDED -> Unit
            }
            delay(delayMillis)
            delayMillis = (delayMillis * 2).coerceAtMost(maxDelayMillis)
        }
    }

    private enum class Outcome { ENDED, REJECTED }

    /** One connection; suspends until it ends. Cancelling the coroutine closes the connection. */
    private suspend fun connectOnce(onOpen: () -> Unit): Outcome = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Accept", "text/event-stream").build()
        val source = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                onOpen()
                scope.launch { listener.onConnected() }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val event = parse(data) ?: return
                if (event.isSpaceChanged) scope.launch { listener.onEvent(event) }
            }

            override fun onClosed(eventSource: EventSource) {
                if (continuation.isActive) continuation.resume(Outcome.ENDED)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val rejected = response?.code == 401 || response?.code == 403
                if (continuation.isActive) continuation.resume(if (rejected) Outcome.REJECTED else Outcome.ENDED)
            }
        })
        continuation.invokeOnCancellation { source.cancel() }
    }

    private fun parse(data: String): SpaceEvent? = try {
        StoreJson.decodeFromString(SpaceEvent.serializer(), data)
    } catch (_: SerializationException) {
        null // ignore malformed events
    } catch (_: IllegalArgumentException) {
        null
    }
}
