package com.tfassbender.shoppinglist.data.remote

import com.tfassbender.shoppinglist.api.infrastructure.ApiClient
import com.tfassbender.shoppinglist.api.infrastructure.Serializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Supplies the current session token; called on OkHttp's worker thread for every request. */
fun interface TokenProvider {
    fun token(): String?
}

/** Told when the server rejected the session token (401 on a request that carried it). */
fun interface UnauthorizedListener {
    fun onUnauthorized()
}

/** Thrown instead of sending the token somewhere unsafe - reported like a network error. */
class InsecureTokenTransportException(url: String) :
    IOException("Refusing to send the session token to $url")

/** Hosts that may be reached over plain HTTP: this machine (unit tests); debug builds add the emulator host. */
val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")
const val EMULATOR_HOST = "10.0.2.2"

/**
 * Adds `Authorization: Bearer <token>` to requests to the API host. Safeguards (see CLAUDE.md, "Security"):
 * the token only goes to [apiHost], and only over HTTPS or to one of [plainHttpHosts] (local dev server).
 * A 401 on a request that carried the token means the session is invalid → [unauthorizedListener].
 */
class AuthInterceptor(
    private val apiHost: () -> String,
    private val tokenProvider: TokenProvider,
    private val unauthorizedListener: UnauthorizedListener,
    private val plainHttpHosts: Set<String> = LOCAL_HOSTS,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val token = tokenProvider.token()?.takeIf { it.isNotBlank() && url.host == apiHost() }
        if (token == null) return chain.proceed(chain.request())
        if (!url.isHttps && url.host !in plainHttpHosts) {
            throw InsecureTokenTransportException(url.newBuilder().query(null).build().toString())
        }
        val response = chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
        if (response.code == 401) unauthorizedListener.onUnauthorized()
        return response
    }
}

object ApiClientFactory {

    /**
     * Builds the shared OkHttp client (auth, timeouts, logging). Also used for the live update stream,
     * so it has the same safeguards. With [server], requests built for [baseUrl] (then usually
     * [SERVER_PLACEHOLDER]) go to the server it names at the time of the request, and the token only goes
     * to that server's host.
     */
    fun okHttp(
        baseUrl: String,
        tokenProvider: TokenProvider,
        unauthorizedListener: UnauthorizedListener,
        logRequests: Boolean,
        plainHttpHosts: Set<String> = LOCAL_HOSTS,
        server: ServerProvider? = null,
    ): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .apply {
            val base = baseUrl.toHttpUrl()
            // First, so the token check below sees the real destination.
            if (server != null) addInterceptor(ServerRewriteInterceptor(base, server))
            val apiHost: () -> String = if (server != null) ({ server.baseUrl().host }) else ({ base.host })
            addInterceptor(AuthInterceptor(apiHost, tokenProvider, unauthorizedListener, plainHttpHosts))
        }
        // The API never redirects. Following one would bypass the checks above (they only see the original
        // request) and could carry the token elsewhere - a 3xx is reported as an error instead.
        .followRedirects(false)
        .followSslRedirects(false)
        .apply {
            if (logRequests) {
                // BASIC = method, URL, status, duration. Never log headers: they contain the token.
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }

    /** Builds the generated [ApiClient] on top of [okHttp]. */
    fun create(baseUrl: String, okHttp: OkHttpClient.Builder): ApiClient {
        configureJson()
        return ApiClient(baseUrl = baseUrl, okHttpClientBuilder = okHttp)
    }

    private var jsonConfigured = false

    /**
     * The server leaves out null fields in some answers (e.g. `token` after registering while the email
     * still needs verification) although the spec marks them required; `explicitNulls = false` decodes a
     * missing nullable field as null. It also leaves out null fields in requests, which the server treats
     * like null ("" clears an optional field in PATCH / EDIT_ITEM).
     */
    @Synchronized
    private fun configureJson() {
        if (jsonConfigured) return
        Serializer.kotlinxSerializationJsonConfiguration = { explicitNulls = false }
        jsonConfigured = true
    }
}
