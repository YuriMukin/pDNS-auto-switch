package io.github.sorjo.pdnsautoswitch

import org.junit.Assert.*
import org.junit.Test

class WifiSsidTest {
    @Test fun `framework quotes are removed once`() {
        assertEquals("Home", WifiSsid.fromFramework("\"Home\""))
        assertEquals("\"Home\"", WifiSsid.fromFramework("\"\"Home\"\""))
    }

    @Test fun `missing redacted and empty names cannot be trusted`() {
        for (raw in listOf(null, "", "\"\"", "<unknown ssid>", "\"<unknown ssid>\"", "   ")) {
            assertNull(WifiSsid.fromFramework(raw))
        }
    }

    @Test fun `SSID case unicode and significant spaces are preserved`() {
        assertEquals(" Дом 5Гц ", WifiSsid.fromFramework("\" Дом 5Гц \""))
        assertNotEquals(WifiSsid.fromFramework("Home"), WifiSsid.fromFramework("home"))
        assertNotEquals(WifiSsid.fromFramework("Home"), WifiSsid.fromFramework("Home "))
    }
}
