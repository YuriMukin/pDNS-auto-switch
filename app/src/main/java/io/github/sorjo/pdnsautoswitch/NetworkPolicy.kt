package io.github.sorjo.pdnsautoswitch

enum class NetworkKind { PENDING, WIFI, MOBILE, ETHERNET, AMBIGUOUS, OTHER }
enum class LocationAccess { AVAILABLE, PERMISSION_MISSING, LOCATION_OFF }
enum class ProviderState { CONFIGURED, MISSING, UNREADABLE }

data class ObservedNetwork(
    val kind: NetworkKind,
    val ssid: String? = null,
    val validated: Boolean = false,
    val captivePortal: Boolean = false,
)

data class NetworkSnapshot(
    val active: ObservedNetwork?,
    val physicalWifiCount: Int = 0,
)

/** Ephemeral handles never leave memory. Late callbacks cannot resurrect a lost default network. */
class NetworkTracker {
    private var activeHandle: Long? = null
    private var active: ObservedNetwork? = null
    private val wifi = mutableMapOf<Long, ObservedNetwork>()
    private val lostWifi = mutableSetOf<Long>()

    fun reset(defaultHandle: Long?) {
        wifi.clear()
        lostWifi.clear()
        activeHandle = defaultHandle
        active = defaultHandle?.let { ObservedNetwork(NetworkKind.PENDING) }
    }

    fun defaultAvailable(handle: Long) {
        activeHandle = handle
        active = ObservedNetwork(NetworkKind.PENDING)
    }

    fun defaultCapabilities(handle: Long, network: ObservedNetwork) {
        if (activeHandle == handle) {
            active = if (handle in lostWifi && network.kind == NetworkKind.WIFI) {
                ObservedNetwork(NetworkKind.PENDING)
            } else network
        }
    }

    fun defaultLost(handle: Long) {
        if (activeHandle == handle) { activeHandle = null; active = null }
    }

    fun wifiAvailable(handle: Long) {
        lostWifi.remove(handle)
        wifi[handle] = ObservedNetwork(NetworkKind.WIFI)
    }
    fun wifiCapabilities(handle: Long, network: ObservedNetwork) {
        if (handle in wifi) wifi[handle] = network
    }

    fun wifiLost(handle: Long) {
        wifi.remove(handle)
        lostWifi.add(handle)
        if (activeHandle == handle && active?.kind == NetworkKind.WIFI) {
            // Do not retain the SSID while waiting for default-network callbacks to catch up.
            active = ObservedNetwork(NetworkKind.PENDING)
        }
    }

    fun snapshot(access: LocationAccess): NetworkSnapshot {
        // Connected physical Wi-Fi takes precedence, independently of an overlay/default network.
        // No VPN detection is needed. Multiple candidates remain explicit rather than choosing arbitrarily.
        val current = when {
            wifi.size == 1 -> wifi.values.single()
            wifi.size > 1 && activeHandle in wifi -> wifi[activeHandle]
            wifi.size > 1 -> ObservedNetwork(NetworkKind.AMBIGUOUS)
            else -> active
        }
        val safe = if (current?.kind == NetworkKind.WIFI) current.copy(
            ssid = if (access == LocationAccess.AVAILABLE) {
                current.ssid
            } else null
        ) else current
        return NetworkSnapshot(safe, wifi.size)
    }
}

data class PolicyDecision(val mode: Mode, val reason: Reason, val desired: Mode) {
    enum class Mode { OFF, HOSTNAME, BLOCKED }
    enum class Reason {
        TRUSTED_WIFI, UNTRUSTED_WIFI, SSID_UNAVAILABLE, PERMISSION_MISSING, LOCATION_OFF,
        MULTIPLE_WIFI, INITIALIZING, NO_NETWORK, MOBILE, ETHERNET, OTHER,
        TRUST_LIST_UNAVAILABLE, PROVIDER_MISSING, PROVIDER_UNREADABLE,
    }
}

/** Pure recommendations only. This component has no API for changing system settings. */
object PolicyEvaluator {
    fun evaluate(
        snapshot: NetworkSnapshot,
        trusted: Set<String>?,
        access: LocationAccess,
        provider: ProviderState,
    ): PolicyDecision {
        val active = snapshot.active
        val reason = when (active?.kind) {
            null -> PolicyDecision.Reason.NO_NETWORK
            NetworkKind.PENDING -> PolicyDecision.Reason.INITIALIZING
            NetworkKind.MOBILE -> PolicyDecision.Reason.MOBILE
            NetworkKind.ETHERNET -> PolicyDecision.Reason.ETHERNET
            NetworkKind.AMBIGUOUS -> PolicyDecision.Reason.MULTIPLE_WIFI
            NetworkKind.OTHER -> PolicyDecision.Reason.OTHER
            NetworkKind.WIFI -> when {
                access == LocationAccess.PERMISSION_MISSING -> PolicyDecision.Reason.PERMISSION_MISSING
                access == LocationAccess.LOCATION_OFF -> PolicyDecision.Reason.LOCATION_OFF
                trusted == null -> PolicyDecision.Reason.TRUST_LIST_UNAVAILABLE
                active.ssid == null -> PolicyDecision.Reason.SSID_UNAVAILABLE
                active.ssid in trusted -> PolicyDecision.Reason.TRUSTED_WIFI
                else -> PolicyDecision.Reason.UNTRUSTED_WIFI
            }
        }
        val desired = if (reason == PolicyDecision.Reason.TRUSTED_WIFI) {
            PolicyDecision.Mode.OFF
        } else PolicyDecision.Mode.HOSTNAME
        if (desired == PolicyDecision.Mode.HOSTNAME && provider != ProviderState.CONFIGURED) {
            return PolicyDecision(
                PolicyDecision.Mode.BLOCKED,
                if (provider == ProviderState.MISSING) PolicyDecision.Reason.PROVIDER_MISSING
                else PolicyDecision.Reason.PROVIDER_UNREADABLE,
                desired,
            )
        }
        return PolicyDecision(desired, reason, desired)
    }
}
