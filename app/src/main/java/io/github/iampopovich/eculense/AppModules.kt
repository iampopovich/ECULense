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
import io.github.iampopovich.eculense.core.demo.demoModule
import io.github.iampopovich.eculense.core.obd.obdModule
import io.github.iampopovich.eculense.core.service.serviceModule
import io.github.iampopovich.eculense.core.usb.usbModule
import io.github.iampopovich.eculense.feature.aichat.aiChatModule
import io.github.iampopovich.eculense.feature.aisettings.aiSettingsModule
import io.github.iampopovich.eculense.feature.analysis.analysisModule
import io.github.iampopovich.eculense.feature.car.carModule
import io.github.iampopovich.eculense.feature.connect.connectModule
import io.github.iampopovich.eculense.feature.dashboard.dashboardModule
import io.github.iampopovich.eculense.feature.datapacks.dataPacksFeatureModule
import io.github.iampopovich.eculense.feature.dtc.dtcModule
import io.github.iampopovich.eculense.feature.maintenance.maintenanceModule
import io.github.iampopovich.eculense.feature.pitstop.pitstopModule
import io.github.iampopovich.eculense.feature.sessions.sessionsModule
import io.github.iampopovich.eculense.feature.settings.settingsModule
import io.github.iampopovich.eculense.feature.stats.statsModule
import org.koin.core.module.Module

/** Add/remove a feature = add/remove a line here and in settings.gradle.kts. */
val appModules: List<Module> = listOf(
    obdModule(includeEmulator = BuildConfig.DEBUG),
    bluetoothModule,
    usbModule,
    serviceModule,
    databaseModule,
    dataModule,
    // Background post-trip analysis and maintenance reminders (WorkManager + notifications); without it the data layer computes findings.
    backgroundModule,
    // On-demand data packs (reference catalogs, code databases) — before the modules that read them.
    dataPacksModule,
    vehicleDbModule,
    dtcDbModule,
    connectModule,
    dashboardModule,
    pitstopModule,
    settingsModule,
    dtcModule,
    sessionsModule,
    carModule,
    statsModule,
    analysisModule,
    maintenanceModule,
    dataPacksFeatureModule,
    // Car Scanner backup import (WorkManager) and ECULense backup; without them «Поездки»/«Статистика» have no import link.
    backupModule,
    backupFeatureModule,
    // Demo mode (virtual ELM327) — last in the transport list, in all builds.
    demoModule,
    // AI (stage 2): remove these lines + the modules in settings.gradle.kts/app → the app works without AI.
    aiModule,
    // Car data tools for AI (stage 3): without them chat works, but without data.
    aiToolsModule,
    aiSettingsModule,
    aiChatModule,
    // On-phone model (3.10): «На устройстве» provider, model downloads, speed test — see OnDeviceModules.kt.
) + onDeviceModules
