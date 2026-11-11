package com.tfassbender.shoppinglist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.tfassbender.shoppinglist.data.local.SecretCipher
import com.tfassbender.shoppinglist.data.remote.ApiClientFactory
import com.tfassbender.shoppinglist.data.remote.TokenProvider
import com.tfassbender.shoppinglist.data.remote.UnauthorizedListener
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Reversible "encryption" for JVM tests (the real TokenCipher needs the Android Keystore). */
class FakeCipher : SecretCipher {
    override fun encrypt(plainText: String) = "enc:" + plainText.reversed()
    override fun decryptOrNull(encoded: String) = if (encoded.startsWith("enc:")) encoded.removePrefix("enc:").reversed() else null
}

/** A clock the test moves forward. */
class MutableClock(var now: Instant = Instant.parse("2026-11-11T10:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

/**
 * In-memory DataStore for JVM tests. The file-based one fails on Windows test machines (it can't rename
 * over its still-open file); on Android it works, and DataStore itself isn't what we test here.
 */
class InMemoryDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock { transform(state.value).also { state.value = it } }
}

/** The generated API client against a MockWebServer, with the real OkHttp setup. */
fun testApiClient(server: MockWebServer, tokenProvider: TokenProvider, unauthorized: UnauthorizedListener = UnauthorizedListener { }) =
    server.url("/").toString().let { baseUrl ->
        ApiClientFactory.create(baseUrl, ApiClientFactory.okHttp(baseUrl, tokenProvider, unauthorized, logRequests = false))
    }

fun jsonResponse(body: String, code: Int = 200): MockResponse =
    MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

fun userJson(name: String = "Anna") =
    """{"id":"u1","email":"a@b.de","displayName":"$name","emailVerified":true,""" +
        """"settings":{"language":"de","theme":"SYSTEM","checkedItemsToEnd":true,"ignoreMergeWarnings":false}}"""

fun sessionJson(token: String? = "t1", expiresAt: String = "2026-12-11T10:00:00Z", user: String = userJson()) =
    if (token == null) """{"user":$user,"verificationRequired":true}"""
    else """{"user":$user,"token":"$token","expiresAt":"$expiresAt","verificationRequired":false}"""
