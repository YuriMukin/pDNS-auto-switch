package io.github.sorjo.pdnsautoswitch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ObservationSectionController(private val activity: AppCompatActivity) {
    private val repository = ObservationRepository.get(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val status = activity.findViewById<TextView>(R.id.observation_status)
    private val start = activity.findViewById<Button>(R.id.observation_start)
    private val stop = activity.findViewById<Button>(R.id.observation_stop)
    private val save = activity.findViewById<Button>(R.id.observation_save)
    private val clear = activity.findViewById<Button>(R.id.observation_clear)
    private val logging = activity.findViewById<MaterialSwitch>(R.id.observation_logging)
    private var exporting = false
    private val permissionRequest = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasPrecisePermission()) startObservation()
        else toast(R.string.observation_permission_required)
    }

    init {
        start.setOnClickListener {
            if (hasPrecisePermission()) startObservation()
            else MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.wifi_permission_title)
                .setMessage(R.string.observation_permission_explanation)
                .setNegativeButton(R.string.wifi_cancel, null)
                .setPositiveButton(R.string.wifi_allow) { _, _ ->
                    permissionRequest.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                    ))
                }.show()
        }
        stop.setOnClickListener { activity.stopService(Intent(activity, NetworkObservationService::class.java)) }
        logging.isChecked = repository.state.value.loggingEnabled
        logging.setOnCheckedChangeListener { _, enabled -> repository.setLoggingEnabled(enabled) }
        save.setOnClickListener { export() }
        clear.setOnClickListener {
            MaterialAlertDialogBuilder(activity).setTitle(R.string.observation_clear_title)
                .setMessage(R.string.observation_clear_message)
                .setNegativeButton(R.string.wifi_cancel, null)
                .setPositiveButton(R.string.observation_clear) { _, _ -> repository.clear() }.show()
        }
        scope.launch {
            repository.state.collect { state ->
                start.isEnabled = state.runState == ObservationRunState.STOPPED
                stop.isEnabled = state.runState == ObservationRunState.RUNNING
                save.isEnabled = state.ready && !state.storageError && !exporting
                clear.isEnabled = state.ready && !exporting
                status.setText(when {
                    state.startFailed -> R.string.observation_start_failed
                    state.storageError -> R.string.observation_storage_error
                    state.runState == ObservationRunState.STARTING -> R.string.observation_starting
                    state.runState == ObservationRunState.RUNNING -> R.string.observation_running
                    else -> R.string.observation_stopped
                })
            }
        }
    }

    private fun hasPrecisePermission() = activity.checkSelfPermission(
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    private fun startObservation() {
        if (!activity.getSystemService(LocationManager::class.java).isLocationEnabled) {
            MaterialAlertDialogBuilder(activity).setMessage(R.string.observation_location_required)
                .setNegativeButton(R.string.wifi_cancel, null)
                .setPositiveButton(R.string.wifi_location_settings) { _, _ ->
                    try { activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    catch (_: RuntimeException) { toast(R.string.wifi_settings_unavailable) }
                }.show()
            return
        }
        repository.setRunState(ObservationRunState.STARTING)
        try { activity.startForegroundService(Intent(activity, NetworkObservationService::class.java)) }
        catch (error: RuntimeException) {
            repository.setRunState(ObservationRunState.STOPPED, failed = true)
            repository.record(ObservationEntry.now(
                "SERVICE_START_ERROR_${error.javaClass.simpleName}", "BLOCKED;diagnostic_only",
            ))
        }
    }

    private fun export() {
        exporting = true
        save.isEnabled = false
        clear.isEnabled = false
        scope.launch {
            try {
                val lines = repository.snapshot()
                withContext(Dispatchers.IO) { ObservationExport.save(activity.applicationContext, lines) }
                toast(R.string.observation_saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                toast(R.string.observation_export_failed)
            } finally {
                exporting = false
                save.isEnabled = repository.state.value.ready && !repository.state.value.storageError
                clear.isEnabled = repository.state.value.ready
            }
        }
    }

    private fun toast(message: Int) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    fun close() { scope.cancel() }
}
