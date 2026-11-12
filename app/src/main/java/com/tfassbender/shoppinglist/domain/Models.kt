package com.tfassbender.shoppinglist.domain

import kotlinx.serialization.Serializable
import java.time.Instant

// The app's own models - independent of the generated API models (mapped in data/remote/Mappers.kt).
// They are @Serializable because the local store persists them as JSON; kotlinx.serialization is pure
// Kotlin, so the domain stays free of Android and Retrofit.

typealias SerializableInstant = @Serializable(with = InstantSerializer::class) Instant

enum class Role { OWNER, MEMBER }
enum class Theme { SYSTEM, LIGHT, DARK }
enum class TokenScope { READ, FULL }
enum class LayoutMode { CATEGORIES, ITEMS }

@Serializable
data class UserSettings(
    /** `en` or `de`. */
    val language: String = "en",
    val theme: Theme = Theme.SYSTEM,
    val checkedItemsToEnd: Boolean = false,
    val ignoreMergeWarnings: Boolean = false,
)

@Serializable
data class User(
    val id: String,
    val email: String,
    val displayName: String,
    val emailVerified: Boolean,
    val settings: UserSettings,
)

@Serializable
data class Space(
    val id: String,
    val name: String,
    val myRole: Role,
    val memberCount: Int,
    val createdAt: SerializableInstant,
) {
    val isOwner: Boolean get() = myRole == Role.OWNER
}

data class Member(val userId: String, val displayName: String, val role: Role, val joinedAt: Instant)

data class Invite(val id: String, val createdAt: Instant, val expiresAt: Instant)

/** A new invite: [token] and [url] are only available right after creating it. */
data class CreatedInvite(val invite: Invite, val token: String, val url: String)

data class InvitePreview(val spaceName: String, val invitedBy: String, val alreadyMember: Boolean)

data class ApiToken(
    val id: String,
    val name: String,
    val scope: TokenScope,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
)

/** A new API token: [rawToken] is shown once and never again. */
data class CreatedApiToken(val token: ApiToken, val rawToken: String)

@Serializable
data class Item(
    val id: String,
    val name: String,
    val quantity: String? = null,
    val unit: String? = null,
    val note: String? = null,
    val categoryId: String? = null,
    val checked: Boolean = false,
    val createdAt: SerializableInstant,
    /** Server sequence number of the last change; 0 for items only known locally (not synced yet). */
    val seq: Long = 0,
)

@Serializable
data class ShoppingList(
    val id: String,
    val spaceId: String,
    val name: String,
    val createdAt: SerializableInstant,
    val seq: Long = 0,
    val items: List<Item> = emptyList(),
) {
    val openCount: Int get() = items.count { !it.checked }
    val checkedCount: Int get() = items.count { it.checked }
}

@Serializable
data class Category(val id: String, val name: String, val color: String)

@Serializable
data class Catalog(
    /** In the space's order. */
    val categories: List<Category> = emptyList(),
    val units: List<String> = emptyList(),
    /** How often each item was bought: normalized name (see [normalizeName]) → count. Derived from [history]. */
    val usage: Map<String, Int> = emptyMap(),
    /** All items used before in the space (alphabetically) - the source of offline suggestions. */
    val history: List<HistoryEntry> = emptyList(),
) {
    /**
     * Adds or replaces history entries (matched by normalized name, like the server) and keeps [usage] in
     * step, since the sorting reads it. Port of the web app's `mergeHistory`.
     */
    fun mergeHistory(changes: List<HistoryEntry>): Catalog {
        if (changes.isEmpty()) return this
        val byName = LinkedHashMap(history.associateBy { normalizeName(it.name) })
        val newUsage = usage.toMutableMap()
        for (entry in changes) {
            val key = normalizeName(entry.name)
            byName[key] = entry
            if (entry.usageCount > 0) newUsage[key] = entry.usageCount else newUsage.remove(key)
        }
        return copy(history = byName.values.sortedBy { it.name.lowercase() }, usage = newUsage)
    }
}

/** An item used before in a space: last category and unit, how often it was bought, when it was last used. */
@Serializable
data class HistoryEntry(
    val name: String,
    val categoryId: String? = null,
    val unit: String? = null,
    val usageCount: Int = 0,
    val lastUsed: SerializableInstant,
)

@Serializable
data class ShopLayout(
    val id: String,
    val name: String,
    val mode: LayoutMode,
    val categoryOrder: List<String> = emptyList(),
    val itemOrder: List<String> = emptyList(),
    val listIds: List<String> = emptyList(),
    val createdAt: SerializableInstant,
)

data class Suggestion(val name: String, val categoryId: String?, val unit: String?, val usageCount: Int)
