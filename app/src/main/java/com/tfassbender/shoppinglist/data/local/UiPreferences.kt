package com.tfassbender.shoppinglist.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The list opened last on this device (marked on the start page, preselected space for new lists). */
data class LastList(val spaceId: String, val listId: String)

/**
 * Small per-device conveniences (not synced), like the web app's localStorage values. Two kinds:
 * - per user (cleared on logout): the shop last used per list, the list opened last;
 * - per device (kept on logout): keep the screen on, category headers, the language before login.
 */
class UiPreferences(private val dataStore: DataStore<Preferences>) {

    /** The layout (shop) last used on a list; null = order of adding. */
    fun lastLayout(listId: String): Flow<String?> = dataStore.data.map { it[layoutKey(listId)] }

    suspend fun setLastLayout(listId: String, layoutId: String?) {
        dataStore.edit { if (layoutId == null) it.remove(layoutKey(listId)) else it[layoutKey(listId)] = layoutId }
    }

    val lastList: Flow<LastList?> = dataStore.data.map { prefs ->
        val space = prefs[LAST_LIST_SPACE]
        val list = prefs[LAST_LIST_ID]
        if (space != null && list != null) LastList(space, list) else null
    }

    suspend fun setLastList(value: LastList) {
        dataStore.edit {
            it[LAST_LIST_SPACE] = value.spaceId
            it[LAST_LIST_ID] = value.listId
        }
    }

    /** While the app is open the screen doesn't turn off (list in one hand, trolley in the other). Default on. */
    val keepScreenOn: Flow<Boolean> = dataStore.data.map { it[KEEP_SCREEN_ON] ?: true }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        dataStore.edit { it[KEEP_SCREEN_ON] = enabled }
    }

    /** Lists show category headers (default) or a colored bar per item. */
    val categoryHeaders: Flow<Boolean> = dataStore.data.map { it[CATEGORY_HEADERS] ?: true }

    suspend fun setCategoryHeaders(shown: Boolean) {
        dataStore.edit { it[CATEGORY_HEADERS] = shown }
    }

    /** The language chosen before login (`en`/`de`); null = the device's language. Afterwards it's a user setting. */
    val deviceLanguage: Flow<String?> = dataStore.data.map { it[DEVICE_LANGUAGE] }

    suspend fun setDeviceLanguage(language: String) {
        dataStore.edit { it[DEVICE_LANGUAGE] = language }
    }

    /** Logout: another user must not see the per-user values; device settings stay. */
    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name !in DEVICE_KEYS }.forEach { prefs.remove(it) }
        }
    }

    private fun layoutKey(listId: String) = stringPreferencesKey("layout_$listId")

    private companion object {
        val LAST_LIST_SPACE = stringPreferencesKey("last_list_space")
        val LAST_LIST_ID = stringPreferencesKey("last_list_id")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val CATEGORY_HEADERS = booleanPreferencesKey("category_headers")
        val DEVICE_LANGUAGE = stringPreferencesKey("device_language")
        val DEVICE_KEYS = setOf(KEEP_SCREEN_ON.name, CATEGORY_HEADERS.name, DEVICE_LANGUAGE.name)
    }
}
