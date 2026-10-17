package io.github.sorjo.pdnsautoswitch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

class AndroidDnsSettings(context: Context) : DnsSettings {
    private val context = context.applicationContext
    override fun read(key: String): String? = Settings.Global.getString(context.contentResolver, key)
    override fun write(key: String, value: String): Boolean =
        Settings.Global.putString(context.contentResolver, key, value)
    override fun canWrite(): Boolean = context.checkSelfPermission(
        Manifest.permission.WRITE_SECURE_SETTINGS
    ) == PackageManager.PERMISSION_GRANTED
}

/** Observe only while an Activity or the visible QS panel needs updates. No background monitor. */
class DnsSettingsObserver(context: Context, onChange: () -> Unit) {
    private val resolver = context.contentResolver
    private var registered = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = onChange()
    }

    fun start() {
        if (registered) return
        registered = true
        try {
            listOf(DnsController.MODE, DnsController.PROVIDER, DnsController.DEFAULT_MODE).forEach {
                resolver.registerContentObserver(Settings.Global.getUriFor(it), false, observer)
            }
        } catch (_: SecurityException) {
            stop()
        }
    }

    fun stop() {
        if (!registered) return
        resolver.unregisterContentObserver(observer)
        registered = false
    }
}
