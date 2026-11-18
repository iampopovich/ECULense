package io.github.iampopovich.eculense.core.vehicledb

import io.github.iampopovich.eculense.core.datapacks.DataPacks
import io.github.iampopovich.eculense.core.datapacks.PackCatalog
import io.github.iampopovich.eculense.core.datapacks.PackDbOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.dsl.module
import java.io.File

/** Справочник доступен, только если пакет скачан; база открывается лениво и переоткрывается после обновления версии. */
class VehicleRepository(private val packs: DataPacks, private val opener: PackDbOpener) {
    private val mutex = Mutex()
    private var open: Pair<File, VehicleDb>? = null

    val available: Flow<Boolean> = packs.observe(PackCatalog.VEHICLES).map { it?.installed != null }.distinctUntilChanged()

    fun isAvailable(): Boolean = packs.file(PackCatalog.VEHICLES, FILE) != null

    /** null — справочник не скачан (или схема релиза изменилась: тогда в лог, а пользователь вводит вручную). */
    suspend fun <T> query(block: (VehicleDb) -> T): T? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = packs.file(PackCatalog.VEHICLES, FILE)
            if (file == null) {
                open?.second?.close()
                open = null
                return@withLock null
            }
            val db = open?.takeIf { it.first == file }?.second ?: run {
                open?.second?.close()
                VehicleDb(opener.open(file)).also { db ->
                    val problems = db.schemaProblems()
                    if (problems.isNotEmpty()) {
                        db.close()
                        throw IllegalStateException("Формат справочника изменился: ${problems.joinToString()}")
                    }
                    open = file to db
                }
            }
            block(db)
        }
    }

    companion object {
        const val FILE = "vehicles.sqlite"
    }
}

val vehicleDbModule = module {
    single { VehicleRepository(get(), get()) }
}
