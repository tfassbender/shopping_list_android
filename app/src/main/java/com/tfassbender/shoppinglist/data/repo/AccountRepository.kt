package com.tfassbender.shoppinglist.data.repo

import com.tfassbender.shoppinglist.api.apis.AccountApi
import com.tfassbender.shoppinglist.api.models.ChangePasswordRequest
import com.tfassbender.shoppinglist.api.models.DisplayNameRequest
import com.tfassbender.shoppinglist.data.local.JsonFile
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.remote.apiCall
import com.tfassbender.shoppinglist.data.remote.apiCallNoContent
import com.tfassbender.shoppinglist.data.remote.map
import com.tfassbender.shoppinglist.data.remote.onSuccess
import com.tfassbender.shoppinglist.data.remote.toDomain
import com.tfassbender.shoppinglist.data.remote.toDto
import com.tfassbender.shoppinglist.domain.User
import com.tfassbender.shoppinglist.domain.UserSettings
import kotlinx.coroutines.flow.Flow

/** The logged-in user's account. The user (incl. settings) is cached, so it is available offline. */
class AccountRepository(
    private val api: AccountApi,
    private val auth: AuthRepository,
    private val me: JsonFile<CachedUser>,
) {
    /** The cached user; null before the first successful [refresh]. */
    val user: Flow<CachedUser> = me.flow

    suspend fun current(): User? = me.get().user

    suspend fun refresh(): ApiResult<User> = apiCall { api.getMe() }.map { it.toDomain() }.onSuccess { cache(it) }

    /** Replaces all personal settings (online only). */
    suspend fun updateSettings(settings: UserSettings): ApiResult<User> =
        apiCall { api.updateSettings(settings.toDto()) }.map { it.toDomain() }.onSuccess { cache(it) }

    suspend fun updateDisplayName(displayName: String): ApiResult<User> =
        apiCall { api.updateDisplayName(DisplayNameRequest(displayName.trim())) }.map { it.toDomain() }.onSuccess { cache(it) }

    /** Logs out all other devices; this device gets a new token. Errors: `WRONG_PASSWORD`, `PASSWORD_TOO_SHORT`. */
    suspend fun changePassword(currentPassword: String, newPassword: String): ApiResult<User> =
        apiCall { api.changePassword(ChangePasswordRequest(currentPassword, newPassword)) }.map { response ->
            auth.storeSession(response)
            response.user.toDomain().also { cache(it) }
        }

    /** Invalidates all sessions; this device gets a new token. */
    suspend fun logoutEverywhere(): ApiResult<Unit> =
        apiCall { api.logoutEverywhere() }.map { response ->
            auth.storeSession(response)
            Unit
        }

    /** Deletes the account; owned spaces pass to the longest-standing member. Logs out afterwards. */
    suspend fun deleteAccount(): ApiResult<Unit> =
        apiCallNoContent { api.deleteAccount() }.onSuccess { auth.logout(LogoutReason.ACCOUNT_DELETED) }

    private suspend fun cache(user: User) {
        me.set(CachedUser(user))
    }
}

/** Wrapper so "not loaded yet" can be stored as a value (the file store needs a non-null default). */
@kotlinx.serialization.Serializable
data class CachedUser(val user: User? = null)
