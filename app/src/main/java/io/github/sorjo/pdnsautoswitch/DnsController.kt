package io.github.sorjo.pdnsautoswitch

/** System settings are the only source of truth; provider hostname is never overwritten. */
class DnsController(private val settings: DnsSettings) {
    enum class Mode { OFF, HOSTNAME, AUTOMATIC, UNKNOWN }
    data class State(
        val mode: Mode,
        val provider: String,
        val canWrite: Boolean,
        val readable: Boolean = true,
    )
    enum class ToggleResult { SUCCESS, MISSING_PERMISSION, MISSING_PROVIDER, READ_FAILED, WRITE_FAILED }

    fun readState(): State = try {
        val value = settings.read(MODE) ?: settings.read(DEFAULT_MODE) ?: "opportunistic"
        State(
            mode = when (value) {
                "off" -> Mode.OFF
                "hostname" -> Mode.HOSTNAME
                "opportunistic" -> Mode.AUTOMATIC
                else -> Mode.UNKNOWN
            },
            provider = settings.read(PROVIDER).orEmpty().trim(),
            canWrite = settings.canWrite(),
        )
    } catch (_: SecurityException) {
        State(Mode.UNKNOWN, "", false, readable = false)
    }

    fun toggle(): ToggleResult {
        val state = readState()
        if (!state.readable) return ToggleResult.READ_FAILED
        if (!state.canWrite) return ToggleResult.MISSING_PERMISSION
        val desired = if (state.mode == Mode.HOSTNAME) "off" else "hostname"
        if (desired == "hostname" && state.provider.isBlank()) return ToggleResult.MISSING_PROVIDER
        return try {
            if (settings.write(MODE, desired) && settings.read(MODE) == desired) {
                ToggleResult.SUCCESS
            } else {
                ToggleResult.WRITE_FAILED
            }
        } catch (_: SecurityException) {
            ToggleResult.MISSING_PERMISSION
        }
    }

    companion object {
        const val MODE = "private_dns_mode"
        const val DEFAULT_MODE = "private_dns_default_mode"
        const val PROVIDER = "private_dns_specifier"
    }
}

interface DnsSettings {
    fun read(key: String): String?
    fun write(key: String, value: String): Boolean
    fun canWrite(): Boolean
}
