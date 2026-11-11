package com.tfassbender.shoppinglist.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant

/** A login session: the JWT, when it expires and when this device received it (for the sliding refresh). */
data class Session(val token: String, val expiresAt: Instant, val receivedAt: Instant) {

    fun isExpired(now: Instant): Boolean = !expiresAt.isAfter(now)

    /** The token is exchanged for a fresh one about once a day, like in the web app. */
    fun shouldRefresh(now: Instant): Boolean = !isExpired(now) && Duration.between(receivedAt, now) > REFRESH_AFTER

    companion object {
        val REFRESH_AFTER: Duration = Duration.ofDays(1)
    }
}

/**
 * Persists the session in DataStore. The token is encrypted with [cipher] (Android Keystore key); the
 * timestamps are not secret. A token that can't be decrypted counts as "no session" (= logged out).
 */
class SessionStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    val session: Flow<Session?> = dataStore.data.map { prefs ->
        val token = prefs[KEY_TOKEN]?.let(cipher::decryptOrNull)?.takeIf { it.isNotBlank() }
        val expiresAt = prefs[KEY_EXPIRES_AT]
        val receivedAt = prefs[KEY_RECEIVED_AT]
        if (token == null || expiresAt == null || receivedAt == null) {
            null
        } else {
            Session(token, Instant.ofEpochMilli(expiresAt), Instant.ofEpochMilli(receivedAt))
        }
    }

    suspend fun current(): Session? = session.first()

    suspend fun save(session: Session) {
        val encrypted = cipher.encrypt(session.token)
        dataStore.edit {
            it[KEY_TOKEN] = encrypted
            it[KEY_EXPIRES_AT] = session.expiresAt.toEpochMilli()
            it[KEY_RECEIVED_AT] = session.receivedAt.toEpochMilli()
        }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(KEY_TOKEN)
            it.remove(KEY_EXPIRES_AT)
            it.remove(KEY_RECEIVED_AT)
        }
    }

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("session_token_encrypted")
        val KEY_EXPIRES_AT = longPreferencesKey("session_expires_at")
        val KEY_RECEIVED_AT = longPreferencesKey("session_received_at")
    }
}
