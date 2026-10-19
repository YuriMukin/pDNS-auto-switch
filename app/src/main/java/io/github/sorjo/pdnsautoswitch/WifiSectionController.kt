package io.github.sorjo.pdnsautoswitch

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.IOException

/** Owns the diagnostic UI, not the DNS controller. Its lifetime is the Activity instance. */
class WifiSectionController(private val activity: AppCompatActivity) {
    private val repository = trustedWifiRepository(activity)
    private val inspector = ConnectedWifiInspector(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val status = activity.findViewById<TextView>(R.id.wifi_status)
    private val results = activity.findViewById<LinearLayout>(R.id.wifi_results)
    private val trustedList = activity.findViewById<LinearLayout>(R.id.trusted_wifi_list)
    private val checkButton = activity.findViewById<Button>(R.id.check_wifi)
    private val settingsButton = activity.findViewById<Button>(R.id.wifi_settings)
    private var trusted = emptySet<String>()
    private var storageReady = false
    private var current: ConnectedWifiInspector.Result.Wifi? = null
    private var checking = false
    private var settingsAction: String? = null
    private val permissionRequest = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (inspector.hasPermission()) inspect()
        else showPermissionRequired()
    }

    init {
        checkButton.setOnClickListener { requestCheck() }
        settingsButton.setOnClickListener {
            val intent = if (settingsAction == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                Intent(settingsAction, Uri.parse("package:${activity.packageName}"))
            } else Intent(settingsAction)
            try { activity.startActivity(intent) }
            catch (_: ActivityNotFoundException) { toast(R.string.wifi_settings_unavailable) }
        }
        scope.launch {
            repository.ssids.catch { error ->
                if (error !is IOException) throw error
                storageReady = false
                trustedList.removeAllViews()
                trustedList.addView(body(activity.getString(R.string.wifi_storage_failed)))
                renderResults()
            }.collect {
                trusted = it
                storageReady = true
                renderTrusted()
                renderResults()
            }
        }
    }

    private fun requestCheck() {
        if (inspector.hasPermission()) { inspect(); return }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.wifi_permission_title)
            .setMessage(R.string.wifi_permission_explanation)
            .setNegativeButton(R.string.wifi_cancel, null)
            .setPositiveButton(R.string.wifi_allow) { _, _ ->
                // Let Android decide whether it can show the dialog (including one-time grants).
                // If it declines immediately, the callback offers the app's permission settings.
                permissionRequest.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            }.show()
    }

    private fun inspect(addAfterCheck: String? = null) {
        current = null
        results.removeAllViews()
        settingsButton.visibility = View.GONE
        status.setText(R.string.wifi_checking)
        checking = true
        setBusy(true)
        inspector.check { result ->
            checking = false
            setBusy(false)
            when (result) {
                ConnectedWifiInspector.Result.PermissionRequired -> showPermissionRequired()
                ConnectedWifiInspector.Result.LocationDisabled -> {
                    status.setText(R.string.wifi_location_disabled)
                    showSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS, R.string.wifi_location_settings)
                }
                ConnectedWifiInspector.Result.Failed -> status.setText(R.string.wifi_check_failed)
                is ConnectedWifiInspector.Result.NoWifi -> status.setText(when (result.transport) {
                    ConnectedWifiInspector.Transport.NONE -> R.string.wifi_no_network
                    ConnectedWifiInspector.Transport.CELLULAR -> R.string.wifi_mobile
                    ConnectedWifiInspector.Transport.ETHERNET -> R.string.wifi_ethernet
                    ConnectedWifiInspector.Transport.VPN -> R.string.wifi_vpn_no_wifi
                    ConnectedWifiInspector.Transport.OTHER -> R.string.wifi_not_connected
                })
                is ConnectedWifiInspector.Result.Wifi -> {
                    current = result
                    status.text = activity.getString(when {
                        result.ssids.isEmpty() -> R.string.wifi_redacted
                        result.vpnActive -> R.string.wifi_found_vpn
                        result.hiddenCount > 0 -> R.string.wifi_found_partial
                        else -> R.string.wifi_found
                    })
                    renderResults()
                }
            }
            if (addAfterCheck != null) {
                if ((result as? ConnectedWifiInspector.Result.Wifi)?.ssids?.contains(addAfterCheck) == true) {
                    save(addAfterCheck)
                } else toast(R.string.wifi_changed)
            }
        }
    }

    private fun showPermissionRequired() {
        status.setText(R.string.wifi_permission_required)
        showSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, R.string.wifi_open_app_settings)
    }

    private fun showSettings(action: String, label: Int) {
        settingsAction = action
        settingsButton.setText(label)
        settingsButton.visibility = View.VISIBLE
    }

    private fun save(ssid: String) {
        scope.launch {
            try { repository.add(ssid); toast(R.string.wifi_saved) }
            catch (_: IOException) { toast(R.string.wifi_storage_failed) }
        }
    }

    private fun renderResults() {
        results.removeAllViews()
        current?.ssids?.forEach { ssid ->
            results.addView(body(activity.getString(R.string.wifi_ssid_value, ssid)))
            results.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = activity.getString(if (ssid in trusted) R.string.wifi_already_trusted else R.string.wifi_trust)
                isEnabled = storageReady && ssid !in trusted
                setOnClickListener { inspect(addAfterCheck = ssid) }
            })
        }
    }

    private fun renderTrusted() {
        trustedList.removeAllViews()
        if (trusted.isEmpty()) {
            trustedList.addView(body(activity.getString(R.string.wifi_empty)))
        }
        trusted.sorted().forEach { ssid ->
            val row = activity.layoutInflater.inflate(R.layout.row_trusted_wifi, trustedList, false)
            row.findViewById<TextView>(R.id.trusted_ssid).text = ssid
            row.findViewById<Button>(R.id.remove_trusted_wifi).apply {
                contentDescription = activity.getString(R.string.wifi_remove_description, ssid)
                setOnClickListener {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.wifi_remove_title)
                        .setMessage(activity.getString(R.string.wifi_remove_confirmation, ssid))
                        .setNegativeButton(R.string.wifi_cancel, null)
                        .setPositiveButton(R.string.wifi_remove) { _, _ ->
                            scope.launch {
                                try { repository.remove(ssid) }
                                catch (_: IOException) { toast(R.string.wifi_storage_failed) }
                            }
                        }.show()
                }
            }
            trustedList.addView(row)
        }
    }

    private fun body(value: String) = TextView(activity).apply {
        text = value
        setTextAppearance(R.style.Text_Pdns_Body)
        val padding = (8 * resources.displayMetrics.density).toInt()
        setPadding(0, padding, 0, padding)
    }

    private fun setBusy(busy: Boolean) {
        checkButton.isEnabled = !busy
        checkButton.setText(if (busy) R.string.wifi_checking else R.string.wifi_check)
    }

    private fun toast(message: Int) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    fun onPause() {
        inspector.cancel()
        current = null
        results.removeAllViews()
        if (checking) status.setText(R.string.wifi_cancelled)
        else if (status.text.isNotBlank()) status.setText(R.string.wifi_idle)
        checking = false
        setBusy(false)
    }

    fun close() {
        inspector.cancel()
        scope.cancel()
    }
}
