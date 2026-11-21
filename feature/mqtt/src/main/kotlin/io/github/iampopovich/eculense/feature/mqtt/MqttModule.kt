package io.github.iampopovich.eculense.feature.mqtt

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.DashboardPrefs
import io.github.iampopovich.eculense.core.data.ExtendedSensors
import io.github.iampopovich.eculense.core.data.ExtendedStatus
import io.github.iampopovich.eculense.core.data.LastSeenStore
import io.github.iampopovich.eculense.core.model.AppBuild
import io.github.iampopovich.eculense.core.model.AppLogger
import io.github.iampopovich.eculense.core.mqtt.MqttClient
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.navigation.MqttSettingsDestination
import io.github.iampopovich.eculense.core.obd.AppScope
import io.github.iampopovich.eculense.core.secrets.KeystoreCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/**
 * R-F «Smart home (MQTT)» (Settings hub). Optional: remove this module (and :feature:mqtt / :core:mqtt) and nothing
 * else changes. The broker password uses its own Keystore key alias; the settings file `mqtt.preferences_pb` is
 * excluded from Android auto backup (app/src/main/res/xml) and the .eculense backup strips the password key.
 */
@OptIn(KoinExperimentalAPI::class)
val mqttModule = module {
    single<MqttSettingsSource> {
        DataStoreMqttSettings(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("mqtt") }, KeystoreCipher(alias = "eculense_mqtt_secret"))
    }
    single(createdAtStart = true) {
        val scope = get<CoroutineScope>(AppScope)
        val logger = getOrNull<AppLogger>() ?: AppLogger.NONE
        val lastSeen = getOrNull<LastSeenStore>()
        MqttBridge(
            controller = get(),
            settings = get(),
            dashboard = get<DashboardPrefs>(),
            activeCar = get<CarRepository>().observeActiveCar(),
            dtcCount = { id -> lastSeen?.observe(id)?.map { it.dtcCount } ?: flowOf(null) },
            extendedSignals = getOrNull<ExtendedSensors>()?.status?.map { (it as? ExtendedStatus.Ready)?.signals.orEmpty() } ?: flowOf(emptyList()),
            appVersion = getOrNull<AppBuild>()?.versionName ?: "dev",
            publisher = MqttClient(scope, log = { logger.info(MqttBridge.TAG, it) }),
            scope = scope,
            logger = logger,
        ).also { it.start() }
    }
    viewModel {
        val scope = get<CoroutineScope>(AppScope)
        MqttSettingsViewModel(
            store = get(),
            probe = { config -> MqttClient(scope).probe(config) },
            bridgeStatus = get<MqttBridge>().status,
            saveScope = scope,
        )
    }
    navigation<MqttSettingsDestination> { MqttSettingsRoute() }
    single(named("mqtt-hub")) {
        HubEntry(Hub.SETTINGS, MqttSettingsDestination, R.string.mqtt_hub_title, R.string.mqtt_hub_subtitle, R.drawable.ic_mqtt, order = 14)
    }
}
