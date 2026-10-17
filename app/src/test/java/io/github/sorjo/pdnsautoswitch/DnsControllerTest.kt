package io.github.sorjo.pdnsautoswitch

import org.junit.Assert.*
import org.junit.Test

class DnsControllerTest {
    private class FakeSettings(
        mode: String? = "hostname",
        provider: String? = "dns.example.com",
        var permission: Boolean = true,
    ) : DnsSettings {
        val values = mutableMapOf(DnsController.MODE to mode, DnsController.PROVIDER to provider)
        val writes = mutableListOf<Pair<String, String>>()
        var accepted = true
        var persist = true
        var revokeOnWrite = false
        var denyRead = false
        override fun read(key: String): String? {
            if (denyRead) throw SecurityException()
            return values[key]
        }
        override fun write(key: String, value: String): Boolean {
            if (revokeOnWrite) throw SecurityException()
            writes += key to value
            if (accepted && persist) values[key] = value
            return accepted
        }
        override fun canWrite() = permission
    }

    @Test fun `round trip preserves configured provider and writes mode only`() {
        val settings = FakeSettings()
        val controller = DnsController(settings)
        assertEquals(DnsController.ToggleResult.SUCCESS, controller.toggle())
        assertEquals(DnsController.Mode.OFF, controller.readState().mode)
        assertEquals(DnsController.ToggleResult.SUCCESS, controller.toggle())
        assertEquals(DnsController.Mode.HOSTNAME, controller.readState().mode)
        assertEquals("dns.example.com", settings.values[DnsController.PROVIDER])
        assertEquals(listOf(DnsController.MODE to "off", DnsController.MODE to "hostname"), settings.writes)
    }

    @Test fun `missing permission prevents any write`() {
        val settings = FakeSettings(permission = false)
        assertEquals(DnsController.ToggleResult.MISSING_PERMISSION, DnsController(settings).toggle())
        assertTrue(settings.writes.isEmpty())
    }

    @Test fun `blank provider cannot enable strict mode`() {
        for (provider in listOf(null, "", "  ")) {
            val settings = FakeSettings(mode = "off", provider = provider)
            assertEquals(DnsController.ToggleResult.MISSING_PROVIDER, DnsController(settings).toggle())
            assertTrue(settings.writes.isEmpty())
        }
    }

    @Test fun `can disable strict mode even if provider was removed`() {
        val settings = FakeSettings(provider = null)
        assertEquals(DnsController.ToggleResult.SUCCESS, DnsController(settings).toggle())
        assertEquals("off", settings.values[DnsController.MODE])
    }

    @Test fun `automatic mode enables saved provider`() {
        val settings = FakeSettings(mode = "opportunistic")
        val controller = DnsController(settings)
        assertEquals(DnsController.Mode.AUTOMATIC, controller.readState().mode)
        assertEquals(DnsController.ToggleResult.SUCCESS, controller.toggle())
        assertEquals("hostname", settings.values[DnsController.MODE])
    }

    @Test fun `external settings change is reflected on next read and toggle`() {
        val settings = FakeSettings()
        val controller = DnsController(settings)
        assertEquals(DnsController.Mode.HOSTNAME, controller.readState().mode)
        settings.values[DnsController.MODE] = "off"
        assertEquals(DnsController.Mode.OFF, controller.readState().mode)
        controller.toggle()
        assertEquals("hostname", settings.values[DnsController.MODE])
    }

    @Test fun `missing explicit mode uses system default then automatic fallback`() {
        val settings = FakeSettings(mode = null)
        val controller = DnsController(settings)
        assertEquals(DnsController.Mode.AUTOMATIC, controller.readState().mode)
        settings.values[DnsController.DEFAULT_MODE] = "off"
        assertEquals(DnsController.Mode.OFF, controller.readState().mode)
    }

    @Test fun `unknown mode is not reported as off`() {
        assertEquals(DnsController.Mode.UNKNOWN, DnsController(FakeSettings(mode = "future")).readState().mode)
    }

    @Test fun `write rejection and missing readback never report success`() {
        for (persist in listOf(false, true)) {
            val settings = FakeSettings().apply { this.persist = persist; accepted = !persist }
            assertEquals(DnsController.ToggleResult.WRITE_FAILED, DnsController(settings).toggle())
        }
    }

    @Test fun `permission revoked between check and write is handled`() {
        val settings = FakeSettings().apply { revokeOnWrite = true }
        assertEquals(DnsController.ToggleResult.MISSING_PERMISSION, DnsController(settings).toggle())
    }

    @Test fun `read failure blocks writes`() {
        val settings = FakeSettings().apply { denyRead = true }
        val controller = DnsController(settings)
        assertFalse(controller.readState().readable)
        assertEquals(DnsController.ToggleResult.READ_FAILED, controller.toggle())
        assertTrue(settings.writes.isEmpty())
    }
}
