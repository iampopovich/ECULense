package io.github.iampopovich.eculense

import io.github.iampopovich.eculense.core.ai.aiModule
import io.github.iampopovich.eculense.core.backup.backupModule
import io.github.iampopovich.eculense.feature.backup.backupFeatureModule
import io.github.iampopovich.eculense.core.aitools.aiToolsModule
import io.github.iampopovich.eculense.core.background.backgroundModule
import io.github.iampopovich.eculense.core.bluetooth.bluetoothModule
import io.github.iampopovich.eculense.core.data.dataModule
import io.github.iampopovich.eculense.core.datapacks.dataPacksModule
import io.github.iampopovich.eculense.core.vehicledb.vehicleDbModule
import io.github.iampopovich.eculense.core.dtcdb.dtcDbModule
import io.github.iampopovich.eculense.core.database.databaseModule
import io.github.iampopovich.eculense.core.obd.obdModule
import io.github.iampopovich.eculense.core.service.serviceModule
import io.github.iampopovich.eculense.core.sensors.sensorsModule
import io.github.iampopovich.eculense.core.usb.usbModule
import io.github.iampopovich.eculense.core.wifi.wifiModule
import io.github.iampopovich.eculense.core.ble.bleModule
import io.github.iampopovich.eculense.feature.aichat.aiChatModule
import io.github.iampopovich.eculense.feature.aisettings.aiSettingsModule
import io.github.iampopovich.eculense.feature.analysis.analysisModule
import io.github.iampopovich.eculense.feature.car.carModule
import io.github.iampopovich.eculense.feature.connect.connectModule
import io.github.iampopovich.eculense.feature.obdlog.obdLogModule
import io.github.iampopovich.eculense.feature.cardata.carDataModule
import io.github.iampopovich.eculense.feature.dashboard.dashboardModule
import io.github.iampopovich.eculense.feature.datapacks.dataPacksFeatureModule
import io.github.iampopovich.eculense.feature.dtc.dtcModule
import io.github.iampopovich.eculense.feature.health.healthModule
import io.github.iampopovich.eculense.feature.maintenance.maintenanceModule
import io.github.iampopovich.eculense.feature.fuel.fuelModule
import io.github.iampopovich.eculense.feature.pitstop.pitstopModule
import io.github.iampopovich.eculense.feature.sessions.sessionsModule
import io.github.iampopovich.eculense.feature.settings.settingsModule
import io.github.iampopovich.eculense.feature.stats.statsModule
import io.github.iampopovich.eculense.feature.widget.widgetModule
import io.github.iampopovich.eculense.feature.mqtt.mqttModule
import io.github.iampopovich.eculense.core.model.AppBuild
import org.koin.core.module.Module
import org.koin.dsl.module

/** Add/remove a feature = add/remove a line here and in settings.gradle.kts. */
val appModules: List<Module> = listOf(
    // Version and flavor for «About» and problem reports (C11).
    module { single { AppBuild(BuildConfig.VERSION_NAME, BuildConfig.FLAVOR) } },
    // Current app language (BCP-47) for AI prompts: the answer language follows the in-app language.
    appLanguageModule,
    // Logcat implementation of the injectable AppLogger (R3.1).
    appLoggerModule,
    // Developer Wi-Fi endpoints (PC emulator) only in debug builds (DevModules.kt per build type).
    obdModule(extraWifiEndpoints = devWifiEndpoints),
    bluetoothModule,
    // A: Bluetooth LE adapters (GATT serial); without it the Connect screen has no BLE section.
    bleModule,
    usbModule,
    // B: Wi-Fi adapter sockets bound to the adapter's Wi-Fi network; without it they use the default network.
    wifiModule,
    serviceModule,
    databaseModule,
    dataModule,
    // E5-lite phone accelerometer logging during recordings (threshold calibration); without it the setting is hidden.
    sensorsModule,
    // Background post-trip analysis and maintenance reminders (WorkManager + notifications); without it the data layer computes findings.
    backgroundModule,
    // On-demand data packs (reference catalogs, code databases) — before the modules that read them.
    dataPacksModule,
    vehicleDbModule,
    dtcDbModule,
    connectModule,
    // Adapter traffic log screen (Settings hub); without it the log still records in memory, unseen.
    obdLogModule,
    dashboardModule,
    // Car data screen (C1, Pit-stop hub + dashboard button); without it the dashboard keeps its default PID set.
    carDataModule,
    pitstopModule,
    settingsModule,
    dtcModule,
    sessionsModule,
    carModule,
    statsModule,
    analysisModule,
    maintenanceModule,
    fuelModule,
    dataPacksFeatureModule,
    // Car Scanner backup import (WorkManager) and ECULense backup; without them «Поездки»/«Статистика» have no import link.
    backupModule,
    backupFeatureModule,
    // B6 quick health check + report (Pit-stop hub); without it nothing else changes.
    healthModule,
    // C9 home-screen widget: live values or the last voltage, code check and trip; without it no widget.
    widgetModule,
    // R-F «Smart home (MQTT)»: optional publishing to the user's own broker + Home Assistant discovery; without it
    // nothing else changes (remove the line + :feature:mqtt/:core:mqtt in settings.gradle.kts and app).
    mqttModule,
    // AI (stage 2): remove these lines + the modules in settings.gradle.kts/app → the app works without AI.
    aiModule,
    // Car data tools for AI (stage 3): without them chat works, but without data.
    aiToolsModule,
    aiSettingsModule,
    aiChatModule,
    // On-phone model (3.10): «На устройстве» provider, model downloads, speed test — see OnDeviceModules.kt.
    // R-I: demo adapters and replay — debug builds only (DevModules.kt in app/src/debug; empty in app/src/release).
) + onDeviceModules + devModules
