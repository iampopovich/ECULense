package io.github.iampopovich.eculense

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class EcuLenseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Вспомогательные процессы (тест локальной модели `:bench` — нативный код может упасть или съесть память)
        // не поднимают граф приложения: там нет WorkManager, базы и Koin не нужен.
        if (!isMainProcess()) return
        startKoin {
            androidLogger()
            androidContext(this@EcuLenseApplication)
            modules(appModules)
        }
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            getSystemService(ActivityManager::class.java)?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        return name == null || name == packageName
    }
}
