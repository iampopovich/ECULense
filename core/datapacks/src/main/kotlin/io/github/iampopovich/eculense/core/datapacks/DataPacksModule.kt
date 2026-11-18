package io.github.iampopovich.eculense.core.datapacks

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

/** filesDir/packs; постобработчики (OBDex-индекс и т. п.) регистрируют модули-читатели как PackPostProcessor. */
val dataPacksModule = module {
    single { PackStore(File(androidContext().filesDir, "packs")) }
    single<PackHttp> { UrlConnectionHttp() }
    single { PackInstaller(get(), get(), getAll()) }
    single<PackDbOpener> { AndroidPackDb }
    single(createdAtStart = true) { DataPacks(androidContext(), get(), get()).also { it.scheduleUpdateCheck() } }
}
