package com.tfassbender.shoppinglist.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tfassbender.shoppinglist.data.remote.ServerProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The server this device talks to: the build's default ([defaultUrl]) unless the user chose another one
 * (the server is open source, anyone can run one). Belongs to the device - it survives logging out.
 * Change it only through `ServerRepository.change`, which logs out first (a session and the local data
 * belong to one server).
 */
class ServerConfig(private val dataStore: DataStore<Preferences>, defaultUrl: String) : ServerProvider {
    val defaultUrl: HttpUrl = defaultUrl.toHttpUrl()

    // Read once at start (a tiny file); every request asks for the current value.
    private val state = MutableStateFlow(runBlocking { dataStore.data.first()[KEY] }?.toHttpUrlOrNull() ?: this.defaultUrl)

    /** The current server's base URL (ends with `/`). */
    val url: StateFlow<HttpUrl> = state.asStateFlow()

    override fun baseUrl(): HttpUrl = state.value

    /** Sets the server; null or the default address means "the default" (follows a new default of the app). */
    suspend fun set(url: HttpUrl?) {
        val custom = url?.takeIf { it != defaultUrl }
        dataStore.edit { if (custom == null) it.remove(KEY) else it[KEY] = custom.toString() }
        state.value = custom ?: defaultUrl
    }

    private companion object {
        val KEY = stringPreferencesKey("server_url")
    }
}
