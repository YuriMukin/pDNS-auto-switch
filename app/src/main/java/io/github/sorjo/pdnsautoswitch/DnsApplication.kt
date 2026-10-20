package io.github.sorjo.pdnsautoswitch

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

class DnsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AutoRuntime.initialize(this)
        registerActivityLifecycleCallbacks(ObservationPresence)
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder().setPrecondition { _, _ ->
                getSharedPreferences(APPEARANCE, MODE_PRIVATE).getBoolean(DYNAMIC_COLOR, true)
            }.build(),
        )
    }

    companion object {
        const val APPEARANCE = "appearance"
        const val DYNAMIC_COLOR = "dynamic_color"
    }
}
