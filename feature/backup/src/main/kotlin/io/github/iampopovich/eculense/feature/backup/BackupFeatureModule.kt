package io.github.iampopovich.eculense.feature.backup

import io.github.iampopovich.eculense.core.navigation.BackupDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.navigation.RouteHandler
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** «Импорт и резервные копии» — пункт хаба «Настройки»; RouteHandler включает ссылки из «Поездок» и «Статистики». */
@OptIn(KoinExperimentalAPI::class)
val backupFeatureModule = module {
    single<BackupFolder> { SafBackupFolder(androidContext()) }
    viewModel { BackupViewModel(get(), get(), get(), get(), get()) }
    navigation<BackupDestination> { BackupRoute() }
    single(named("backup-hub")) {
        HubEntry(Hub.SETTINGS, BackupDestination, "Импорт и резервные копии", "Бэкапы Car Scanner, копия данных ECULense", R.drawable.ic_backup, order = 25)
    }
    single(named("backup-route")) { RouteHandler(BackupDestination::class) }
}
