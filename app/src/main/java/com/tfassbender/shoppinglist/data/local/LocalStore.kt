package com.tfassbender.shoppinglist.data.local

import com.tfassbender.shoppinglist.data.repo.CachedUser
import com.tfassbender.shoppinglist.domain.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's local data as JSON files (see doc/tech_stack.md, "Local storage"):
 * ```
 * <root>/me.json                user incl. settings
 * <root>/spaces.json            my spaces
 * <root>/spaces/<spaceId>.json  per space: lists, pending changes, catalog, layouts, ... (SpaceState)
 * ```
 * Everything here belongs to the logged-in user and is deleted on logout ([clear]).
 */
class LocalStore(private val root: File) {

    val me = JsonFile(File(root, "me.json"), CachedUser.serializer()) { CachedUser() }

    val spaces = JsonFile(File(root, "spaces.json"), ListSerializer(Space.serializer())) { emptyList() }

    private val spaceFiles = ConcurrentHashMap<String, JsonFile<*>>()

    /** The per-space file; one instance per space, so its in-memory state and lock are shared. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> spaceFile(spaceId: String, serializer: KSerializer<T>, default: () -> T): JsonFile<T> {
        require(SAFE_ID.matches(spaceId)) { "Invalid space id" } // a crafted id must not escape the store
        return spaceFiles.getOrPut(spaceId) { JsonFile(File(spacesDir, "$spaceId.json"), serializer, default) } as JsonFile<T>
    }

    /** Ids of all spaces with local data (to resume syncing them after an app restart). */
    suspend fun storedSpaceIds(): List<String> = withContext(Dispatchers.IO) {
        spacesDir.listFiles { file -> file.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?.filter { SAFE_ID.matches(it) }
            .orEmpty()
    }

    suspend fun deleteSpace(spaceId: String) {
        spaceFiles.remove(spaceId)?.delete() ?: withContext(Dispatchers.IO) { File(spacesDir, "$spaceId.json").delete() }
    }

    /** Deletes everything (logout). */
    suspend fun clear() {
        me.delete()
        spaces.delete()
        spaceFiles.values.forEach { it.delete() }
        spaceFiles.clear()
        withContext(Dispatchers.IO) { root.deleteRecursively() }
    }

    private val spacesDir get() = File(root, "spaces")

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
