package io.github.sorjo.pdnsautoswitch

import android.app.Activity
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val controller by lazy { DnsController(AndroidDnsSettings(this)) }
    private val observer by lazy { DnsSettingsObserver(this) { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val content = findViewById<View>(R.id.main_content)
        val padding = (24 * resources.displayMetrics.density).toInt()
        content.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        content.requestApplyInsets()
        findViewById<Button>(R.id.toggle_dns).setOnClickListener {
            val message = when (controller.toggle()) {
                DnsController.ToggleResult.SUCCESS -> null
                DnsController.ToggleResult.MISSING_PERMISSION -> R.string.permission_help
                DnsController.ToggleResult.MISSING_PROVIDER -> R.string.provider_help
                DnsController.ToggleResult.READ_FAILED -> R.string.read_failed
                DnsController.ToggleResult.WRITE_FAILED -> R.string.write_failed
            }
            refresh()
            if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.copy_command).setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText(getString(R.string.adb_command_label), grantCommand())
            )
        }
        findViewById<Button>(R.id.open_settings).setOnClickListener { openDnsSettings() }
        findViewById<Button>(R.id.add_tile).setOnClickListener { addTile() }
        findViewById<TextView>(R.id.adb_command).text = grantCommand()
    }

    override fun onResume() {
        super.onResume()
        observer.start()
        refresh()
    }

    override fun onPause() {
        observer.stop()
        super.onPause()
    }

    private fun refresh() {
        val state = controller.readState()
        val modeText = getString(when (state.mode) {
            DnsController.Mode.HOSTNAME -> R.string.state_on
            DnsController.Mode.OFF -> R.string.state_off
            DnsController.Mode.AUTOMATIC -> R.string.state_automatic
            DnsController.Mode.UNKNOWN -> R.string.state_unknown
        })
        findViewById<TextView>(R.id.dns_state).text = getString(R.string.current_state, modeText)
        findViewById<TextView>(R.id.provider).text = getString(
            R.string.provider_value, state.provider.ifBlank { getString(R.string.not_configured) }
        )
        findViewById<TextView>(R.id.permission_status).setText(
            if (state.canWrite) R.string.permission_granted else R.string.permission_required
        )
        val help = when {
            !state.readable -> getString(R.string.read_failed)
            !state.canWrite -> getString(R.string.permission_help)
            state.provider.isBlank() -> getString(R.string.provider_help)
            else -> getString(R.string.ready_help)
        }
        findViewById<TextView>(R.id.setup_help).text = help
        findViewById<Button>(R.id.toggle_dns).apply {
            isEnabled = state.readable && state.canWrite &&
                (state.mode == DnsController.Mode.HOSTNAME || state.provider.isNotBlank())
            setText(if (state.mode == DnsController.Mode.HOSTNAME) R.string.turn_off else R.string.turn_on)
        }
    }

    private fun grantCommand() = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"

    private fun addTile() {
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, DnsTileService::class.java),
            getString(R.string.tile_label), Icon.createWithResource(this, R.drawable.ic_dns_tile),
            mainExecutor,
        ) { result ->
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.tile_added
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.tile_already_added
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> R.string.tile_not_added
                else -> R.string.tile_add_failed
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun openDnsSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }
}
