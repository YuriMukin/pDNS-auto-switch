package io.github.sorjo.pdnsautoswitch

import androidx.appcompat.app.AppCompatActivity
import android.app.StatusBarManager
import com.google.android.material.button.MaterialButton
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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.color.DynamicColors

class MainActivity : AppCompatActivity() {
    private var setupExpanded: Boolean? = null
    private var setupRequired = false
    private lateinit var wifiSection: WifiSectionController
    private val controller by lazy { DnsController(AndroidDnsSettings(this)) }
    private val observer by lazy { DnsSettingsObserver(this) { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupExpanded = savedInstanceState?.getBoolean("setup_expanded")
        val content = findViewById<View>(R.id.main_content)
        val padding = (20 * resources.displayMetrics.density).toInt()
        content.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        content.requestApplyInsets()
        wifiSection = WifiSectionController(this)
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
        findViewById<Button>(R.id.expand_setup).setOnClickListener {
            setupExpanded = !(setupExpanded ?: setupRequired)
            updateSetupVisibility()
        }
        val appearance = getSharedPreferences(DnsApplication.APPEARANCE, MODE_PRIVATE)
        findViewById<MaterialSwitch>(R.id.dynamic_color).apply {
            isChecked = appearance.getBoolean(DnsApplication.DYNAMIC_COLOR, true)
            isEnabled = DynamicColors.isDynamicColorAvailable()
            setOnCheckedChangeListener { _, enabled ->
                appearance.edit().putBoolean(DnsApplication.DYNAMIC_COLOR, enabled).apply()
                recreate()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        observer.start()
        refresh()
    }

    override fun onPause() {
        observer.stop()
        wifiSection.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        wifiSection.close()
        super.onDestroy()
    }

    private fun refresh() {
        val state = controller.readState()
        val modeText = getString(when (state.mode) {
            DnsController.Mode.HOSTNAME -> R.string.state_on
            DnsController.Mode.OFF -> R.string.state_off
            DnsController.Mode.AUTOMATIC -> R.string.state_automatic
            DnsController.Mode.UNKNOWN -> R.string.state_unknown
        })
        findViewById<TextView>(R.id.dns_state).text = modeText
        findViewById<TextView>(R.id.provider).text = state.provider.ifBlank { getString(R.string.provider_empty) }
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
        val commandVisibility = if (state.canWrite) View.GONE else View.VISIBLE
        findViewById<View>(R.id.adb_command).visibility = commandVisibility
        findViewById<View>(R.id.copy_command).visibility = commandVisibility
        setupRequired = !state.readable || !state.canWrite || state.provider.isBlank()
        updateSetupVisibility()
        findViewById<Button>(R.id.toggle_dns).apply {
            isEnabled = state.readable && state.canWrite &&
                (state.mode == DnsController.Mode.HOSTNAME || state.provider.isNotBlank())
            setText(if (state.mode == DnsController.Mode.HOSTNAME) R.string.turn_off else R.string.turn_on)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("setup_expanded", setupExpanded ?: setupRequired)
        super.onSaveInstanceState(outState)
    }

    private fun updateSetupVisibility() {
        val expanded = setupExpanded ?: setupRequired
        findViewById<View>(R.id.setup_card).visibility = if (expanded) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.expand_setup).apply {
            setText(when {
                setupRequired && !expanded -> R.string.setup_needed
                expanded -> R.string.setup_collapse
                else -> R.string.setup_done
            })
            setIconResource(if (expanded) R.drawable.ic_collapse else R.drawable.ic_expand)
            contentDescription = getString(if (expanded) R.string.setup_collapse else R.string.setup_title)
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
