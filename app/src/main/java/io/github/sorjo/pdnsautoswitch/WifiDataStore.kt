package io.github.sorjo.pdnsautoswitch

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

// One DataStore instance per process/file, also usable by a future policy service.
private val Context.trustedWifiDataStore by preferencesDataStore(name = "trusted_wifi")

fun trustedWifiRepository(context: Context) =
    TrustedWifiRepository(context.applicationContext.trustedWifiDataStore)
