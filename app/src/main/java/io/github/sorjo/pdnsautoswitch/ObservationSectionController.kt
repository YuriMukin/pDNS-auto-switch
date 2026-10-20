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
import com.google.android.material.textfield.MaterialAutoCompleteTextView
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
    private var pendingAuto = false
    private var renderingAuto = false
    private val autoSwitch = activity.findViewById<MaterialSwitch>(R.id.auto_enabled)
    private val autoStatus = activity.findViewById<TextView>(R.id.auto_status)
    private val permissionRequest = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasPrecisePermission()) startObservation()
        else { pendingAuto = false; toast(R.string.observation_permission_required) }
    }

    init {
        val durations = AutoSession.PAUSE_MINUTES
        val durationLabels = durations.map { activity.getString(R.string.auto_pause_minutes, it) }
        activity.findViewById<MaterialAutoCompleteTextView>(R.id.pause_duration).apply {
            setSimpleItems(durationLabels.toTypedArray())
            setText(activity.getString(R.string.auto_pause_minutes, AutoRuntime.session.pauseMinutes), false)
            setOnItemClickListener { _, _, position, _ ->
                AutoRuntime.setPauseMinutes(activity, durations[position])
            }
        }
        autoSwitch.setOnCheckedChangeListener { _, enabled ->
            if (renderingAuto) return@setOnCheckedChangeListener
            if (!enabled) AutoRuntime.session.disable()
            else {
                pendingAuto = true
                requestStart()
                renderAuto()
            }
        }
        scope.launch { AutoRuntime.session.state.collect { renderAuto() } }
        start.setOnClickListener { pendingAuto = false; requestStart() }
        stop.setOnClickListener {
            AutoRuntime.session.disable()
            activity.stopService(Intent(activity, NetworkObservationService::class.java))
        }
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

    private fun renderAuto() {
        val state = AutoRuntime.session.state.value
        activity.findViewById<TextView>(R.id.control_mode).setText(when {
            state.pausedUntilMs != null -> R.string.control_auto_paused
            state.enabled -> R.string.control_auto
            else -> R.string.manual_mode
        })
        renderingAuto = true
        autoSwitch.isChecked = state.enabled
        renderingAuto = false
        autoStatus.setText(when (state.error) {
            DnsController.ToggleResult.MISSING_PERMISSION -> R.string.auto_permission_error
            DnsController.ToggleResult.MISSING_PROVIDER -> R.string.auto_provider_error
            DnsController.ToggleResult.READ_FAILED -> R.string.auto_read_error
            DnsController.ToggleResult.WRITE_FAILED -> R.string.auto_write_error
            else -> when {
                state.pausedUntilMs != null -> R.string.auto_paused
                state.enabled -> R.string.auto_running
                else -> R.string.auto_stopped
            }
        })
        state.pausedUntilMs?.let { deadline ->
            val until = java.util.Date(System.currentTimeMillis() +
                (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
            autoStatus.text = activity.getString(R.string.auto_paused_until,
                android.text.format.DateFormat.getTimeFormat(activity).format(until))
        }
    }

    private fun requestStart() {
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
        if (repository.state.value.runState != ObservationRunState.RUNNING)
            repository.setRunState(ObservationRunState.STARTING)
        try {
            activity.startForegroundService(Intent(activity, NetworkObservationService::class.java).apply {
                if (pendingAuto) action = NetworkObservationService.ACTION_ENABLE_AUTO
            })
            pendingAuto = false
        }
        catch (error: RuntimeException) {
            pendingAuto = false
            AutoRuntime.session.disable()
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
