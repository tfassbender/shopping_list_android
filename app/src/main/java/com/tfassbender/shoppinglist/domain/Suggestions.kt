package com.tfassbender.shoppinglist.domain

/**
 * Offline autocomplete: ranks the space's item history ([Catalog.history]) like the server's
 * `SpaceCatalog.suggestions` (and the web app's `rankSuggestions`). An entry matches if its name or one of
 * its words starts with the query (normalized); names starting with the query come first, then most used,
 * most recently used, and by name. A blank query gives no suggestions.
 */
fun rankSuggestions(history: List<HistoryEntry>, query: String, limit: Int = SUGGESTION_LIMIT): List<Suggestion> {
    val q = normalizeName(query)
    if (q.isEmpty()) return emptyList()
    val startsWithQuery = { entry: HistoryEntry -> normalizeName(entry.name).startsWith(q) }
    return history
        .filter { entry -> startsWithQuery(entry) || normalizeName(entry.name).split(' ').any { it.startsWith(q) } }
        .sortedWith(
            compareByDescending(startsWithQuery)
                .thenByDescending { it.usageCount }
                .thenByDescending { it.lastUsed }
                .thenBy { it.name.lowercase() },
        )
        .take(limit)
        .map { Suggestion(it.name, it.categoryId, it.unit, it.usageCount) }
}

/** Number of suggestions the quick-add field shows (as the web app). */
const val SUGGESTION_LIMIT = 20
