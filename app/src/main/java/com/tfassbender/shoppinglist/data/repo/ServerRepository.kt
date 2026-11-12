package com.tfassbender.shoppinglist.data.repo

import com.tfassbender.shoppinglist.api.apis.SystemApi
import com.tfassbender.shoppinglist.data.local.ServerConfig
import com.tfassbender.shoppinglist.data.remote.ApiError
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.remote.ServerUrlInput
import com.tfassbender.shoppinglist.data.remote.apiCall
import com.tfassbender.shoppinglist.data.remote.parseServerUrl
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/** Why a server address can't be used. */
enum class ServerProblem { INVALID, NOT_HTTPS, UNREACHABLE, NOT_A_SHOPPING_LIST_SERVER }

/**
 * Which server the app talks to. Changing it logs out first: the session, the cached spaces and the
 * offline lists all belong to the old server.
 *
 * @param systemApiFor a client for another server, without a token - to check an address before using it.
 */
class ServerRepository(
    private val config: ServerConfig,
    private val auth: AuthRepository,
    private val plainHttpHosts: Set<String>,
    private val systemApiFor: (HttpUrl) -> SystemApi,
) {
    val url: StateFlow<HttpUrl> = config.url
    val defaultUrl: HttpUrl get() = config.defaultUrl

    /**
     * Checks a typed address (format, then whether a shopping list server answers there) and switches to
     * it: logged out, then the new server. Returns null on success. The same address as now is a no-op.
     */
    suspend fun change(input: String): ServerProblem? {
        val url = when (val parsed = parseServerUrl(input, plainHttpHosts)) {
            is ServerUrlInput.Valid -> parsed.url
            ServerUrlInput.Invalid -> return ServerProblem.INVALID
            ServerUrlInput.NotHttps -> return ServerProblem.NOT_HTTPS
        }
        if (url == config.baseUrl()) return null
        when (val health = apiCall { systemApiFor(url).getHealth() }) {
            is ApiResult.Failure -> return if (health.error is ApiError.Network) ServerProblem.UNREACHABLE else ServerProblem.NOT_A_SHOPPING_LIST_SERVER
            is ApiResult.Success -> Unit
        }
        switchTo(url)
        return null
    }

    /** Back to the app's own server (no check: it's the address the app was built for). */
    suspend fun reset() {
        if (config.baseUrl() != config.defaultUrl) switchTo(null)
    }

    // NonCancellable: logging out closes the page that started this - the switch must still finish.
    private suspend fun switchTo(url: HttpUrl?) = withContext(NonCancellable) {
        auth.logout()
        config.set(url)
    }
}
