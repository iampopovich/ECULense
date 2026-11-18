package io.github.iampopovich.eculense

import io.github.iampopovich.eculense.core.navigation.BuildCapabilities
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Облачная сборка (флейвор `cloud`): без :core:ailocal и нативных библиотек LiteRT. Мастер ИИ вместо пункта
 * «На устройстве» пишет, что локальные модели есть в сборке «Локально».
 */
val onDeviceModules: List<Module> = listOf(
    module { single { BuildCapabilities(onDeviceModels = false) } },
)
