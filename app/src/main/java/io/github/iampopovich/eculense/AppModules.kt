package io.github.iampopovich.eculense

import io.github.iampopovich.eculense.core.bluetooth.bluetoothModule
import io.github.iampopovich.eculense.core.data.dataModule
import io.github.iampopovich.eculense.core.database.databaseModule
import io.github.iampopovich.eculense.core.demo.demoModule
import io.github.iampopovich.eculense.core.obd.obdModule
import io.github.iampopovich.eculense.core.service.serviceModule
import io.github.iampopovich.eculense.core.usb.usbModule
import io.github.iampopovich.eculense.feature.connect.connectModule
import io.github.iampopovich.eculense.feature.dashboard.dashboardModule
import org.koin.core.module.Module

/** Добавить/убрать фичу = добавить/убрать строку здесь и в settings.gradle.kts. */
val appModules: List<Module> = listOf(
    obdModule(includeEmulator = BuildConfig.DEBUG),
    bluetoothModule,
    usbModule,
    serviceModule,
    databaseModule,
    dataModule,
    connectModule,
    dashboardModule,
    // Демо-режим (виртуальный ELM327) — последним в списке транспортов, во всех сборках.
    demoModule,
)
