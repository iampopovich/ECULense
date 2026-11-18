package io.github.iampopovich.eculense

import io.github.iampopovich.eculense.core.ai.aiModule
import io.github.iampopovich.eculense.core.aitools.aiToolsModule
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
import io.github.iampopovich.eculense.feature.pitstop.pitstopModule
import io.github.iampopovich.eculense.feature.sessions.sessionsModule
import io.github.iampopovich.eculense.feature.settings.settingsModule
import io.github.iampopovich.eculense.feature.stats.statsModule
import org.koin.core.module.Module

/** Добавить/убрать фичу = добавить/убрать строку здесь и в settings.gradle.kts. */
val appModules: List<Module> = listOf(
    obdModule(includeEmulator = BuildConfig.DEBUG),
    bluetoothModule,
    usbModule,
    serviceModule,
    databaseModule,
    dataModule,
    // Пакеты данных по запросу (справочники, базы кодов) — до модулей-читателей.
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
    dataPacksFeatureModule,
    // Демо-режим (виртуальный ELM327) — последним в списке транспортов, во всех сборках.
    demoModule,
    // ИИ (этап 2): убрать эти строки + модули в settings.gradle.kts/app → приложение работает без ИИ.
    aiModule,
    // Инструменты данных машины для ИИ (этап 3): без них чат работает, но без данных.
    aiToolsModule,
    aiSettingsModule,
    aiChatModule,
)
