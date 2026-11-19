package io.github.iampopovich.eculense.core.ailocal

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.iampopovich.eculense.core.ai.OnDeviceLlm
import io.github.iampopovich.eculense.core.datapacks.PackAuth
import io.github.iampopovich.eculense.core.datapacks.PackSource
import io.github.iampopovich.eculense.core.navigation.LocalModelDestination
import io.github.iampopovich.eculense.core.navigation.RouteHandler
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation
import java.io.File

/**
 * 3.10 «На устройстве». Loaded after aiModule and dataPacksModule. The AI wizard sees the option via RouteHandler,
 * the :core:ai gateway via OnDeviceLlm, data packs via PackSource/PackAuth. Without the module all of this is simply absent.
 */
@OptIn(KoinExperimentalAPI::class)
val aiLocalModule = module {
    single { LocalModelStore(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("ailocal") }, get()) }
    single { LocalModels(androidContext(), get(), { get() }, get()) } bind PackSource::class
    single<PackAuth>(named("hf-token")) { get<LocalModels>() }
    single { DeviceProbe(androidContext()) }
    single { NanoProbe() }
    single { BenchRunner(androidContext()) }
    single { LocalLlmRuntime(get(), get(), File(androidContext().cacheDir, "llm"), androidContext().applicationInfo.nativeLibraryDir) } bind OnDeviceLlm::class
    single { LocalBench(get(), get(), get(), get(), get(), File(androidContext().cacheDir, "llm")) }
    viewModelOf(::LocalModelViewModel)
    navigation<LocalModelDestination> { LocalModelRoute() }
    single(named("ailocal-route")) { RouteHandler(LocalModelDestination::class) }
}
