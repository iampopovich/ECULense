package io.github.iampopovich.eculense

import io.github.iampopovich.eculense.core.ailocal.aiLocalModule
import io.github.iampopovich.eculense.core.navigation.BuildCapabilities
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * «Локально» build (`local` flavor): on-phone model (3.10, :core:ailocal) + cloud providers. The only
 * place where the app knows about the module; in the `cloud` flavor this file has no :core:ailocal (see src/cloud).
 */
val onDeviceModules: List<Module> = listOf(
    aiLocalModule,
    module { single { BuildCapabilities(onDeviceModels = true) } },
)
