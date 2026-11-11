package com.tfassbender.shoppinglist

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tfassbender.shoppinglist.data.local.TokenCipher
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.repo.RegisterResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * What only a device can check: the Keystore encryption and the app's real wiring (AppContainer with
 * DataStore, files, WorkManager) against the local dev server at 10.0.2.2:4720 (skipped if not running).
 * Run: `./gradlew connectedDebugAndroidTest` with an emulator.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShoppingListApp

    @Test
    fun tokenCipherRoundTripWithTheKeystore() {
        val cipher = TokenCipher()
        val encrypted = cipher.encrypt("secret-jwt")

        assertNotEquals("secret-jwt", encrypted)
        assertFalse(encrypted.contains("secret"))
        assertEquals("secret-jwt", cipher.decryptOrNull(encrypted))
        assertNull(cipher.decryptOrNull("not-encrypted"))
    }

    @Test
    fun registerSyncAListAndLogOut() = runBlocking {
        assumeTrue("No dev server at ${BuildConfig.API_BASE_URL}", serverReachable())
        val container = app.container
        val email = "android-device-${UUID.randomUUID()}@example.com"

        val registered = container.auth.register(email, "Device Test", "device-secret", "de")
        assertTrue("$registered", (registered as ApiResult.Success).value is RegisterResult.LoggedIn)
        try {
            val space = (container.spaces.create("Device") as ApiResult.Success).value
            val listId = container.lists.createList(space.id, "Einkauf").getOrThrow()
            container.lists.addItem(space.id, listId, "Milch").getOrThrow()
            container.syncEngine.sync(space.id)

            assertEquals(0, container.syncEngine.pendingCount.first())
            assertEquals(listOf("Milch"), container.lists.list(space.id, listId).first()!!.items.map { it.name })
            assertTrue(container.catalog.catalog(space.id).first()!!.categories.isNotEmpty()) // German defaults
            assertTrue(File(app.filesDir, "store/spaces/${space.id}.json").exists())
        } finally {
            container.account.deleteAccount() // logs out
        }

        assertNull(container.auth.currentSession())
        assertFalse(File(app.filesDir, "store").exists())
    }

    private fun serverReachable(): Boolean = runCatching {
        OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(BuildConfig.API_BASE_URL + "api/health").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)
}
