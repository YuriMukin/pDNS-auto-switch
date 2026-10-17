package io.github.sorjo.pdnsautoswitch

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class DnsTileService : TileService() {
    private val controller by lazy { DnsController(AndroidDnsSettings(this)) }
    private val observer by lazy { DnsSettingsObserver(this) { updateTile() } }
    private var listening = false

    override fun onStartListening() {
        super.onStartListening()
        listening = true
        observer.start()
        updateTile()
    }

    override fun onStopListening() {
        listening = false
        observer.stop()
        super.onStopListening()
    }

    override fun onDestroy() {
        observer.stop()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { toggleDns() } else toggleDns()
    }

    private fun toggleDns() {
        when (controller.toggle()) {
            DnsController.ToggleResult.SUCCESS -> Unit
            DnsController.ToggleResult.MISSING_PERMISSION,
            DnsController.ToggleResult.MISSING_PROVIDER,
            DnsController.ToggleResult.READ_FAILED -> openSetup()
            DnsController.ToggleResult.WRITE_FAILED -> {
                Toast.makeText(this, R.string.write_failed, Toast.LENGTH_LONG).show()
                openSetup()
            }
        }
        updateTile()
    }

    private fun updateTile() {
        if (!listening) return
        val tile = qsTile ?: return
        val state = controller.readState()
        tile.label = getString(R.string.tile_label)
        tile.state = when (state.mode) {
            DnsController.Mode.HOSTNAME -> Tile.STATE_ACTIVE
            DnsController.Mode.OFF, DnsController.Mode.AUTOMATIC -> Tile.STATE_INACTIVE
            DnsController.Mode.UNKNOWN -> Tile.STATE_INACTIVE
        }
        tile.subtitle = getString(when {
            !state.readable -> R.string.state_unknown
            !state.canWrite -> R.string.tile_setup
            state.mode != DnsController.Mode.HOSTNAME && state.provider.isBlank() -> R.string.tile_provider
            state.mode == DnsController.Mode.HOSTNAME -> R.string.state_on
            state.mode == DnsController.Mode.OFF -> R.string.state_off
            state.mode == DnsController.Mode.AUTOMATIC -> R.string.state_automatic
            else -> R.string.state_unknown
        })
        tile.contentDescription = getString(R.string.tile_description, tile.subtitle)
        tile.stateDescription = tile.subtitle
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated") // API 33 has no PendingIntent overload.
    private fun openSetup() {
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
