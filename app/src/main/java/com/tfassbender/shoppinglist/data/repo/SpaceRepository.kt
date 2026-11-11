package com.tfassbender.shoppinglist.data.repo

import com.tfassbender.shoppinglist.api.apis.SpacesApi
import com.tfassbender.shoppinglist.api.models.CreateInviteRequest
import com.tfassbender.shoppinglist.api.models.NameRequest
import com.tfassbender.shoppinglist.api.models.TransferOwnershipRequest
import com.tfassbender.shoppinglist.data.local.JsonFile
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.remote.apiCall
import com.tfassbender.shoppinglist.data.remote.apiCallNoContent
import com.tfassbender.shoppinglist.data.remote.map
import com.tfassbender.shoppinglist.data.remote.onSuccess
import com.tfassbender.shoppinglist.data.remote.status
import com.tfassbender.shoppinglist.data.remote.toDomain
import com.tfassbender.shoppinglist.domain.CreatedInvite
import com.tfassbender.shoppinglist.domain.Invite
import com.tfassbender.shoppinglist.domain.InvitePreview
import com.tfassbender.shoppinglist.domain.Member
import com.tfassbender.shoppinglist.domain.Space
import kotlinx.coroutines.flow.Flow

/** Told when a space is gone for this user (deleted, left, removed) - its local data must be dropped. */
fun interface SpaceRemovedListener {
    suspend fun onSpaceRemoved(spaceId: String)
}

/**
 * Spaces with their members and invites. The space list is cached (available offline); everything else
 * here needs a connection (offline → `ApiError.Network`).
 *
 * Owner-only actions (rename, delete, invites, remove member, transfer ownership) answer `403 OWNER_ONLY`
 * for members - the UI should only offer them when [Space.isOwner].
 */
class SpaceRepository(
    private val api: SpacesApi,
    private val cache: JsonFile<List<Space>>,
) {
    private val removedListeners = mutableListOf<SpaceRemovedListener>()

    /** My spaces (cached), sorted by creation time. */
    val spaces: Flow<List<Space>> = cache.flow

    fun addSpaceRemovedListener(listener: SpaceRemovedListener) {
        removedListeners += listener
    }

    suspend fun cached(): List<Space> = cache.get()

    /** Reloads the space list; local data of spaces I'm no longer member of is dropped. */
    suspend fun refresh(): ApiResult<List<Space>> =
        apiCall { api.listSpaces() }.map { dtos -> dtos.map { it.toDomain() }.sortedBy { it.createdAt } }.onSuccess { fresh ->
            val gone = cache.get().map { it.id } - fresh.map { it.id }.toSet()
            cache.set(fresh)
            gone.forEach { notifyRemoved(it) }
        }

    /** The creator becomes owner; new spaces get default categories in the user's language. */
    suspend fun create(name: String): ApiResult<Space> =
        apiCall { api.createSpace(NameRequest(name.trim())) }.map { it.toDomain() }.onSuccess { upsert(it) }

    suspend fun rename(spaceId: String, name: String): ApiResult<Space> =
        apiCall { api.renameSpace(spaceId = spaceId, nameRequest = NameRequest(name.trim())) }.map { it.toDomain() }.onSuccess { upsert(it) }

    /** Owner only: deletes the space with all its lists. */
    suspend fun delete(spaceId: String): ApiResult<Unit> =
        apiCallNoContent { api.deleteSpace(spaceId = spaceId) }.onSuccess { remove(spaceId) }

    /** Members only - the owner must hand over ownership first (`OWNER_CANNOT_LEAVE`). */
    suspend fun leave(spaceId: String): ApiResult<Unit> =
        apiCallNoContent { api.leaveSpace(spaceId = spaceId) }.onSuccess { remove(spaceId) }

    suspend fun members(spaceId: String): ApiResult<List<Member>> =
        apiCall { api.listMembers(spaceId = spaceId) }.map { members -> members.map { it.toDomain() } }
            .also { if (it.isGone()) remove(spaceId) }

    suspend fun removeMember(spaceId: String, userId: String): ApiResult<Unit> =
        apiCallNoContent { api.removeMember(spaceId = spaceId, userId = userId) }.onSuccess { refreshOne(spaceId) }

    /** After this, I'm a member of the space. */
    suspend fun transferOwnership(spaceId: String, newOwnerId: String): ApiResult<Space> =
        apiCall { api.transferOwnership(spaceId = spaceId, transferOwnershipRequest = TransferOwnershipRequest(newOwnerId)) }
            .map { it.toDomain() }.onSuccess { upsert(it) }

    /** Open (unused, unexpired) invites. */
    suspend fun invites(spaceId: String): ApiResult<List<Invite>> =
        apiCall { api.listInvites(spaceId = spaceId) }.map { invites -> invites.map { it.toDomain() } }

    /** Single-use invite link, valid for [expiresInHours] (1 h - 30 days, default 24 h). */
    suspend fun createInvite(spaceId: String, expiresInHours: Long? = null): ApiResult<CreatedInvite> =
        apiCall { api.createInvite(spaceId = spaceId, createInviteRequest = CreateInviteRequest(expiresInHours)) }.map { it.toDomain() }

    suspend fun revokeInvite(spaceId: String, inviteId: String): ApiResult<Unit> =
        apiCallNoContent { api.revokeInvite(inviteId = inviteId, spaceId = spaceId) }

    /** What an invite link leads to. Token from [com.tfassbender.shoppinglist.domain.parseInviteToken]. */
    suspend fun previewInvite(token: String): ApiResult<InvitePreview> =
        apiCall { api.previewInvite(token = token) }.map { it.toDomain() }

    /** Joins the space (single use). Errors: `INVITE_INVALID`, `ALREADY_MEMBER`. */
    suspend fun acceptInvite(token: String): ApiResult<Space> =
        apiCall { api.acceptInvite(token = token) }.map { it.toDomain() }.onSuccess { upsert(it) }

    private suspend fun refreshOne(spaceId: String) {
        val result = apiCall { api.getSpace(spaceId = spaceId) }
        if (result is ApiResult.Success) upsert(result.value.toDomain()) else if (result.isGone()) remove(spaceId)
    }

    private suspend fun upsert(space: Space) {
        cache.update { spaces -> (spaces.filterNot { it.id == space.id } + space).sortedBy { it.createdAt } }
    }

    private suspend fun remove(spaceId: String) {
        cache.update { spaces -> spaces.filterNot { it.id == spaceId } }
        notifyRemoved(spaceId)
    }

    private suspend fun notifyRemoved(spaceId: String) {
        removedListeners.forEach { it.onSpaceRemoved(spaceId) }
    }

    /** Non-members get 404 (the server doesn't reveal foreign spaces) - the space is gone for us. */
    private fun ApiResult<*>.isGone() = this is ApiResult.Failure && error.status == 404
}
