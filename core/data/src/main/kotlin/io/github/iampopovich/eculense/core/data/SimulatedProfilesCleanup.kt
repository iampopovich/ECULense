package io.github.iampopovich.eculense.core.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey

/**
 * R-D item 0: one-time removal of learned adapter profiles of simulated links from the `adapter` DataStore. Before
 * 1041965 every demo / replay / PC-emulator connect stored `profile|<kind>|<address>|car=N` for the active car. The
 * cleanup runs once (versioned flag [VERSION_KEY]); it only removes keys, never touches settings or real profiles.
 */
internal object SimulatedProfilesCleanup : DataMigration<Preferences> {
    const val VERSION = 1
    val VERSION_KEY = intPreferencesKey("cleanup_version")

    /** Kinds without a real car behind them (see AdapterEndpoint.isSimulated); the PC emulator is a wifi address. */
    private const val DEMO_PREFIX = "demo"
    private const val REPLAY_KIND = "replay"
    private const val PC_EMULATOR = "wifi|10.0.2.2:"

    /** The stored key names that belong to simulated links. */
    fun simulatedKeys(names: Collection<String>): Set<String> = names.filter { name ->
        if (!name.startsWith("profile|")) return@filter false
        val rest = name.removePrefix("profile|")
        val kind = rest.substringBefore('|')
        kind.startsWith(DEMO_PREFIX) || kind == REPLAY_KIND || rest.startsWith(PC_EMULATOR)
    }.toSet()

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = (currentData[VERSION_KEY] ?: 0) < VERSION

    override suspend fun migrate(currentData: Preferences): Preferences {
        val updated = currentData.toMutablePreferences()
        val doomed = simulatedKeys(currentData.asMap().keys.map { it.name })
        for (key in currentData.asMap().keys) if (key.name in doomed) updated.remove(key)
        updated[VERSION_KEY] = VERSION
        return updated.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
