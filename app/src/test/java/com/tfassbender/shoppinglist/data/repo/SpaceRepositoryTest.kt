package com.tfassbender.shoppinglist.data.repo

import com.tfassbender.shoppinglist.api.apis.SpacesApi
import com.tfassbender.shoppinglist.data.local.LocalStore
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.remote.isNetwork
import com.tfassbender.shoppinglist.domain.Role
import com.tfassbender.shoppinglist.jsonResponse
import com.tfassbender.shoppinglist.testApiClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpaceRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var repo: SpaceRepository
    private val removed = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val store = LocalStore(File(tmp.root, "store"))
        repo = SpaceRepository(testApiClient(server, { "t" }).createService(SpacesApi::class.java), store.spaces)
        repo.addSpaceRemovedListener { removed += it }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun space(id: String, role: String = "OWNER", created: String = "2026-11-0${id.last()}T10:00:00Z") =
        """{"id":"$id","name":"Space $id","myRole":"$role","memberCount":1,"createdAt":"$created"}"""

    @Test
    fun `refresh caches spaces in the server's order and reports vanished ones`() = runTest {
        server.enqueue(jsonResponse("[${space("s2")},${space("s1")}]"))
        repo.refresh()
        assertEquals(listOf("s2", "s1"), repo.cached().map { it.id })

        server.enqueue(jsonResponse("[${space("s2")}]"))
        repo.refresh()

        assertEquals(listOf("s2"), repo.cached().map { it.id })
        assertEquals(listOf("s1"), removed)
    }

    @Test
    fun `reorder saves the order and restores the previous one on failure`() = runTest {
        server.enqueue(jsonResponse("[${space("s1")},${space("s2")},${space("s3")}]"))
        repo.refresh()
        server.takeRequest()

        server.enqueue(jsonResponse("[${space("s3")},${space("s1")},${space("s2")}]"))
        repo.reorder(listOf("s3", "s1", "s2"))
        val request = server.takeRequest()
        assertEquals("PUT /api/spaces/order", "${request.method} ${request.path}")
        assertTrue(request.body.readUtf8().contains("\"spaceIds\":[\"s3\",\"s1\",\"s2\"]"))
        assertEquals(listOf("s3", "s1", "s2"), repo.cached().map { it.id })

        server.enqueue(jsonResponse("""{"code":"X","message":"x"}""", 500))
        assertTrue(repo.reorder(listOf("s2", "s3", "s1")) is ApiResult.Failure)
        assertEquals(listOf("s3", "s1", "s2"), repo.cached().map { it.id })
    }

    @Test
    fun `create keeps the order and puts a new space last`() = runTest {
        server.enqueue(jsonResponse("[${space("s2")},${space("s1")}]"))
        repo.refresh()
        server.enqueue(jsonResponse(space("s3"), 201))
        repo.create("New")
        server.enqueue(jsonResponse(space("s2", role = "MEMBER")))
        repo.transferOwnership("s2", "u2")

        assertEquals(listOf("s2", "s1", "s3"), repo.cached().map { it.id })
    }

    @Test
    fun `create, accept invite and transfer ownership update the cache`() = runTest {
        server.enqueue(jsonResponse(space("s1"), 201))
        repo.create(" Home ")
        assertTrue(server.takeRequest().body.readUtf8().contains("\"name\":\"Home\""))

        server.enqueue(jsonResponse(space("s2", role = "MEMBER")))
        repo.acceptInvite("tok")
        assertEquals("/api/invites/tok/accept", server.takeRequest().path)

        server.enqueue(jsonResponse(space("s1", role = "MEMBER")))
        repo.transferOwnership("s1", "u2")

        assertEquals(listOf(Role.MEMBER, Role.MEMBER), repo.cached().map { it.myRole })
    }

    @Test
    fun `leaving removes the space and its local data`() = runTest {
        server.enqueue(jsonResponse("[${space("s1")}]"))
        repo.refresh()
        server.enqueue(MockResponse().setResponseCode(204))

        repo.leave("s1")

        assertEquals(emptyList<String>(), repo.cached().map { it.id })
        assertEquals(listOf("s1"), removed)
        server.takeRequest() // GET /api/spaces
        assertEquals("/api/spaces/s1/leave", server.takeRequest().path)
    }

    @Test
    fun `404 on members means the space is gone`() = runTest {
        server.enqueue(jsonResponse("[${space("s1")}]"))
        repo.refresh()
        server.enqueue(jsonResponse("""{"code":"SPACE_NOT_FOUND","message":"x"}""", 404))

        val result = repo.members("s1")

        assertEquals("SPACE_NOT_FOUND", ((result as ApiResult.Failure).error as com.tfassbender.shoppinglist.data.remote.ApiError.Server).code)
        assertEquals(listOf("s1"), removed)
    }

    @Test
    fun `invite creation returns the link once`() = runTest {
        server.enqueue(jsonResponse("""{"invite":{"id":"i1","createdAt":"2026-11-11T10:00:00Z","expiresAt":"2026-11-12T10:00:00Z"},"token":"raw","url":"http://x/invite/raw"}""", 201))

        val invite = (repo.createInvite("s1", 24) as ApiResult.Success).value

        assertEquals("http://x/invite/raw", invite.url)
        assertEquals("/api/spaces/s1/invites", server.takeRequest().path)
    }

    @Test
    fun `offline is reported as network error and keeps the cache`() = runTest {
        server.enqueue(jsonResponse("[${space("s1")}]"))
        repo.refresh()
        server.shutdown()

        val result = repo.refresh()

        assertTrue((result as ApiResult.Failure).error.isNetwork)
        assertEquals(listOf("s1"), repo.cached().map { it.id })
    }
}
