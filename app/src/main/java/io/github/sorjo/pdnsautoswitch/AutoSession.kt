package io.github.sorjo.pdnsautoswitch

import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread session only: never persisted, never restarts a service. */
class AutoSession(private val elapsedMs: () -> Long) {
    data class State(
        val enabled: Boolean = false,
        val error: DnsController.ToggleResult? = null,
        val pausedUntilMs: Long? = null,
    )
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    var pauseMinutes: Int = DEFAULT_PAUSE_MINUTES
        private set

    fun setPauseMinutes(minutes: Int) {
        require(minutes in PAUSE_MINUTES)
        pauseMinutes = minutes
    }

    fun enable(controller: DnsController) {
        val dns = controller.readState()
        val error = when {
            !dns.readable -> DnsController.ToggleResult.READ_FAILED
            !dns.canWrite -> DnsController.ToggleResult.MISSING_PERMISSION
            dns.provider.isBlank() -> DnsController.ToggleResult.MISSING_PROVIDER
            else -> null
        }
        mutable.value = State(enabled = error == null, error = error)
    }

    fun disable() { mutable.value = State() }

    fun pauseForManualChange() {
        if (state.value.enabled) mutable.value = state.value.copy(pausedUntilMs = elapsedMs() + pauseMinutes * 60_000L)
    }

    /** Expiration triggers a fresh subscription in the service before any further application. */
    fun resumeIfDue(): Boolean {
        val deadline = state.value.pausedUntilMs ?: return false
        if (elapsedMs() < deadline) return false
        mutable.value = state.value.copy(pausedUntilMs = null)
        return true
    }

    companion object {
        const val DEFAULT_PAUSE_MINUTES = 10
        val PAUSE_MINUTES: List<Int> = listOf(1, 5, 10, 15, 20, 25, 30)
        fun restoredPauseMinutes(value: Int) = value.takeIf { it in PAUSE_MINUTES } ?: DEFAULT_PAUSE_MINUTES
    }

    fun apply(decision: PolicyDecision, controller: DnsController): DnsController.ToggleResult? {
        if (!state.value.enabled || state.value.pausedUntilMs != null) return null
        val result = when (decision.mode) {
            PolicyDecision.Mode.BLOCKED -> if (decision.reason == PolicyDecision.Reason.PROVIDER_MISSING)
                DnsController.ToggleResult.MISSING_PROVIDER else DnsController.ToggleResult.READ_FAILED
            PolicyDecision.Mode.OFF -> controller.applyMode(DnsController.Mode.OFF)
            PolicyDecision.Mode.HOSTNAME -> controller.applyMode(DnsController.Mode.HOSTNAME)
        }
        if (result != DnsController.ToggleResult.SUCCESS) mutable.value = State(error = result)
        return result
    }
}

/** The duration is a preference; enabled state and an active deadline remain session-only. */
object AutoRuntime {
    val session = AutoSession(android.os.SystemClock::elapsedRealtime)
    private const val PREFERENCES = "auto_settings"
    private const val PAUSE_MINUTES = "pause_minutes"

    fun initialize(context: android.content.Context) {
        val saved = context.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE)
            .getInt(PAUSE_MINUTES, AutoSession.DEFAULT_PAUSE_MINUTES)
        session.setPauseMinutes(AutoSession.restoredPauseMinutes(saved))
    }

    fun setPauseMinutes(context: android.content.Context, minutes: Int) {
        session.setPauseMinutes(minutes)
        context.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE)
            .edit { putInt(PAUSE_MINUTES, minutes) }
    }
}
