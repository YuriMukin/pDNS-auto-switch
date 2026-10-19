package io.github.sorjo.pdnsautoswitch

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Exact, already decoded SSIDs. Never normalize them a second time or store network handles. */
class TrustedWifiRepository(private val store: DataStore<Preferences>) {
    val ssids: Flow<Set<String>> = store.data.map { it[KEY].orEmpty().toSet() }

    suspend fun add(ssid: String) {
        require(ssid.isNotBlank() && ssid != "<unknown ssid>")
        store.edit { it[KEY] = it[KEY].orEmpty() + ssid }
    }

    suspend fun remove(ssid: String) {
        store.edit { it[KEY] = it[KEY].orEmpty() - ssid }
    }

    private companion object {
        val KEY = stringSetPreferencesKey("trusted_ssids")
    }
}
