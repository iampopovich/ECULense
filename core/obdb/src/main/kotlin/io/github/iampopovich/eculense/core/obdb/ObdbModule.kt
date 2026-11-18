package io.github.iampopovich.eculense.core.obdb

import org.koin.dsl.module

val obdbModule = module {
    single { ObdbSignalsets(get(), get()) }
}
