package io.github.iampopovich.eculense.feature.mqtt

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.iampopovich.eculense.core.mqtt.HaDiscovery
import io.github.iampopovich.eculense.core.secrets.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

/**
 * MQTT publishing settings (off by default). The password is never kept in plain text: [hasPassword] tells the UI
 * one is stored; [MqttSettingsSource.password] decrypts it only for a connection. Values are published in stored
 * (metric) units; HA converts for display.
 */
data class MqttSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val tls: Boolean = false,
    val username: String = "",
    val hasPassword: Boolean = false,
    val prefix: String = DEFAULT_PREFIX,
    val periodSec: Int = DEFAULT_PERIOD_SEC,
    val sendDashboard: Boolean = true,
    val sendBattery: Boolean = true,
    val sendConnection: Boolean = true,
    val sendDtcCount: Boolean = true,
) {
    /** The broker part of the settings: a change here invalidates the last «Check connection» result. */
    val brokerKey: String get() = "$host|$port|$tls|$username|$hasPassword"

    companion object {
        const val DEFAULT_PORT = 1883
        const val DEFAULT_TLS_PORT = 8883
        const val DEFAULT_PREFIX = "eculense"
        const val DEFAULT_PERIOD_SEC = 15

        /** Lower bound of the publish period: a K-Line car needs ~1.7 s per cycle, faster publishing only repeats values. */
        const val MIN_PERIOD_SEC = 5
        const val MAX_PERIOD_SEC = 3_600
    }
}

interface MqttSettingsSource {
    val settings: Flow<MqttSettings>
    suspend fun update(change: (MqttSettings) -> MqttSettings)

    /** null = keep the stored password; "" = remove it. */
    suspend fun setPassword(plain: String?)
    suspend fun password(): String?

    /** Random per-install id for the HA node id and the MQTT client id (never VIN, MAC or the car's name). */
    suspend fun installId(): String
}

internal class DataStoreMqttSettings(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val random: () -> String = ::randomId,
) : MqttSettingsSource {
    override val settings: Flow<MqttSettings> = store.data.map { p ->
        MqttSettings(
            enabled = p[ENABLED] ?: false,
            host = p[HOST].orEmpty(),
            port = p[PORT] ?: MqttSettings.DEFAULT_PORT,
            tls = p[TLS] ?: false,
            username = p[USERNAME].orEmpty(),
            hasPassword = !p[PASSWORD_CIPHER].isNullOrEmpty(),
            prefix = p[PREFIX] ?: MqttSettings.DEFAULT_PREFIX,
            periodSec = (p[PERIOD] ?: MqttSettings.DEFAULT_PERIOD_SEC).coerceIn(MqttSettings.MIN_PERIOD_SEC, MqttSettings.MAX_PERIOD_SEC),
            sendDashboard = p[SEND_DASHBOARD] ?: true,
            sendBattery = p[SEND_BATTERY] ?: true,
            sendConnection = p[SEND_CONNECTION] ?: true,
            sendDtcCount = p[SEND_DTC] ?: true,
        )
    }

    override suspend fun update(change: (MqttSettings) -> MqttSettings) {
        val next = change(settings.first())
        store.edit { p ->
            p[ENABLED] = next.enabled
            p[HOST] = next.host.trim()
            p[PORT] = next.port
            p[TLS] = next.tls
            p[USERNAME] = next.username.trim()
            p[PREFIX] = HaDiscovery.cleanPrefix(next.prefix)
            p[PERIOD] = next.periodSec.coerceIn(MqttSettings.MIN_PERIOD_SEC, MqttSettings.MAX_PERIOD_SEC)
            p[SEND_DASHBOARD] = next.sendDashboard
            p[SEND_BATTERY] = next.sendBattery
            p[SEND_CONNECTION] = next.sendConnection
            p[SEND_DTC] = next.sendDtcCount
        }
    }

    override suspend fun setPassword(plain: String?) {
        if (plain == null) return
        store.edit { p -> if (plain.isEmpty()) p.remove(PASSWORD_CIPHER) else p[PASSWORD_CIPHER] = cipher.encrypt(plain) }
    }

    override suspend fun password(): String? = store.data.first()[PASSWORD_CIPHER]?.takeIf { it.isNotEmpty() }?.let(cipher::decrypt)

    override suspend fun installId(): String {
        store.data.first()[INSTALL_ID]?.let { return it }
        var id = ""
        store.edit { p -> id = p[INSTALL_ID] ?: random().also { p[INSTALL_ID] = it } }
        return id
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val TLS = booleanPreferencesKey("tls")
        val USERNAME = stringPreferencesKey("username")

        // «password» in the key name: the .eculense backup strips it like API keys (PrefsFile.isSecret).
        val PASSWORD_CIPHER = stringPreferencesKey("password_cipher")
        val PREFIX = stringPreferencesKey("topic_prefix")
        val PERIOD = intPreferencesKey("period_sec")
        val SEND_DASHBOARD = booleanPreferencesKey("send_dashboard")
        val SEND_BATTERY = booleanPreferencesKey("send_battery")
        val SEND_CONNECTION = booleanPreferencesKey("send_connection")
        val SEND_DTC = booleanPreferencesKey("send_dtc_count")
        val INSTALL_ID = stringPreferencesKey("install_id")
    }
}

/** 12 hex digits from SecureRandom: unique enough per install, unrelated to the phone, the car or the adapter. */
internal fun randomId(): String {
    val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
    return bytes.joinToString("") { "%02x".format(it) }
}

/** In-memory settings for tests. */
class InMemoryMqttSettings(initial: MqttSettings = MqttSettings(), private var storedPassword: String? = null, private val id: String = "a1b2c3d4e5f6") : MqttSettingsSource {
    override val settings = MutableStateFlow(initial.copy(hasPassword = storedPassword != null))
    override suspend fun update(change: (MqttSettings) -> MqttSettings) {
        settings.value = change(settings.value).copy(hasPassword = storedPassword != null)
    }
    override suspend fun setPassword(plain: String?) {
        if (plain == null) return
        storedPassword = plain.ifEmpty { null }
        settings.value = settings.value.copy(hasPassword = storedPassword != null)
    }
    override suspend fun password(): String? = storedPassword
    override suspend fun installId(): String = id
}
