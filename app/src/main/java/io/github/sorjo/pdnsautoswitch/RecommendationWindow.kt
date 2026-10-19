package io.github.sorjo.pdnsautoswitch

/** One-shot quiet window. A newer snapshot always supersedes the previous recommendation. */
class RecommendationWindow {
    private var pending: PolicyDecision? = null
    private var readyAt = 0L

    fun update(decision: PolicyDecision, elapsedMs: Long) {
        pending = decision
        readyAt = elapsedMs + QUIET_MS
    }

    fun takeReady(elapsedMs: Long): PolicyDecision? {
        if (elapsedMs < readyAt) return null
        return pending.also { pending = null }
    }

    companion object { const val QUIET_MS = 750L }
}
