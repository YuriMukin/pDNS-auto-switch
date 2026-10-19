package io.github.sorjo.pdnsautoswitch

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrustedWifiRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `exact SSIDs are deduplicated persisted and removed independently`() = runBlocking {
        val file = folder.root.resolve("trusted_wifi.preferences_pb")
        fun repository(scope: CoroutineScope) = TrustedWifiRepository(
            PreferenceDataStoreFactory.create(scope = scope) { file }
        )
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = repository(firstScope)
            assertTrue(repo.ssids.first().isEmpty())
            for (ssid in listOf("Home", "Home", "home", "Home ", "Дом", "\"quoted\"")) repo.add(ssid)
            assertEquals(setOf("Home", "home", "Home ", "Дом", "\"quoted\""), repo.ssids.first())
            repo.remove("Home")
            repo.remove("absent")
            assertEquals(setOf("home", "Home ", "Дом", "\"quoted\""), repo.ssids.first())
        } finally {
            firstScope.cancel()
            firstScope.coroutineContext[Job]!!.join()
        }
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            assertEquals(setOf("home", "Home ", "Дом", "\"quoted\""), repository(secondScope).ssids.first())
        } finally {
            secondScope.cancel()
            secondScope.coroutineContext[Job]!!.join()
        }
    }

    @Test fun `invalid names cannot enter storage`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = TrustedWifiRepository(PreferenceDataStoreFactory.create(scope = scope) {
                folder.root.resolve("invalid.preferences_pb")
            })
            for (ssid in listOf("", "  ", "<unknown ssid>")) {
                try { repo.add(ssid); fail("Invalid SSID accepted") }
                catch (_: IllegalArgumentException) { }
            }
            assertTrue(repo.ssids.first().isEmpty())
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
        }
    }
}
