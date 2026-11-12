package com.tfassbender.shoppinglist.data.remote

import com.tfassbender.shoppinglist.api.apis.SystemApi
import com.tfassbender.shoppinglist.jsonResponse
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerUrlTest {

    private fun valid(input: String, plain: Set<String> = emptySet()) =
        (parseServerUrl(input, plain) as ServerUrlInput.Valid).url.toString()

    @Test
    fun `adds https and the trailing slash, keeps a base path`() {
        assertEquals("https://shop.example.org/", valid("shop.example.org"))
        assertEquals("https://shop.example.org/", valid("  https://shop.example.org/  "))
        assertEquals("https://example.org:8443/shopping/", valid("example.org:8443/shopping"))
        assertEquals("https://example.org/shopping/", valid("https://example.org/shopping/?x=1#top"))
    }

    @Test
    fun `plain http only for the allowed local hosts`() {
        assertEquals(ServerUrlInput.NotHttps, parseServerUrl("http://shop.example.org", emptySet()))
        assertEquals(ServerUrlInput.NotHttps, parseServerUrl("http://10.0.2.2:4723", LOCAL_HOSTS))
        assertEquals("http://10.0.2.2:4723/", valid("http://10.0.2.2:4723", LOCAL_HOSTS + EMULATOR_HOST))
    }

    @Test
    fun `rejects what isn't a server address`() {
        listOf("", "   ", "not a url", "ftp://example.org", "https://", "https://user:pw@example.org").forEach {
            assertEquals(it, ServerUrlInput.Invalid, parseServerUrl(it, emptySet()))
        }
    }

    @Test
    fun `display address has no trailing slash`() {
        assertEquals("https://example.org/shopping", "https://example.org/shopping/".toHttpUrl().displayAddress())
    }
}

/** The configurable server: requests built for the placeholder go to the server configured right then. */
class ServerRewriteTest {

    private lateinit var first: MockWebServer
    private lateinit var second: MockWebServer
    private var current: HttpUrl? = null
    private var unauthorized = 0

    @Before
    fun setUp() {
        first = MockWebServer().apply { start() }
        second = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        first.shutdown()
        second.shutdown()
    }

    private fun api(): SystemApi {
        val okHttp = ApiClientFactory.okHttp(SERVER_PLACEHOLDER, { "tok" }, { unauthorized++ }, logRequests = false, server = { current!! })
        return ApiClientFactory.create(SERVER_PLACEHOLDER, okHttp).createService(SystemApi::class.java)
    }

    @Test
    fun `requests follow the configured server, including its base path, with the token`() = runTest {
        val api = api()
        current = first.url("/")
        first.enqueue(jsonResponse("""{"status":"UP","version":"1"}"""))
        assertEquals("1", (apiCall { api.getHealth() } as ApiResult.Success).value.version)
        val request = first.takeRequest()
        assertEquals("/api/health", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))

        current = second.url("/shopping/")
        second.enqueue(jsonResponse("""{"status":"UP","version":"2"}"""))
        assertEquals("2", (apiCall { api.getHealth() } as ApiResult.Success).value.version)
        assertEquals("/shopping/api/health", second.takeRequest().path)
        assertEquals(1, first.requestCount)
    }

    @Test
    fun `a request to another host than the configured server gets no token`() = runTest {
        // Both mock servers run on this machine - two names for it make two different hosts.
        current = first.url("/").newBuilder().host("localhost").build()
        val okHttp = ApiClientFactory.okHttp(SERVER_PLACEHOLDER, { "tok" }, { }, logRequests = false, server = { current!! })
            .build()
        second.enqueue(jsonResponse("{}"))
        val other = second.url("/x").newBuilder().host("127.0.0.1").build()
        okHttp.newCall(okhttp3.Request.Builder().url(other).build()).execute().close()
        assertNull(second.takeRequest().getHeader("Authorization"))
        assertTrue(first.requestCount == 0)
    }
}
