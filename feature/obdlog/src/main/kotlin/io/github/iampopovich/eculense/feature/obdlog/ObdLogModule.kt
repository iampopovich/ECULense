package io.github.iampopovich.eculense.feature.obdlog

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.navigation.ObdLogDestination
import io.github.iampopovich.eculense.core.obd.AppScope
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/**
 * Adapter traffic log screen: an item of the Settings hub, right after «Адаптер». Reads the in-memory
 * ObdTrafficLog of :core:obd; removing this module leaves the log recording in memory with nobody to show it.
 */
@OptIn(KoinExperimentalAPI::class)
val obdLogModule = module {
    single<TrafficLogPrefs> {
        DataStoreTrafficLogPrefs(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("obd_traffic_log") })
    }
    single(createdAtStart = true) { TrafficLogPrefsApplier(get(), get(), get(AppScope)) }
    single { TrafficLogFiles(androidContext()) }
    viewModel { ObdLogViewModel(get(), get(), get()) }
    navigation<ObdLogDestination> { ObdLogRoute() }
    single(named("obdlog-hub")) {
        HubEntry(Hub.SETTINGS, ObdLogDestination, R.string.obdlog_hub_title, R.string.obdlog_hub_subtitle, R.drawable.ic_obdlog, order = 12)
    }
}
