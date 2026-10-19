package io.github.sorjo.pdnsautoswitch

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Visibility at callback time, not inferred later when the log is opened. */
object ObservationPresence : Application.ActivityLifecycleCallbacks {
    private var resumed = 0
    private val visible = MutableStateFlow(false)
    val foreground = visible.asStateFlow()
    override fun onActivityResumed(activity: Activity) { resumed++; visible.value = resumed > 0 }
    override fun onActivityPaused(activity: Activity) { resumed = (resumed - 1).coerceAtLeast(0); visible.value = resumed > 0 }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
