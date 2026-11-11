package com.tfassbender.shoppinglist.data.sync

import com.tfassbender.shoppinglist.data.remote.ApiClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

class LiveUpdatesTest {

    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val events: MutableList<SpaceEvent> = Collections.synchronizedList(mutableListOf())
    @Volatile private var connects = 0
    private var unauthorized = 0

    private val listener = object : LiveUpdateListener {
        override suspend fun onConnected() {
            connects++
        }

        override suspend fun onEvent(event: SpaceEvent) {
            events += event
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun liveUpdates(): LiveUpdates {
        val baseUrl = server.url("/").toString()
        val client = ApiClientFactory.okHttp(baseUrl, { "jwt" }, { unauthorized++ }, logRequests = false).build()
        return LiveUpdates(client, baseUrl + "api/events", { true }, listener, scope, minDelayMillis = 50, maxDelayMillis = 200)
    }

    private fun stream(vararg payloads: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(payloads.joinToString("") { "data:$it\n\n" })

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun `space changes are delivered, pings and garbage ignored, with the auth header`() = runBlocking {
        server.enqueue(
            stream(
                """{"type":"PING"}""",
                """{"type":"SPACE_CHANGED","spaceId":"s1","seq":42}""",
                "not json",
                """{"type":"SPACE_CHANGED","spaceId":"s2","seq":7,"future":1}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(401)) // end the test: no reconnect after this
        val updates = liveUpdates()

        updates.start()
        waitFor { events.size == 2 }

        assertEquals(listOf(SpaceEvent("SPACE_CHANGED", "s1", 42), SpaceEvent("SPACE_CHANGED", "s2", 7)), events.toList())
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/events", request.path)
        assertEquals("Bearer jwt", request.getHeader("Authorization"))
        assertEquals("text/event-stream", request.getHeader("Accept"))
        updates.stop()
    }

    @Test
    fun `reconnects after the stream ends and reports every connect`() = runBlocking {
        server.enqueue(stream("""{"type":"PING"}"""))
        server.enqueue(stream("""{"type":"SPACE_CHANGED","spaceId":"s1","seq":1}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        val updates = liveUpdates()

        updates.start()
        waitFor { server.requestCount >= 3 }
        waitFor { connects >= 2 }

        assertEquals(2, connects)
        assertEquals(1, events.size)
        updates.stop()
    }

    @Test
    fun `a rejected session ends the stream for good`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val updates = liveUpdates()

        updates.start()
        waitFor { server.requestCount >= 1 }
        delay(400) // longer than the max backoff: no reconnect attempt

        assertEquals(1, server.requestCount)
        assertEquals(1, unauthorized)
        assertEquals(0, connects)
    }

    @Test
    fun `server errors are retried with backoff`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(403))
        val updates = liveUpdates()

        updates.start()
        waitFor { server.requestCount >= 3 }
        delay(300)

        assertEquals(3, server.requestCount)
        updates.stop()
    }
}
