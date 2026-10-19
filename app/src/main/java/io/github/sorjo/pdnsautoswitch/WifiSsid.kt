package io.github.sorjo.pdnsautoswitch

/** Decode the framework representation once; SSIDs are case-sensitive and spaces are significant. */
object WifiSsid {
    fun fromFramework(raw: String?): String? {
        if (raw == null || raw == "<unknown ssid>") return null
        val name = if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
            raw.substring(1, raw.length - 1)
        } else raw
        return name.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }
}
