package com.tfassbender.shoppinglist.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/** The server the app talks to - configurable in the app (see `ServerConfig`); read for every request. */
fun interface ServerProvider {
    fun baseUrl(): HttpUrl
}

/**
 * The base URL the generated client and the live update stream are built with when the server is
 * configurable: [ServerRewriteInterceptor] points every request at the current server. `.invalid` never
 * resolves, so a request that somehow skipped the rewrite fails instead of going anywhere.
 */
const val SERVER_PLACEHOLDER = "https://server.invalid/"

/** Points requests built for [clientBase] at the current server (scheme, host, port and base path). */
class ServerRewriteInterceptor(private val clientBase: HttpUrl, private val server: ServerProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val prefix = clientBase.encodedPath
        val built = url.scheme == clientBase.scheme && url.host == clientBase.host && url.port == clientBase.port
        if (!built || !url.encodedPath.startsWith(prefix)) return chain.proceed(chain.request())
        val target = server.baseUrl()
        val rewritten = url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .encodedPath(target.encodedPath.trimEnd('/') + "/" + url.encodedPath.removePrefix(prefix))
            .build()
        return chain.proceed(chain.request().newBuilder().url(rewritten).build())
    }
}

/** What the user typed as server address, checked. */
sealed interface ServerUrlInput {
    data class Valid(val url: HttpUrl) : ServerUrlInput
    data object Invalid : ServerUrlInput
    /** Plain HTTP to a host that isn't allowed (only the local dev server in debug builds). */
    data object NotHttps : ServerUrlInput
}

/**
 * Turns a typed server address into the base URL of the API: `https://` is added when no scheme is given,
 * query and fragment are dropped and the path ends with `/` (a server may live below a path, e.g.
 * `https://example.org/shopping/`). Plain HTTP only for [plainHttpHosts] - the session token must never
 * travel unencrypted (see CLAUDE.md, "Security").
 */
fun parseServerUrl(input: String, plainHttpHosts: Set<String>): ServerUrlInput {
    val text = input.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return ServerUrlInput.Invalid
    val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)) text else "https://$text"
    val url = withScheme.toHttpUrlOrNull() ?: return ServerUrlInput.Invalid
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return ServerUrlInput.Invalid
    if (!url.isHttps && url.host !in plainHttpHosts) return ServerUrlInput.NotHttps
    val path = url.encodedPath.let { if (it.endsWith("/")) it else "$it/" }
    return ServerUrlInput.Valid(url.newBuilder().encodedPath(path).query(null).fragment(null).build())
}

/** The address as shown to the user: without the trailing `/` of the base URL. */
fun HttpUrl.displayAddress(): String = toString().removeSuffix("/")

/** Short form for one line (login page): host, plus the port when it isn't the scheme's default. */
fun HttpUrl.displayHost(): String = if (port == HttpUrl.defaultPort(scheme)) host else "$host:$port"
