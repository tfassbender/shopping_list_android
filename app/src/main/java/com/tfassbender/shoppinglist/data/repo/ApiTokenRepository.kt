package com.tfassbender.shoppinglist.data.repo

import com.tfassbender.shoppinglist.api.apis.APITokensApi
import com.tfassbender.shoppinglist.api.models.CreateApiTokenRequest
import com.tfassbender.shoppinglist.data.remote.ApiResult
import com.tfassbender.shoppinglist.data.remote.apiCall
import com.tfassbender.shoppinglist.data.remote.apiCallNoContent
import com.tfassbender.shoppinglist.data.remote.map
import com.tfassbender.shoppinglist.data.remote.toDomain
import com.tfassbender.shoppinglist.data.remote.toDto
import com.tfassbender.shoppinglist.domain.ApiToken
import com.tfassbender.shoppinglist.domain.CreatedApiToken
import com.tfassbender.shoppinglist.domain.TokenScope

/** Personal API tokens for AI agents (online only, not cached - only the settings screen needs them). */
class ApiTokenRepository(private val api: APITokensApi) {

    suspend fun list(): ApiResult<List<ApiToken>> = apiCall { api.listApiTokens() }.map { tokens -> tokens.map { it.toDomain() } }

    /** [expiresInDays] 1-3650, null = never expires. The raw token in the result is shown only once. */
    suspend fun create(name: String, scope: TokenScope, expiresInDays: Long?): ApiResult<CreatedApiToken> =
        apiCall { api.createApiToken(CreateApiTokenRequest(name = name.trim(), scope = scope.toDto(), expiresInDays = expiresInDays)) }
            .map { it.toDomain() }

    suspend fun revoke(tokenId: String): ApiResult<Unit> = apiCallNoContent { api.revokeApiToken(tokenId = tokenId) }
}
