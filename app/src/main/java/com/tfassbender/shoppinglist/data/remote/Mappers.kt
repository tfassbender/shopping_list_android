package com.tfassbender.shoppinglist.data.remote

import com.tfassbender.shoppinglist.api.models.ApiTokenDto
import com.tfassbender.shoppinglist.api.models.CatalogDto
import com.tfassbender.shoppinglist.api.models.CreatedApiTokenDto
import com.tfassbender.shoppinglist.api.models.CreatedInviteDto
import com.tfassbender.shoppinglist.api.models.HistoryEntryDto
import com.tfassbender.shoppinglist.api.models.InviteDto
import com.tfassbender.shoppinglist.api.models.InvitePreviewDto
import com.tfassbender.shoppinglist.api.models.LayoutDto
import com.tfassbender.shoppinglist.api.models.MemberDto
import com.tfassbender.shoppinglist.api.models.SettingsDto
import com.tfassbender.shoppinglist.api.models.SpaceDto
import com.tfassbender.shoppinglist.api.models.SuggestionDto
import com.tfassbender.shoppinglist.api.models.UserDto
import com.tfassbender.shoppinglist.domain.ApiToken
import com.tfassbender.shoppinglist.domain.Catalog
import com.tfassbender.shoppinglist.domain.Category
import com.tfassbender.shoppinglist.domain.CreatedApiToken
import com.tfassbender.shoppinglist.domain.CreatedInvite
import com.tfassbender.shoppinglist.domain.HistoryEntry
import com.tfassbender.shoppinglist.domain.Invite
import com.tfassbender.shoppinglist.domain.InvitePreview
import com.tfassbender.shoppinglist.domain.LayoutMode
import com.tfassbender.shoppinglist.domain.Member
import com.tfassbender.shoppinglist.domain.Role
import com.tfassbender.shoppinglist.domain.ShopLayout
import com.tfassbender.shoppinglist.domain.Space
import com.tfassbender.shoppinglist.domain.Suggestion
import com.tfassbender.shoppinglist.domain.Theme
import com.tfassbender.shoppinglist.domain.TokenScope
import com.tfassbender.shoppinglist.domain.User
import com.tfassbender.shoppinglist.domain.UserSettings
import com.tfassbender.shoppinglist.api.models.LayoutMode as LayoutModeDto
import com.tfassbender.shoppinglist.api.models.Role as RoleDto
import com.tfassbender.shoppinglist.api.models.ThemePreference as ThemeDto
import com.tfassbender.shoppinglist.api.models.TokenScope as TokenScopeDto

// Generated API models → domain models and back. Enums are mapped by name: both sides use the server's names.

fun UserDto.toDomain() = User(id, email, displayName, emailVerified, settings.toDomain())

fun SettingsDto.toDomain() = UserSettings(language, Theme.valueOf(theme.name), checkedItemsToEnd, ignoreMergeWarnings)

fun UserSettings.toDto() = SettingsDto(language, ThemeDto.valueOf(theme.name), checkedItemsToEnd, ignoreMergeWarnings)

fun SpaceDto.toDomain() = Space(id, name, myRole.toDomain(), memberCount, createdAt.toInstant())

fun RoleDto.toDomain() = Role.valueOf(name)

fun MemberDto.toDomain() = Member(userId, displayName, role.toDomain(), joinedAt.toInstant())

fun InviteDto.toDomain() = Invite(id, createdAt.toInstant(), expiresAt.toInstant())

fun CreatedInviteDto.toDomain() = CreatedInvite(invite.toDomain(), token, url)

fun InvitePreviewDto.toDomain() = InvitePreview(spaceName, invitedBy, alreadyMember)

fun ApiTokenDto.toDomain() = ApiToken(
    id, name, TokenScope.valueOf(scope.name), createdAt.toInstant(), expiresAt?.toInstant(), lastUsedAt?.toInstant(),
)

fun TokenScope.toDto() = TokenScopeDto.valueOf(name)

fun CreatedApiTokenDto.toDomain() = CreatedApiToken(token.toDomain(), rawToken)

fun CatalogDto.toDomain() = Catalog(
    categories = categories.map { Category(it.id, it.name, it.color) },
    units = units,
    usage = usage.orEmpty(),
    history = history.orEmpty().map { it.toDomain() },
)

fun HistoryEntryDto.toDomain() = HistoryEntry(name, categoryId, unit, usageCount, lastUsed.toInstant())

fun LayoutDto.toDomain() = ShopLayout(
    id, name, LayoutMode.valueOf(mode.name), categoryOrder, itemOrder, listIds, createdAt.toInstant(),
)

fun LayoutMode.toDto() = LayoutModeDto.valueOf(name)

fun SuggestionDto.toDomain() = Suggestion(name, categoryId, unit, usageCount)
