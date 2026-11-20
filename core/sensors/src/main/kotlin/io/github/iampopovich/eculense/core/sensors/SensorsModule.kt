package io.github.iampopovich.eculense.core.sensors

import io.github.iampopovich.eculense.core.data.PhoneSensors
import io.github.iampopovich.eculense.core.obd.AppScope
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** E5-lite: phone accelerometer as a calibration sensor. Remove this module and the setting disappears. */
val sensorsModule = module {
    single<PhoneSensors> { AndroidPhoneSensors(androidContext()) }
    single(createdAtStart = true) {
        AccelCapture(androidContext(), get(), get(), get(), get(), get(), get(), get(AppScope)).also { it.start() }
    }
}
