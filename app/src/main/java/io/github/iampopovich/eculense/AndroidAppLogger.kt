package io.github.iampopovich.eculense

import android.util.Log
import io.github.iampopovich.eculense.core.model.AppLogger
import org.koin.dsl.module

/** [AppLogger] on logcat. */
internal object AndroidAppLogger : AppLogger {
    override fun warn(tag: String, message: String, error: Throwable?) {
        Log.w(tag, message, error)
    }

    override fun error(tag: String, message: String, error: Throwable?) {
        Log.e(tag, message, error)
    }
}

val appLoggerModule = module {
    single<AppLogger> { AndroidAppLogger }
}
