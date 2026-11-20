package io.github.iampopovich.eculense.feature.widget

import io.github.iampopovich.eculense.core.obd.AppScope
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** C9 home-screen widget. Removing this module (and the line in AppModules) removes the widget and its updates. */
val widgetModule = module {
    single(createdAtStart = true) {
        WidgetRepository(androidContext(), get(), get(), get(), get(), get(), get(AppScope)).also { it.start() }
    }
}
