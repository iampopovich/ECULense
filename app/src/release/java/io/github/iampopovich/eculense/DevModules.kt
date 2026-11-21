package io.github.iampopovich.eculense

import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import org.koin.core.module.Module

/** R-I: release builds ship no developer endpoints (demo adapters, replay, PC emulator); see app/src/debug. */
val devModules: List<Module> = emptyList()

val devWifiEndpoints: List<AdapterEndpoint> = emptyList()
