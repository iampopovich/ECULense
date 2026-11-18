package io.github.iampopovich.eculense.core.datapacks

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.File

/**
 * filesDir/packs; постобработчики (OBDex-индекс и т. п.) регистрируют модули-читатели как PackPostProcessor;
 * модули с динамическим каталогом (модели ИИ) — PackSource, токены источников — PackAuth.
 */
val dataPacksModule = module {
    single { PackStore(File(androidContext().filesDir, "packs")) }
    single<PackHttp> { UrlConnectionHttp() }
    single { PackInstaller(get(), get(), getAll(), auth = getAll()) }
    single<PackDbOpener> { AndroidPackDb }
    single(createdAtStart = true) { DataPacks(androidContext(), get(), get(), sources = getAll()).also { it.scheduleUpdateCheck() } } bind DataPackService::class
}
