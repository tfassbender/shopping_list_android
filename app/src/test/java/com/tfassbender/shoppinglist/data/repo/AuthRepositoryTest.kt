package com.tfassbender.shoppinglist.data.repo

import com.tfassbender.shoppinglist.FakeCipher
import com.tfassbender.shoppinglist.MutableClock
import com.tfassbender.shoppinglist.api.apis.AccountApi
import com.tfassbender.shoppinglist.api.apis.AuthApi
import com.tfassbender.shoppinglist.api.apis.SpacesApi
import com.tfassbender.shoppinglist.data.local.JsonFile
import com.tfassbender.shoppinglist.data.local.SecretCipher
import com.tfassbender.shoppinglist.data.local.Session
import com.tfassbender.shoppinglist.data.local.SessionStore
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.remote.apiCall
import com.tfassbender.shoppinglist.jsonResponse
import com.tfassbender.shoppinglist.sessionJson
import com.tfassbender.shoppinglist.testApiClient
import com.tfassbender.shoppinglist.InMemoryDataStore
import com.tfassbender.shoppinglist.userJson
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration

class AuthRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val clock = MutableClock()
    private var hookCalls = 0

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private class Setup(val auth: AuthRepository, val account: AccountRepository, val store: SessionStore, val client: com.tfassbender.shoppinglist.api.infrastructure.ApiClient)

    private fun TestScope.setup(): Setup {
        val store = SessionStore(InMemoryDataStore(), FakeCipher())
        lateinit var auth: AuthRepository
        val client = testApiClient(server, { runBlocking { store.current()?.token } }, { auth.onUnauthorized() })
        auth = AuthRepository(client.createService(AuthApi::class.java), store, clock, backgroundScope)
        auth.addLogoutHook { hookCalls++ }
        val me = JsonFile(tmp.newFile("me.json").also { it.delete() }, CachedUser.serializer()) { CachedUser() }
        val account = AccountRepository(client.createService(AccountApi::class.java), auth, me)
        return Setup(auth, account, store, client)
    }

    @Test
    fun `login stores the session with the receive time`() = runTest {
        val s = setup()
        server.enqueue(jsonResponse(sessionJson("t1")))

        val result = s.auth.login(" a@b.de ", "secret")

        assertEquals("Anna", (result as ApiResult.Success).value.displayName)
        val session = s.store.current()!!
        assertEquals("t1", session.token)
        assertEquals(clock.now, session.receivedAt)
        assertTrue(s.auth.isLoggedIn.first())
        assertTrue(server.takeRequest().body.readUtf8().contains("\"email\":\"a@b.de\""))
    }

    @Test
    fun `register with pending verification stores no session`() = runTest {
        val s = setup()
        server.enqueue(jsonResponse(sessionJson(token = null), 201))

        val result = s.auth.register("a@b.de", "Anna", "secret123", "de")

        assertTrue((result as ApiResult.Success).value is RegisterResult.VerificationRequired)
        assertNull(s.store.current())
    }

    @Test
    fun `refresh only after a day, then the new token is stored`() = runTest {
        val s = setup()
        server.enqueue(jsonResponse(sessionJson("t1")))
        s.auth.login("a@b.de", "secret")
        server.takeRequest()

        clock.advance(Duration.ofHours(23))
        s.auth.refreshIfNeeded()
        assertEquals(1, server.requestCount)

        clock.advance(Duration.ofHours(2))
        server.enqueue(jsonResponse(sessionJson("t2")))
        s.auth.refreshIfNeeded()

        val request = server.takeRequest()
        assertEquals("/api/auth/refresh", request.path)
        assertEquals("Bearer t1", request.getHeader("Authorization"))
        assertEquals("t2", s.store.current()!!.token)
    }

    @Test
    fun `an expired session logs out without asking the server`() = runTest {
        val s = setup()
        server.enqueue(jsonResponse(sessionJson("t1", expiresAt = "2026-11-12T10:00:00Z")))
        s.auth.login("a@b.de", "secret")

        clock.advance(Duration.ofDays(2))
        s.auth.refreshIfNeeded()

        assertNull(s.store.current())
        assertEquals(1, hookCalls)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a 401 on any call logs out and runs the hooks once`() = runTest {
        val s = setup()
        server.enqueue(jsonResponse(sessionJson("t1")))
        s.auth.login("a@b.de", "secret")
        val reason = backgroundScope.async { s.auth.loggedOut.first() }
        server.enqueue(jsonResponse("""{"code":"SESSION_EXPIRED","message":"x"}""", 401))

        apiCall { s.client.createService(SpacesApi::class.java).listSpaces() }

        // The logout runs asynchronously (launched from OkHttp's thread); the event comes after the hooks.
        assertEquals(LogoutReason.SESSION_INVALID, reason.await())
        assertNull(s.store.current())
        assertEquals(1, hookCalls)
        s.auth.logout() // idempotent
        assertEquals(1, hookCalls)
    }

    @Test
    fun `change password replaces the token`() = runTest {
        val s = setup()
        server.enqueue(jsonResponse(sessionJson("t1")))
        s.auth.login("a@b.de", "secret")
        server.enqueue(jsonResponse(sessionJson("t2", user = userJson("Anna B"))))

        val result = s.account.changePassword("secret", "secret456")

        assertEquals("Anna B", (result as ApiResult.Success).value.displayName)
        assertEquals("t2", s.store.current()!!.token)
        assertEquals("Anna B", s.account.current()!!.displayName)
    }

    @Test
    fun `an undecryptable token counts as logged out`() = runTest {
        val broken = object : SecretCipher {
            override fun encrypt(plainText: String) = "garbage"
            override fun decryptOrNull(encoded: String): String? = null
        }
        val store = SessionStore(InMemoryDataStore(), broken)

        store.save(Session("t1", clock.now.plusSeconds(3600), clock.now))

        assertNull(store.current())
    }
}
