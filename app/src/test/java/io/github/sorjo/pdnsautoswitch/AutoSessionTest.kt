package io.github.sorjo.pdnsautoswitch

import org.junit.Assert.*
import org.junit.Test

class AutoSessionTest {
    private class Settings : DnsSettings {
        var mode = "hostname"
        var provider = "dns.example.com"
        var permission = true
        var accepted = true
        var persist = true
        var denied = false
        var revokeOnWrite = false
        var mutateProvider = false
        var writes = 0
        override fun read(key: String): String? {
            if (denied) throw SecurityException()
            return when (key) { DnsController.MODE -> mode; DnsController.PROVIDER -> provider; else -> null }
        }
        override fun canWrite() = permission
        override fun write(key: String, value: String): Boolean {
            if (revokeOnWrite) throw SecurityException()
            assertEquals(DnsController.MODE, key)
            writes++
            if (accepted && persist) mode = value
            if (mutateProvider) provider = "changed.example.com"
            return accepted
        }
    }
    private val trusted = PolicyDecision(PolicyDecision.Mode.OFF, PolicyDecision.Reason.TRUSTED_WIFI, PolicyDecision.Mode.OFF)
    private val mobile = PolicyDecision(PolicyDecision.Mode.HOSTNAME, PolicyDecision.Reason.MOBILE, PolicyDecision.Mode.HOSTNAME)

    @Test fun `repeated desired modes do not write again or invert settings`() {
        val settings = Settings()
        val dns = DnsController(settings)
        repeat(4) { assertEquals(DnsController.ToggleResult.SUCCESS, dns.applyMode(DnsController.Mode.OFF)) }
        assertEquals(1, settings.writes)
        repeat(4) { dns.applyMode(DnsController.Mode.HOSTNAME) }
        assertEquals(2, settings.writes)
        assertEquals("dns.example.com", settings.provider)
    }

    @Test fun `auto starts disabled and requires valid settings`() {
        val settings = Settings()
        val dns = DnsController(settings)
        val auto = AutoSession { 0 }
        assertNull(auto.apply(trusted, dns))
        settings.permission = false
        auto.enable(dns)
        assertEquals(DnsController.ToggleResult.MISSING_PERMISSION, auto.state.value.error)
        settings.permission = true
        settings.provider = " "
        auto.enable(dns)
        assertEquals(DnsController.ToggleResult.MISSING_PROVIDER, auto.state.value.error)
        settings.denied = true
        auto.enable(dns)
        assertEquals(DnsController.ToggleResult.READ_FAILED, auto.state.value.error)
        assertEquals(0, settings.writes)
    }

    @Test fun `manual pause survives network changes and needs explicit expiry reconciliation`() {
        var now = 100L
        val auto = AutoSession { now }
        val settings = Settings()
        val dns = DnsController(settings)
        auto.enable(dns)
        auto.pauseForManualChange()
        dns.toggle()
        now += AutoSession.DEFAULT_PAUSE_MINUTES * 60_000L - 1
        assertFalse(auto.resumeIfDue())
        assertNull(auto.apply(mobile, dns))
        assertNull(auto.apply(trusted, dns))
        now++
        // The service must refresh network callbacks when resumeIfDue returns true.
        assertNull(auto.apply(mobile, dns))
        assertTrue(auto.resumeIfDue())
        assertFalse(auto.resumeIfDue())
        auto.apply(mobile, dns)
        assertEquals("hostname", settings.mode)
        assertEquals(2, settings.writes)
    }

    @Test fun `another manual action extends pause and disabling cancels it`() {
        var now = 0L
        val auto = AutoSession { now }
        auto.enable(DnsController(Settings()))
        auto.pauseForManualChange()
        now = 300_000
        auto.pauseForManualChange()
        now = 600_000
        assertFalse(auto.resumeIfDue())
        assertEquals(900_000L, auto.state.value.pausedUntilMs)
        auto.disable()
        now = 1_000_000
        assertFalse(auto.resumeIfDue())
        assertFalse(auto.state.value.enabled)
        assertFalse(AutoSession { now }.state.value.enabled)
    }

    @Test fun `write failures and revoked permission disable auto without retries`() {
        for (failure in 0..4) {
            val settings = Settings()
            val dns = DnsController(settings)
            val auto = AutoSession { 0 }
            auto.enable(dns)
            when (failure) {
                0 -> settings.accepted = false
                1 -> settings.persist = false
                2 -> settings.permission = false
                3 -> settings.revokeOnWrite = true
                4 -> settings.denied = true
            }
            assertNotEquals(DnsController.ToggleResult.SUCCESS, auto.apply(trusted, dns))
            assertFalse(auto.state.value.enabled)
            val writes = settings.writes
            assertNull(auto.apply(trusted, dns))
            assertEquals(writes, settings.writes)
        }
    }

    @Test fun `provider removal blocks hostname and provider races fail verification`() {
        val settings = Settings()
        val dns = DnsController(settings)
        val auto = AutoSession { 0 }
        auto.enable(dns)
        settings.mode = "off"
        settings.provider = ""
        assertEquals(DnsController.ToggleResult.MISSING_PROVIDER, auto.apply(mobile, dns))
        assertEquals(0, settings.writes)
        settings.provider = "dns.example.com"
        settings.mutateProvider = true
        assertEquals(DnsController.ToggleResult.WRITE_FAILED, dns.applyMode(DnsController.Mode.HOSTNAME))
    }


    @Test fun `every offered duration expires at its exact monotonic deadline`() {
        assertEquals(listOf(1, 5, 10, 15, 20, 25, 30), AutoSession.PAUSE_MINUTES)
        for (minutes in AutoSession.PAUSE_MINUTES) {
            var now = 42L
            val auto = AutoSession { now }
            auto.setPauseMinutes(minutes)
            auto.enable(DnsController(Settings()))
            auto.pauseForManualChange()
            now += minutes * 60_000L - 1
            assertFalse(auto.resumeIfDue())
            now++
            assertTrue(auto.resumeIfDue())
        }
    }

    @Test fun `changing duration preserves active deadline and affects the next manual action`() {
        var now = 100L
        val auto = AutoSession { now }
        auto.enable(DnsController(Settings()))
        auto.pauseForManualChange()
        val original = auto.state.value.pausedUntilMs
        auto.setPauseMinutes(1)
        assertEquals(original, auto.state.value.pausedUntilMs)
        now = 1000L
        auto.pauseForManualChange()
        assertEquals(61_000L, auto.state.value.pausedUntilMs)
    }

    @Test fun `invalid saved durations fall back to ten and invalid assignments are rejected`() {
        for (value in listOf(-1, 0, 2, 31, Int.MAX_VALUE)) {
            assertEquals(10, AutoSession.restoredPauseMinutes(value))
            val auto = AutoSession { 0 }
            assertThrows(IllegalArgumentException::class.java) { auto.setPauseMinutes(value) }
            assertEquals(10, auto.pauseMinutes)
        }
        for (value in AutoSession.PAUSE_MINUTES) assertEquals(value, AutoSession.restoredPauseMinutes(value))
    }
}
