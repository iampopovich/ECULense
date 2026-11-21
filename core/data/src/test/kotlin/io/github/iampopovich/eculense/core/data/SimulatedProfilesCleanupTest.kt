package io.github.iampopovich.eculense.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.iampopovich.eculense.core.obd.AdapterProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** R-D item 0: learned profiles of simulated links left by earlier builds are removed once; real ones stay. */
class SimulatedProfilesCleanupTest {
    private val real = "profile|bt|00:11:22:33:44:55|car=1"
    private val realWifi = "profile|wifi|192.168.0.10:35000|car=1"
    private val doomed = listOf(
        "profile|demo|Virtual ELM327 · CAN 11/500 · no car|car=1",
        "profile|demo-flaky|Virtual ELM327 clone|car=1",
        "profile|demo-engine-off|x|car=2",
        "profile|demo-ble|Virtual ELM327 · BLE FFF0|car=1",
        "profile|replay|traffic log|car=1",
        "profile|wifi|10.0.2.2:35000|car=1",
    )

    @Test
    fun `only keys of simulated kinds are selected`() {
        val selected = SimulatedProfilesCleanup.simulatedKeys(doomed + listOf(real, realWifi, "protocol", "extra_init", "suggested_wifi"))
        assertEquals(doomed.toSet(), selected)
    }

    @Test
    fun `migration removes them, sets the flag and is not run again`() = runBlocking {
        val prefs = mutablePreferencesOf()
        (doomed + real + realWifi).forEach { prefs[stringPreferencesKey(it)] = "6;1;STI,VTI" }
        prefs[stringPreferencesKey("protocol")] = "5"
        assertTrue(SimulatedProfilesCleanup.shouldMigrate(prefs))
        val migrated = SimulatedProfilesCleanup.migrate(prefs)
        assertEquals(setOf(real, realWifi, "protocol", "cleanup_version"), migrated.asMap().keys.map { it.name }.toSet())
        assertEquals(1, migrated[SimulatedProfilesCleanup.VERSION_KEY])
        assertFalse(SimulatedProfilesCleanup.shouldMigrate(migrated))
    }

    @Test
    fun `the DataStore runs it on first access`() = runBlocking {
        val dir = Files.createTempDirectory("adapter-prefs").toFile()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val file = File(dir, "adapter.preferences_pb")
            // Seed the file as an older build left it (no migration).
            val seed = PreferenceDataStoreFactory.create(scope = scope) { file }
            seed.edit { p -> (doomed + real).forEach { p[stringPreferencesKey(it)] = "6;1;STI,VTI" } }
        } finally {
            scope.cancel()
        }
        val scope2 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(migrations = listOf(SimulatedProfilesCleanup), scope = scope2) { File(dir, "adapter.preferences_pb") }
            val prefs = DataStoreAdapterPrefs(store) { 1L }
            assertEquals(AdapterProfile(protocol = '6', countSuffix = true, unsupported = setOf("STI", "VTI")), prefs.profile("bt|00:11:22:33:44:55"))
            val names = store.data.first().asMap().keys.map { it.name }.toSet()
            assertEquals(setOf(real, "cleanup_version"), names)
        } finally {
            scope2.cancel()
            dir.deleteRecursively()
        }
    }
}
