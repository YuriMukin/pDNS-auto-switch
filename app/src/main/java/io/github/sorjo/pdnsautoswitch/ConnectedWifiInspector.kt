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

/** A bounded, user-initiated observation. Does not scan, request a connection, or touch DNS. */
class ConnectedWifiInspector(context: Context) {
    sealed interface Result {
        data class Wifi(val ssids: List<String>, val hiddenCount: Int, val vpnActive: Boolean) : Result
        data class NoWifi(val transport: Transport) : Result
        data object PermissionRequired : Result
        data object LocationDisabled : Result
        data object Failed : Result
    }
    enum class Transport { NONE, CELLULAR, ETHERNET, VPN, OTHER }

    private val context = context.applicationContext
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val location = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var deadline: Runnable? = null

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Checked before registration and again before returning the result.
    fun check(onResult: (Result) -> Unit) {
        cancel()
        if (!hasPermission()) { onResult(Result.PermissionRequired); return }
        if (!location.isLocationEnabled) { onResult(Result.LocationDisabled); return }

        val wifi = mutableMapOf<Network, String?>()
        val listener = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) {
                if (callback === this) wifi[network] = null
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (callback !== this) return
                wifi[network] = WifiSsid.fromFramework((capabilities.transportInfo as? WifiInfo)?.ssid)
            }

            override fun onLost(network: Network) {
                if (callback === this) wifi.remove(network)
            }
        }
        val finish = Runnable {
            if (callback !== listener) return@Runnable
            val result = try {
                when {
                    !hasPermission() -> Result.PermissionRequired
                    !location.isLocationEnabled -> Result.LocationDisabled
                    else -> {
                        val active = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                        if (wifi.isNotEmpty()) {
                            Result.Wifi(
                                wifi.values.filterNotNull().distinct().sorted(),
                                wifi.values.count { it == null },
                                active?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
                            )
                        } else {
                            Result.NoWifi(when {
                                active == null -> Transport.NONE
                                active.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.VPN
                                active.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
                                active.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
                                else -> Transport.OTHER
                            })
                        }
                    }
                }
            } catch (_: SecurityException) { Result.PermissionRequired }
            cancel()
            onResult(result)
        }
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            callback = listener
            connectivity.registerNetworkCallback(request, listener, handler)
            deadline = finish
            // Collect the initial callback batch, including multiple Wi-Fi networks and onLost events.
            handler.postDelayed(finish, 2_000)
        } catch (_: SecurityException) {
            cancel()
            onResult(Result.PermissionRequired)
        } catch (_: RuntimeException) {
            cancel()
            onResult(Result.Failed)
        }
    }

    fun cancel() {
        deadline?.let(handler::removeCallbacks)
        deadline = null
        val previous = callback
        callback = null
        if (previous != null) {
            try { connectivity.unregisterNetworkCallback(previous) }
            catch (_: IllegalArgumentException) { /* Registration may have failed. */ }
            catch (_: SecurityException) { /* Permission can be revoked during a check. */ }
        }
    }
}
