package io.github.iampopovich.eculense

import io.github.iampopovich.eculense.core.ailocal.aiLocalModule
import io.github.iampopovich.eculense.core.navigation.BuildCapabilities
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Сборка «Локально» (флейвор `local`): модель на телефоне (3.10, :core:ailocal) + облачные провайдеры. Единственное
 * место, где приложение знает о модуле; в флейворе `cloud` этот файл без :core:ailocal (см. src/cloud).
 */
val onDeviceModules: List<Module> = listOf(
    aiLocalModule,
    module { single { BuildCapabilities(onDeviceModels = true) } },
)
