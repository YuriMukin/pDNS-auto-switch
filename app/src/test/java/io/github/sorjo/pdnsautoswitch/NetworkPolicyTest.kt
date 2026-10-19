package io.github.sorjo.pdnsautoswitch

import org.junit.Assert.*
import org.junit.Test

class NetworkPolicyTest {
    private fun decision(snapshot: NetworkSnapshot, trusted: Set<String>? = setOf("Home"),
                         access: LocationAccess = LocationAccess.AVAILABLE,
                         provider: ProviderState = ProviderState.CONFIGURED) =
        PolicyEvaluator.evaluate(snapshot, trusted, access, provider)

    @Test fun `trusted physical wifi recommends off without needing a provider`() {
        val state = NetworkSnapshot(ObservedNetwork(NetworkKind.WIFI, "Home"))
        assertEquals(PolicyDecision.Mode.OFF, decision(state, provider = ProviderState.MISSING).mode)
    }

    @Test fun `public wifi mobile ethernet and no network recommend hostname`() {
        for (active in listOf(null, ObservedNetwork(NetworkKind.WIFI, "Public"),
            ObservedNetwork(NetworkKind.MOBILE), ObservedNetwork(NetworkKind.ETHERNET))) {
            assertEquals(PolicyDecision.Mode.HOSTNAME, decision(NetworkSnapshot(active)).mode)
        }
    }

    @Test fun `hidden SSID missing permission and location off cannot establish trust`() {
        assertEquals(PolicyDecision.Reason.SSID_UNAVAILABLE,
            decision(NetworkSnapshot(ObservedNetwork(NetworkKind.WIFI))).reason)
        val state = NetworkSnapshot(ObservedNetwork(NetworkKind.WIFI, "Home"))
        for (access in listOf(LocationAccess.PERMISSION_MISSING, LocationAccess.LOCATION_OFF)) {
            assertEquals(PolicyDecision.Mode.HOSTNAME, decision(state, access = access).mode)
        }
    }

    @Test fun `unavailable trusted list cannot establish trust`() {
        assertEquals(PolicyDecision.Reason.TRUST_LIST_UNAVAILABLE,
            decision(NetworkSnapshot(ObservedNetwork(NetworkKind.WIFI, "Home")), trusted = null).reason)
    }

    @Test fun `missing or unreadable provider blocks only a hostname recommendation`() {
        for (provider in listOf(ProviderState.MISSING, ProviderState.UNREADABLE)) {
            val result = decision(NetworkSnapshot(null), provider = provider)
            assertEquals(PolicyDecision.Mode.BLOCKED, result.mode)
            assertEquals(PolicyDecision.Mode.HOSTNAME, result.desired)
        }
    }

    @Test fun `SSID comparison preserves case spaces and membership changes`() {
        val state = NetworkSnapshot(ObservedNetwork(NetworkKind.WIFI, "Home"))
        for (trusted in listOf(emptySet(), setOf("home"), setOf("Home "))) {
            assertEquals(PolicyDecision.Mode.HOSTNAME, decision(state, trusted).mode)
        }
        assertEquals(PolicyDecision.Mode.OFF, decision(state).mode)
    }

    @Test fun `unvalidated trusted wifi can still recommend off`() {
        assertEquals(PolicyDecision.Mode.OFF,
            decision(NetworkSnapshot(ObservedNetwork(NetworkKind.WIFI, "Home", validated = false))).mode)
    }

    @Test fun `physical wifi takes precedence without inspecting overlays`() {
        val tracker = NetworkTracker()
        tracker.reset(20)
        tracker.defaultCapabilities(20, ObservedNetwork(NetworkKind.OTHER))
        tracker.wifiAvailable(10)
        tracker.wifiCapabilities(10, ObservedNetwork(NetworkKind.WIFI, "Home"))
        assertEquals(PolicyDecision.Mode.OFF, decision(tracker.snapshot(LocationAccess.AVAILABLE)).mode)
    }

    @Test fun `multiple physical wifi networks need a known default or remain ambiguous`() {
        val tracker = NetworkTracker()
        tracker.reset(30)
        for ((id, name) in listOf(10L to "Home", 20L to "Public")) {
            tracker.wifiAvailable(id)
            tracker.wifiCapabilities(id, ObservedNetwork(NetworkKind.WIFI, name))
        }
        assertEquals(PolicyDecision.Reason.MULTIPLE_WIFI, decision(tracker.snapshot(LocationAccess.AVAILABLE)).reason)
        tracker.defaultAvailable(20)
        tracker.defaultCapabilities(20, ObservedNetwork(NetworkKind.WIFI, "Public"))
        assertEquals(PolicyDecision.Reason.UNTRUSTED_WIFI, decision(tracker.snapshot(LocationAccess.AVAILABLE)).reason)
    }

    @Test fun `lost wifi and late callbacks never resurrect a trusted SSID`() {
        val tracker = NetworkTracker()
        tracker.reset(10)
        tracker.defaultCapabilities(10, ObservedNetwork(NetworkKind.WIFI, "Home"))
        tracker.wifiAvailable(10)
        tracker.wifiCapabilities(10, ObservedNetwork(NetworkKind.WIFI, "Home"))
        tracker.wifiLost(10)
        tracker.wifiCapabilities(10, ObservedNetwork(NetworkKind.WIFI, "Home"))
        tracker.defaultCapabilities(10, ObservedNetwork(NetworkKind.WIFI, "Home"))
        assertNotEquals(PolicyDecision.Mode.OFF, decision(tracker.snapshot(LocationAccess.AVAILABLE)).mode)
        tracker.defaultLost(10)
        tracker.defaultCapabilities(10, ObservedNetwork(NetworkKind.WIFI, "Home"))
        assertEquals(PolicyDecision.Reason.NO_NETWORK, decision(tracker.snapshot(LocationAccess.AVAILABLE)).reason)
    }

    @Test fun `old default loss cannot erase a newer connection`() {
        val tracker = NetworkTracker()
        tracker.reset(10)
        tracker.defaultAvailable(20)
        tracker.defaultCapabilities(20, ObservedNetwork(NetworkKind.MOBILE))
        tracker.defaultLost(10)
        assertEquals(NetworkKind.MOBILE, tracker.snapshot(LocationAccess.AVAILABLE).active?.kind)
    }

    @Test fun `resubscription and missing location access invalidate cached SSIDs`() {
        val tracker = NetworkTracker()
        tracker.reset(10)
        tracker.defaultCapabilities(10, ObservedNetwork(NetworkKind.WIFI, "Home"))
        assertNull(tracker.snapshot(LocationAccess.PERMISSION_MISSING).active?.ssid)
        tracker.reset(10)
        assertEquals(PolicyDecision.Reason.INITIALIZING, decision(tracker.snapshot(LocationAccess.AVAILABLE)).reason)
    }
}
