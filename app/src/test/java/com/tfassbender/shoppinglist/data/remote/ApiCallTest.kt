package com.tfassbender.shoppinglist.data.remote

import com.tfassbender.shoppinglist.api.apis.AuthApi
import com.tfassbender.shoppinglist.api.apis.ListsApi
import com.tfassbender.shoppinglist.api.apis.SpacesApi
import com.tfassbender.shoppinglist.api.apis.SyncApi
import com.tfassbender.shoppinglist.api.models.EditItemRequest
import com.tfassbender.shoppinglist.api.models.LoginRequest
import com.tfassbender.shoppinglist.api.models.NameRequest
import com.tfassbender.shoppinglist.api.models.PushRequest
import com.tfassbender.shoppinglist.api.models.SyncChangeDto
import com.tfassbender.shoppinglist.api.models.SyncChangeType
import com.tfassbender.shoppinglist.api.models.CreateInviteRequest
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Exercises the real stack (generated APIs + Retrofit + our OkHttp setup + [apiCall]) against a mock server. */
class ApiCallTest {

    private lateinit var server: MockWebServer
    private var token: String? = "jwt-token"
    private var unauthorizedCalls = 0

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun <S> service(type: Class<S>, baseUrl: String = server.url("/").toString()): S {
        val okHttp = ApiClientFactory.okHttp(baseUrl, { token }, { unauthorizedCalls++ }, logRequests = false)
        return ApiClientFactory.create(baseUrl, okHttp).createService(type)
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `session without token (email verification pending) decodes and the bearer token is sent`() = runTest {
        server.enqueue(json(USER_SESSION.replace("\"token\":\"t\",\"expiresAt\":\"2026-12-11T10:00:00Z\",", "").replace("false}", "true}")))

        val result = apiCall { service(AuthApi::class.java).login(LoginRequest("a@b.de", "secret")) }

        val session = (result as ApiResult.Success).value
        assertNull(session.token)
        assertTrue(session.verificationRequired)
        assertEquals("Anna", session.user.displayName)
        assertEquals("Bearer jwt-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `created resources (patched spec) decode`() = runTest {
        server.enqueue(json("""{"invite":{"id":"i1","createdAt":"2026-11-11T10:00:00Z","expiresAt":"2026-11-12T10:00:00Z"},"token":"raw","url":"http://x/invite/raw"}""", 201))

        val result = apiCall { service(SpacesApi::class.java).createInvite("s1", CreateInviteRequest(expiresInHours = 48)) }

        assertEquals("raw", (result as ApiResult.Success).value.token)
        assertEquals("""{"expiresInHours":48}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `no content answers succeed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = apiCallNoContent { service(SpacesApi::class.java).deleteSpace("s1") }

        assertTrue(result is ApiResult.Success)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `null fields are left out of requests (missing = unchanged for PATCH)`() = runTest {
        server.enqueue(json(LIST))

        apiCall { service(ListsApi::class.java).editItem("i1", "l1", "s1", EditItemRequest(note = "")) }

        val request = server.takeRequest()
        assertEquals("""{"note":""}""", request.body.readUtf8())
        assertEquals("/api/spaces/s1/lists/l1/items/i1", request.path)
    }

    @Test
    fun `sync push serializes changes without null fields`() = runTest {
        server.enqueue(json("""{"seq":5,"results":[{"changeId":"c1","status":"APPLIED","code":null}],"warnings":[]}"""))

        val change = SyncChangeDto(changeId = "c1", type = SyncChangeType.CHECK_ITEM, listId = "l1", itemId = "i1")
        val result = apiCall { service(SyncApi::class.java).pushChanges("s1", PushRequest(baseSeq = 4, changes = listOf(change))) }

        assertEquals(5L, (result as ApiResult.Success).value.seq)
        assertEquals(
            """{"baseSeq":4,"changes":[{"changeId":"c1","type":"CHECK_ITEM","listId":"l1","itemId":"i1"}]}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `error body is parsed into status and code`() = runTest {
        server.enqueue(json("""{"code":"OWNER_ONLY","message":"Only the owner may do that"}""", 403))

        val result = apiCall { service(SpacesApi::class.java).renameSpace("s1", NameRequest("x")) }

        assertEquals(ApiError.Server(403, "OWNER_ONLY", "Only the owner may do that"), (result as ApiResult.Failure).error)
        assertEquals(0, unauthorizedCalls)
    }

    @Test
    fun `non JSON error body still gives the status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))

        val result = apiCall { service(SpacesApi::class.java).listSpaces() }

        assertEquals(ApiError.Server(502, null, null), (result as ApiResult.Failure).error)
    }

    @Test
    fun `401 with a token reports the session as invalid`() = runTest {
        server.enqueue(json("""{"code":"SESSION_EXPIRED","message":"x"}""", 401))

        val result = apiCall { service(SpacesApi::class.java).listSpaces() }

        assertEquals(401, (result as ApiResult.Failure).error.status)
        assertEquals(1, unauthorizedCalls)
    }

    @Test
    fun `401 without a token (wrong login) does not`() = runTest {
        token = null
        server.enqueue(json("""{"code":"INVALID_CREDENTIALS","message":"x"}""", 401))

        val result = apiCall { service(AuthApi::class.java).login(LoginRequest("a@b.de", "wrong")) }

        assertEquals("INVALID_CREDENTIALS", (result as ApiResult.Failure).error.code)
        assertEquals(0, unauthorizedCalls)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `token is never sent over plain HTTP to a non-local host`() = runTest {
        val result = apiCall { service(SpacesApi::class.java, "http://example.com/").listSpaces() }

        val error = (result as ApiResult.Failure).error
        assertTrue(error is ApiError.Network && error.cause is InsecureTokenTransportException)
    }

    @Test
    fun `redirects are not followed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/"))

        val result = apiCall { service(SpacesApi::class.java).listSpaces() }

        assertEquals(302, (result as ApiResult.Failure).error.status)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `server not reachable is a network error`() = runTest {
        val url = server.url("/").toString()
        server.shutdown()

        val result = apiCall { service(SpacesApi::class.java, url).listSpaces() }

        assertTrue((result as ApiResult.Failure).error.isNetwork)
        assertFalse(unauthorizedCalls > 0)
    }

    companion object {
        const val USER_SESSION =
            """{"user":{"id":"u1","email":"a@b.de","displayName":"Anna","emailVerified":true,""" +
                """"settings":{"language":"de","theme":"SYSTEM","checkedItemsToEnd":true,"ignoreMergeWarnings":false}},""" +
                """"token":"t","expiresAt":"2026-12-11T10:00:00Z","verificationRequired":false}"""
        const val LIST =
            """{"id":"l1","spaceId":"s1","name":"Groceries","createdAt":"2026-11-11T10:00:00Z","seq":3,"items":[]}"""
    }
}
