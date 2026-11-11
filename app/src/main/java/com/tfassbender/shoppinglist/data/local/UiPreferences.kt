package com.tfassbender.shoppinglist.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Small per-device conveniences (not synced): e.g. the shop last used for each list. */
class UiPreferences(private val dataStore: DataStore<Preferences>) {

    /** The layout (shop) last used on a list; null = order of adding. */
    fun lastLayout(listId: String): Flow<String?> = dataStore.data.map { it[layoutKey(listId)] }

    suspend fun setLastLayout(listId: String, layoutId: String?) {
        dataStore.edit { if (layoutId == null) it.remove(layoutKey(listId)) else it[layoutKey(listId)] = layoutId }
    }

    /** Logout: another user must not see these. */
    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private fun layoutKey(listId: String) = stringPreferencesKey("layout_$listId")
}
