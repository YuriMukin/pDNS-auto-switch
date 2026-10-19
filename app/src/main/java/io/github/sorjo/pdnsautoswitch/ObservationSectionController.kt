package io.github.sorjo.pdnsautoswitch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ObservationSectionController(private val activity: AppCompatActivity) {
    private val repository = ObservationRepository.get(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val status = activity.findViewById<TextView>(R.id.observation_status)
    private val preview = activity.findViewById<TextView>(R.id.observation_preview)
    private val start = activity.findViewById<Button>(R.id.observation_start)
    private val stop = activity.findViewById<Button>(R.id.observation_stop)
    private val viewLog = activity.findViewById<Button>(R.id.observation_view_log)

    init {
        start.setOnClickListener {
            repository.setRunState(ObservationRunState.STARTING)
            try { activity.startForegroundService(Intent(activity, NetworkObservationService::class.java)) }
            catch (error: RuntimeException) {
                repository.setRunState(ObservationRunState.STOPPED, failed = true)
                repository.record(ObservationEntry.now(
                    "SERVICE_START_ERROR_${error.javaClass.simpleName}", "BLOCKED;diagnostic_only",
                ))
            }
        }
        stop.setOnClickListener { activity.stopService(Intent(activity, NetworkObservationService::class.java)) }
        viewLog.setOnClickListener { openLog() }
        scope.launch {
            repository.state.collect { state ->
                start.isEnabled = state.runState == ObservationRunState.STOPPED
                stop.isEnabled = state.runState == ObservationRunState.RUNNING
                viewLog.isEnabled = state.ready
                status.setText(when {
                    state.startFailed -> R.string.observation_start_failed
                    state.runState == ObservationRunState.STARTING -> R.string.observation_starting
                    state.runState == ObservationRunState.RUNNING -> R.string.observation_running
                    else -> R.string.observation_stopped
                })
                preview.text = if (state.storageError) activity.getString(R.string.observation_storage_error)
                else state.lines.takeLast(2).asReversed().joinToString("\n\n").ifBlank {
                    activity.getString(R.string.observation_empty)
                }
            }
        }
    }

    private fun openLog() {
        val lines = repository.state.value.lines.joinToString("\n")
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.observation_log_title)
            .setMessage(lines.ifBlank { activity.getString(R.string.observation_empty) })
            .setPositiveButton(R.string.observation_close, null)
            .setNeutralButton(R.string.observation_copy) { _, _ ->
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText(activity.getString(R.string.observation_log_title), lines)
                )
            }
            .setNegativeButton(R.string.observation_clear) { _, _ ->
                MaterialAlertDialogBuilder(activity).setTitle(R.string.observation_clear_title)
                    .setMessage(R.string.observation_clear_message)
                    .setNegativeButton(R.string.wifi_cancel, null)
                    .setPositiveButton(R.string.observation_clear) { _, _ -> repository.clear() }.show()
            }.show()
    }

    fun close() { scope.cancel() }
}
