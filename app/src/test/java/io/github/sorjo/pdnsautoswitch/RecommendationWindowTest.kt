package io.github.sorjo.pdnsautoswitch

import org.junit.Assert.*
import org.junit.Test

class RecommendationWindowTest {
    private val off = PolicyDecision(PolicyDecision.Mode.OFF,
        PolicyDecision.Reason.TRUSTED_WIFI, PolicyDecision.Mode.OFF)
    private val unknown = PolicyDecision(PolicyDecision.Mode.HOSTNAME,
        PolicyDecision.Reason.SSID_UNAVAILABLE, PolicyDecision.Mode.HOSTNAME)

    @Test fun `burst emits only latest recommendation after quiet window`() {
        val window = RecommendationWindow()
        window.update(unknown, 0)
        window.update(off, 10)
        window.update(unknown, 20)
        window.update(off, 30)
        assertNull(window.takeReady(779))
        assertEquals(off, window.takeReady(780))
        assertNull(window.takeReady(2000))
    }

    @Test fun `loss of readable SSID replaces trusted decision and restarts window`() {
        val window = RecommendationWindow()
        window.update(off, 100)
        window.update(unknown, 800)
        assertNull(window.takeReady(850))
        assertEquals(unknown, window.takeReady(1550))
    }

    @Test fun `no pending event means no periodic output`() {
        assertNull(RecommendationWindow().takeReady(Long.MAX_VALUE))
    }
}
