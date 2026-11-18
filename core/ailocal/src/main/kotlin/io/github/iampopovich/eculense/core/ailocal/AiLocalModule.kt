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
 * 3.10 «На устройстве». Подключается после aiModule и dataPacksModule. Мастер ИИ видит пункт по RouteHandler,
 * шлюз :core:ai — по OnDeviceLlm, пакеты — по PackSource/PackAuth. Без модуля всё это просто отсутствует.
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
