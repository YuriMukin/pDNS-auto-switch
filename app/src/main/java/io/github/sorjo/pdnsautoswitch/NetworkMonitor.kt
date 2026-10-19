package io.github.sorjo.pdnsautoswitch

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Handler
import android.os.Looper

/** Continuous callbacks while explicitly started. No scans, connection requests, or timer loop. */
class NetworkMonitor(context: Context, private val onEvent: (String) -> Unit) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val location = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val tracker = NetworkTracker()
    private var generation = 0
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    fun locationAccess(): LocationAccess = when {
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ->
            LocationAccess.PERMISSION_MISSING
        !location.isLocationEnabled -> LocationAccess.LOCATION_OFF
        else -> LocationAccess.AVAILABLE
    }

    fun snapshot() = tracker.snapshot(locationAccess())

    @SuppressLint("MissingPermission") // ACCESS_NETWORK_STATE is declared; SSID may legitimately be redacted.
    fun refresh() {
        stop()
        val token = generation
        tracker.reset(manager.activeNetwork?.networkHandle)
        val defaults = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) {
                if (token != generation) return
                tracker.defaultAvailable(network.networkHandle)
                onEvent("DEFAULT_AVAILABLE")
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (token != generation) return
                tracker.defaultCapabilities(network.networkHandle, describe(caps))
                onEvent("DEFAULT_CAPABILITIES")
            }
            override fun onLost(network: Network) {
                if (token != generation) return
                tracker.defaultLost(network.networkHandle)
                onEvent("DEFAULT_LOST")
            }
        }
        val physical = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) {
                if (token != generation) return
                tracker.wifiAvailable(network.networkHandle)
                onEvent("WIFI_AVAILABLE")
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (token != generation) return
                tracker.wifiCapabilities(network.networkHandle, describe(caps))
                onEvent("WIFI_CAPABILITIES")
            }
            override fun onLost(network: Network) {
                if (token != generation) return
                tracker.wifiLost(network.networkHandle)
                onEvent("WIFI_LOST")
            }
        }
        try {
            defaultCallback = defaults
            manager.registerDefaultNetworkCallback(defaults, handler)
            wifiCallback = physical
            manager.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), physical, handler,
            )
            onEvent("SUBSCRIBED")
        } catch (error: RuntimeException) {
            stop()
            throw error
        }
    }

    private fun describe(caps: NetworkCapabilities): ObservedNetwork {
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.MOBILE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
            else -> NetworkKind.OTHER
        }
        return ObservedNetwork(
            kind,
            if (kind == NetworkKind.WIFI && locationAccess() == LocationAccess.AVAILABLE) {
                WifiSsid.fromFramework((caps.transportInfo as? WifiInfo)?.ssid)
            } else null,
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        )
    }

    fun stop() {
        generation++ // Ignore queued callbacks from every previous registration.
        listOfNotNull(defaultCallback, wifiCallback).forEach {
            try { manager.unregisterNetworkCallback(it) }
            catch (_: IllegalArgumentException) { }
            catch (_: SecurityException) { }
        }
        defaultCallback = null
        wifiCallback = null
        tracker.reset(null)
    }
}
