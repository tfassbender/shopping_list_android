package com.tfassbender.shoppinglist.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The JSON format of the local store: lenient towards older/newer files, so most format changes need no migration. */
val StoreJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

/** Every file wraps its content with a version, so the format can evolve (like the server's persistence). */
@Serializable
private data class Envelope<T>(val schemaVersion: Int, val data: T)

/**
 * One value persisted as a JSON file, kept in memory and observable. Writes are atomic (temp file +
 * move), so a crash never leaves a half-written file; [update] is serialized by a mutex
 * (load-modify-save, like the server's per-space lock).
 *
 * An unreadable file is treated as missing ([default]) – the data comes back with the next sync.
 */
class JsonFile<T : Any>(
    private val file: File,
    serializer: KSerializer<T>,
    private val default: () -> T,
) {
    private val envelopeSerializer = Envelope.serializer(serializer)
    private val mutex = Mutex()
    private val state = MutableStateFlow<T?>(null)

    /** The current value; loads the file on first collection. */
    val flow: Flow<T> = state.onStart { if (state.value == null) get() }.filterNotNull()

    suspend fun get(): T = state.value ?: mutex.withLock { state.value ?: load().also { state.value = it } }

    /** Applies [transform] and saves the result; returns it. */
    suspend fun update(transform: (T) -> T): T = mutex.withLock {
        val current = state.value ?: load()
        val updated = transform(current)
        if (updated != current || !file.exists()) write(updated)
        state.value = updated
        updated
    }

    suspend fun set(value: T) {
        update { value }
    }

    /** Deletes the file and forgets the value (logout, space removed). */
    suspend fun delete() = mutex.withLock {
        withContext(Dispatchers.IO) { file.delete() }
        state.value = default()
    }

    private suspend fun load(): T = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext default()
        try {
            StoreJson.decodeFromString(envelopeSerializer, file.readText()).data
        } catch (_: SerializationException) {
            default()
        } catch (_: IllegalArgumentException) {
            default()
        } catch (_: IOException) {
            default()
        }
    }

    private suspend fun write(value: T) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(StoreJson.encodeToString(envelopeSerializer, Envelope(SCHEMA_VERSION, value)))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
