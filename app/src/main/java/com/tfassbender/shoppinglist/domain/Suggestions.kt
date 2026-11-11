package com.tfassbender.shoppinglist.domain

/**
 * Offline autocomplete from what is on this device: item names of the space's lists (with their last
 * category/unit) and the usage counts of the catalog. Ranking like the server's suggestions: name-prefix
 * matches first, then word-prefix matches, each by usage count, then name.
 *
 * Weaker than the server's history (items removed from all lists are unknown here) - server issue #1
 * would deliver the history to clients.
 */
fun localSuggestions(query: String, lists: Collection<ShoppingList>, usage: Map<String, Int>, limit: Int = 8): List<Suggestion> {
    val q = normalizeName(query)
    if (q.isEmpty()) return emptyList()
    // Newest item per name wins: it carries the latest category/unit.
    val latest = lists.flatMap { it.items }.sortedByDescending { it.createdAt }.distinctBy { normalizeName(it.name) }
    val candidates = latest.map { Suggestion(it.name, it.categoryId, it.unit, usage[normalizeName(it.name)] ?: 0) }
    fun rank(s: Suggestion): Int? {
        val name = normalizeName(s.name)
        return when {
            name.startsWith(q) -> 0
            name.split(' ').drop(1).any { it.startsWith(q) } -> 1
            else -> null
        }
    }
    return candidates.mapNotNull { s -> rank(s)?.let { it to s } }
        .sortedWith(compareBy<Pair<Int, Suggestion>> { it.first }.thenByDescending { it.second.usageCount }.thenBy { it.second.name.lowercase() })
        .take(limit)
        .map { it.second }
}
