package io.github.iampopovich.eculense.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.EndpointLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * C5 «Automation» group of the adapter screen. Everything is off by default except remembering the last adapter.
 * [askCar] = C8: ask «which car?» when a recording starts and there is more than one car.
 */
data class AutomationSettings(
    val rememberLast: Boolean = true,
    val autoConnect: Boolean = false,
    val autoRecord: Boolean = false,
    val askCar: Boolean = true,
)

interface AutomationPrefs {
    val settings: Flow<AutomationSettings>
    suspend fun update(transform: (AutomationSettings) -> AutomationSettings)

    /** The last real adapter that connected successfully (never a demo or replay). */
    val lastAdapter: Flow<AdapterEndpoint?>
    suspend fun setLastAdapter(endpoint: AdapterEndpoint?)
}

class InMemoryAutomationPrefs(initial: AutomationSettings = AutomationSettings()) : AutomationPrefs {
    private val state = MutableStateFlow(initial)
    private val last = MutableStateFlow<AdapterEndpoint?>(null)
    override val settings: Flow<AutomationSettings> = state
    override suspend fun update(transform: (AutomationSettings) -> AutomationSettings) = state.update(transform)
    override val lastAdapter: Flow<AdapterEndpoint?> = last
    override suspend fun setLastAdapter(endpoint: AdapterEndpoint?) {
        last.value = endpoint
    }
}

internal class DataStoreAutomationPrefs(private val store: DataStore<Preferences>) : AutomationPrefs {
    override val settings: Flow<AutomationSettings> = store.data.map { p ->
        val d = AutomationSettings()
        AutomationSettings(
            rememberLast = p[REMEMBER] ?: d.rememberLast,
            autoConnect = p[AUTO_CONNECT] ?: d.autoConnect,
            autoRecord = p[AUTO_RECORD] ?: d.autoRecord,
            askCar = p[ASK_CAR] ?: d.askCar,
        )
    }

    override suspend fun update(transform: (AutomationSettings) -> AutomationSettings) {
        val next = transform(settings.first())
        store.edit { p ->
            p[REMEMBER] = next.rememberLast
            p[AUTO_CONNECT] = next.autoConnect
            p[AUTO_RECORD] = next.autoRecord
            p[ASK_CAR] = next.askCar
            if (!next.rememberLast) p.remove(LAST)
        }
    }

    override val lastAdapter: Flow<AdapterEndpoint?> = store.data.map { p -> p[LAST]?.let(::decodeEndpoint) }

    override suspend fun setLastAdapter(endpoint: AdapterEndpoint?) {
        store.edit { p -> if (endpoint == null) p.remove(LAST) else p[LAST] = encodeEndpoint(endpoint) }
    }

    internal companion object {
        private val REMEMBER = booleanPreferencesKey("remember_last")
        private val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        private val AUTO_RECORD = booleanPreferencesKey("auto_record")
        private val ASK_CAR = booleanPreferencesKey("ask_car")
        private val LAST = stringPreferencesKey("last_adapter")
        private const val SEP = '\u001F'

        fun encodeEndpoint(e: AdapterEndpoint): String = listOf(e.kind, e.address, e.label, e.labelKey?.name.orEmpty()).joinToString(SEP.toString())

        fun decodeEndpoint(s: String): AdapterEndpoint? {
            val parts = s.split(SEP)
            if (parts.size != 4 || parts[0].isBlank()) return null
            val key = parts[3].takeIf { it.isNotEmpty() }?.let { k -> EndpointLabel.entries.firstOrNull { it.name == k } }
            return AdapterEndpoint(parts[0], parts[1], parts[2], key)
        }
    }
}
