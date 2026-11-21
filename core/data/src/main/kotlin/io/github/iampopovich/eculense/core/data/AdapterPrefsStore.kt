package io.github.iampopovich.eculense.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.iampopovich.eculense.core.obd.AdaptiveTiming
import io.github.iampopovich.eculense.core.obd.AdapterPrefs
import io.github.iampopovich.eculense.core.obd.AdapterProfile
import io.github.iampopovich.eculense.core.obd.AdapterSettings
import io.github.iampopovich.eculense.core.obd.InitCommands
import io.github.iampopovich.eculense.core.obd.ObdProtocols
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Adapter settings (A11) and per-adapter profiles (A5/A6/A7) in DataStore. Profiles are kept per adapter AND
 * active car: the protocol belongs to the car, the unsupported commands to the adapter, so a profile learned with
 * one car is not reused for another.
 */
internal class DataStoreAdapterPrefs(
    private val store: DataStore<Preferences>,
    private val activeCarId: suspend () -> Long?,
) : AdapterPrefs {
    override val settings: Flow<AdapterSettings> = store.data.map { p ->
        AdapterSettings(
            protocol = p[PROTOCOL]?.singleOrNull()?.takeIf(ObdProtocols::isKnown),
            extraInit = InitCommands.valid(p[EXTRA_INIT].orEmpty()),
            adaptiveTiming = p[TIMING]?.let { runCatching { AdaptiveTiming.valueOf(it) }.getOrNull() } ?: AdaptiveTiming.AUTO,
        )
    }

    override suspend fun setSettings(settings: AdapterSettings) {
        store.edit { p ->
            settings.protocol?.let { p[PROTOCOL] = it.toString() } ?: p.remove(PROTOCOL)
            p[EXTRA_INIT] = settings.extraInit.joinToString("\n")
            p[TIMING] = settings.adaptiveTiming.name
        }
    }

    override suspend fun profile(adapterKey: String): AdapterProfile? =
        store.data.first()[profileKey(adapterKey)]?.let(::decode)

    override suspend fun saveProfile(adapterKey: String, profile: AdapterProfile) {
        val key = profileKey(adapterKey)
        store.edit { it[key] = encode(profile) }
    }

    override val suggestedWifi: Flow<String?> = store.data.map { it[SUGGESTED_WIFI] }

    override suspend fun setSuggestedWifi(address: String?) {
        store.edit { p -> address?.let { p[SUGGESTED_WIFI] = it } ?: p.remove(SUGGESTED_WIFI) }
    }

    private suspend fun profileKey(adapterKey: String) =
        stringPreferencesKey("profile|$adapterKey|car=${runCatching { activeCarId() }.getOrNull() ?: 0}")

    internal companion object {
        private val PROTOCOL = stringPreferencesKey("protocol")
        private val EXTRA_INIT = stringPreferencesKey("extra_init")
        private val TIMING = stringPreferencesKey("adaptive_timing")
        private val SUGGESTED_WIFI = stringPreferencesKey("suggested_wifi")

        /** "<protocol or ->;<suffix 1/0/->;<unsupported, comma separated>". */
        fun encode(p: AdapterProfile): String = listOf(
            p.protocol?.toString() ?: "-",
            when (p.countSuffix) { true -> "1"; false -> "0"; null -> "-" },
            p.unsupported.sorted().joinToString(","),
        ).joinToString(";")

        fun decode(text: String): AdapterProfile? {
            val parts = text.split(';')
            if (parts.size != 3) return null
            return AdapterProfile(
                protocol = parts[0].singleOrNull()?.takeIf(ObdProtocols::isKnown),
                countSuffix = when (parts[1]) { "1" -> true; "0" -> false; else -> null },
                unsupported = parts[2].split(',').filter { it.isNotBlank() }.toSet(),
            )
        }
    }
}
