package com.tfassbender.shoppinglist.data.repo

import com.tfassbender.shoppinglist.FakeCipher
import com.tfassbender.shoppinglist.InMemoryDataStore
import com.tfassbender.shoppinglist.MutableClock
import com.tfassbender.shoppinglist.api.apis.AuthApi
import com.tfassbender.shoppinglist.api.apis.SystemApi
import com.tfassbender.shoppinglist.data.local.ServerConfig
import com.tfassbender.shoppinglist.data.local.Session
import com.tfassbender.shoppinglist.data.local.SessionStore
import com.tfassbender.shoppinglist.data.remote.ApiClientFactory
import com.tfassbender.shoppinglist.data.remote.LOCAL_HOSTS
import com.tfassbender.shoppinglist.jsonResponse
import com.tfassbender.shoppinglist.testApiClient
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

class ServerRepositoryTest {

    private lateinit var server: MockWebServer
    private val dataStore = InMemoryDataStore()
    private val sessions = SessionStore(InMemoryDataStore(), FakeCipher())
    private var loggedOut = 0

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun TestScope.setup(): Pair<ServerRepository, ServerConfig> {
        sessions.save(Session("tok", Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")))
        val auth = AuthRepository(testApiClient(server, { null }).createService(AuthApi::class.java), sessions, MutableClock(), backgroundScope)
        auth.addLogoutHook { loggedOut++ }
        val config = ServerConfig(dataStore, "https://default.example.org/")
        val repo = ServerRepository(config, auth, LOCAL_HOSTS) { url ->
            ApiClientFactory.create(url.toString(), ApiClientFactory.okHttp(url.toString(), { null }, { }, logRequests = false))
                .createService(SystemApi::class.java)
        }
        return repo to config
    }

    @Test
    fun `a shopping list server answering switches to it and logs out`() = runTest {
        val (repo, config) = setup()
        server.enqueue(jsonResponse("""{"status":"UP","version":"1.0"}"""))

        assertNull(repo.change(server.url("/").toString()))

        assertEquals("/api/health", server.takeRequest().path)
        assertEquals(server.url("/"), config.baseUrl())
        assertNull(sessions.current())
        assertEquals(1, loggedOut)
        // Survives a restart (a new ServerConfig reads the stored address).
        assertEquals(server.url("/"), ServerConfig(dataStore, "https://default.example.org/").baseUrl())
    }

    @Test
    fun `wrong addresses and other servers are refused, the session stays`() = runTest {
        val (repo, config) = setup()
        assertEquals(ServerProblem.INVALID, repo.change("not a url"))
        assertEquals(ServerProblem.NOT_HTTPS, repo.change("http://shop.example.org"))

        server.enqueue(MockResponse().setResponseCode(404).setBody("<html>nope</html>"))
        assertEquals(ServerProblem.NOT_A_SHOPPING_LIST_SERVER, repo.change(server.url("/").toString()))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>a web page</html>"))
        assertEquals(ServerProblem.NOT_A_SHOPPING_LIST_SERVER, repo.change(server.url("/").toString()))

        val closed = server.url("/").toString()
        server.shutdown()
        assertEquals(ServerProblem.UNREACHABLE, repo.change(closed))

        assertEquals("https://default.example.org/", config.baseUrl().toString())
        assertNotNull(sessions.current())
        assertEquals(0, loggedOut)
    }

    @Test
    fun `the same address is no change, reset goes back to the default`() = runTest {
        val (repo, config) = setup()
        assertNull(repo.change("default.example.org"))
        assertEquals(0, loggedOut)
        assertEquals(0, server.requestCount)

        config.set(server.url("/"))
        repo.reset()
        assertEquals("https://default.example.org/", config.baseUrl().toString())
        assertEquals(1, loggedOut)
    }
}
