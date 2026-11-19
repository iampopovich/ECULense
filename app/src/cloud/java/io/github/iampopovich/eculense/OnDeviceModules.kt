package io.github.iampopovich.eculense

import io.github.iampopovich.eculense.core.navigation.BuildCapabilities
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Cloud build (`cloud` flavor): no :core:ailocal and no LiteRT native libraries. Instead of the «На устройстве»
 * option, the AI wizard says that local models are available in the «Локально» build.
 */
val onDeviceModules: List<Module> = listOf(
    module { single { BuildCapabilities(onDeviceModels = false) } },
)
