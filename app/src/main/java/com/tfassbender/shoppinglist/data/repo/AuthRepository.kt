package com.tfassbender.shoppinglist.data.repo

import com.tfassbender.shoppinglist.api.apis.AuthApi
import com.tfassbender.shoppinglist.api.models.EmailRequest
import com.tfassbender.shoppinglist.api.models.LoginRequest
import com.tfassbender.shoppinglist.api.models.RegisterRequest
import com.tfassbender.shoppinglist.api.models.SessionResponse
import com.tfassbender.shoppinglist.data.local.Session
import com.tfassbender.shoppinglist.data.local.SessionStore
import com.tfassbender.shoppinglist.data.remote.ApiError
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.remote.apiCall
import com.tfassbender.shoppinglist.data.remote.apiCallNoContent
import com.tfassbender.shoppinglist.data.remote.map
import com.tfassbender.shoppinglist.data.remote.toDomain
import com.tfassbender.shoppinglist.domain.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

data class AuthConfig(val emailEnabled: Boolean, val minPasswordLength: Int)

sealed interface RegisterResult {
    data class LoggedIn(val user: User) : RegisterResult

    /** The account exists, but the email must be verified (link in the email) before logging in. */
    data class VerificationRequired(val user: User) : RegisterResult
}

enum class LogoutReason {
    /** The user logged out. */
    USER,

    /** The server rejected the token (expired, password changed elsewhere, "log out everywhere"). */
    SESSION_INVALID,

    ACCOUNT_DELETED,
}

/** Clears everything stored for the logged-in user (local data, sync state, scheduled work). */
fun interface LogoutHook {
    suspend fun onLoggedOut()
}

/**
 * Login state of the app: register, login, sliding session refresh, logout. Account actions that return
 * a new token (change password, log out everywhere) go through [storeSession].
 *
 * Verifying the email and resetting the password happen in the web app (the emailed links point there).
 */
class AuthRepository(
    private val api: AuthApi,
    private val sessionStore: SessionStore,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val logoutMutex = Mutex()
    private val logoutHooks = mutableListOf<LogoutHook>()
    private val loggedOutEvents = MutableSharedFlow<LogoutReason>(extraBufferCapacity = 1)

    val isLoggedIn: Flow<Boolean> = sessionStore.session.map { it != null }

    /** Emits when the user is logged out - the UI goes back to the login screen and may show the reason. */
    val loggedOut: SharedFlow<LogoutReason> = loggedOutEvents.asSharedFlow()

    fun addLogoutHook(hook: LogoutHook) {
        logoutHooks += hook
    }

    suspend fun currentSession(): Session? = sessionStore.current()

    suspend fun config(): ApiResult<AuthConfig> =
        apiCall { api.getAuthConfig() }.map { AuthConfig(it.emailEnabled, it.minPasswordLength) }

    /** [language] (`en`/`de`) also decides the language of the default categories of new spaces. */
    suspend fun register(email: String, displayName: String, password: String, language: String): ApiResult<RegisterResult> =
        apiCall { api.register(RegisterRequest(email = email.trim(), displayName = displayName.trim(), password = password, language = language)) }
            .map { response ->
                val user = response.user.toDomain()
                if (storeSession(response)) RegisterResult.LoggedIn(user) else RegisterResult.VerificationRequired(user)
            }

    /** Errors: `INVALID_CREDENTIALS`, `EMAIL_NOT_VERIFIED`. */
    suspend fun login(email: String, password: String): ApiResult<User> =
        apiCall { api.login(LoginRequest(email = email.trim(), password = password)) }.map { response ->
            storeSession(response)
            response.user.toDomain()
        }

    /** Always succeeds on the server (no account enumeration); 503 `EMAIL_DISABLED` without mail server. */
    suspend fun resendVerification(email: String): ApiResult<Unit> =
        apiCallNoContent { api.resendVerification(EmailRequest(email.trim())) }

    suspend fun forgotPassword(email: String): ApiResult<Unit> =
        apiCallNoContent { api.forgotPassword(EmailRequest(email.trim())) }

    /**
     * Sliding session: exchanges the token for a fresh one when it is older than a day (call on app start /
     * foreground). An expired token logs out; network problems are ignored (next time).
     */
    suspend fun refreshIfNeeded() {
        val session = sessionStore.current() ?: return
        val now = clock.instant()
        if (session.isExpired(now)) {
            logout(LogoutReason.SESSION_INVALID)
            return
        }
        if (!session.shouldRefresh(now)) return
        // A 401 here is handled by the AuthInterceptor → onUnauthorized().
        val result = apiCall { api.refreshSession() }
        if (result is ApiResult.Success) storeSession(result.value)
    }

    /** Stores the token of a session answer; false if it has none (email verification pending). */
    suspend fun storeSession(response: SessionResponse): Boolean {
        val token = response.token ?: return false
        val expiresAt = response.expiresAt?.toInstant() ?: return false
        sessionStore.save(Session(token, expiresAt, clock.instant()))
        return true
    }

    /** Called by the AuthInterceptor when the server rejected our token (any thread). */
    fun onUnauthorized() {
        scope.launch { logout(LogoutReason.SESSION_INVALID) }
    }

    /** Forgets the session and all local data of the user. Idempotent. */
    suspend fun logout(reason: LogoutReason = LogoutReason.USER) = logoutMutex.withLock {
        if (sessionStore.current() == null) return@withLock
        sessionStore.clear()
        logoutHooks.forEach { it.onLoggedOut() }
        loggedOutEvents.emit(reason)
    }
}

/** True if the call failed because the device is offline (or the server unreachable). */
val ApiResult<*>.isOffline: Boolean get() = this is ApiResult.Failure && error is ApiError.Network
