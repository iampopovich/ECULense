package io.github.iampopovich.eculense.core.dtcdb

import android.content.Context
import android.util.Log
import io.github.iampopovich.eculense.core.datapacks.DataPacks
import io.github.iampopovich.eculense.core.datapacks.PackCatalog
import io.github.iampopovich.eculense.core.datapacks.PackDbOpener
import io.github.iampopovich.eculense.core.datapacks.PackPostProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

/**
 * Расшифровки для экранов и ИИ: собирает [DtcCatalog] из того, что скачано сейчас (OBDex, заводские коды),
 * и встроенного CSV. После установки/удаления пакета каталог пересобирается при следующем запросе.
 */
class DtcDescriptions(
    private val context: Context,
    private val packs: DataPacks,
    private val opener: PackDbOpener,
) {
    private val mutex = Mutex()
    private var key: List<File?>? = null
    private var catalog: DtcCatalog? = null
    private var oemDb: Wal33dOemDtc? = null

    private val builtin: Map<String, String> by lazy {
        context.assets.open("dtc_codes.csv").bufferedReader().useLines { parseDtcCsv(it) }
    }

    suspend fun describe(code: String, make: String?): DtcInfo = catalog().describe(code, make)

    suspend fun describeAll(codes: Collection<String>, make: String?): Map<String, DtcInfo> {
        val c = catalog()
        return codes.associateWith { c.describe(it, make) }
    }

    private suspend fun catalog(): DtcCatalog = withContext(Dispatchers.IO) {
        mutex.withLock {
            val obdexDir = packs.installed(PackCatalog.OBDEX)?.dir
            val oemFile = packs.file(PackCatalog.OEM_DTC, OEM_FILE)
            val k = listOf(obdexDir, oemFile)
            catalog?.takeIf { key == k }?.let { return@withLock it }
            oemDb?.close()
            oemDb = null
            val generic = obdexDir?.let { dir ->
                runCatching {
                    val index = File(dir, ObdexIndex.INDEX)
                    if (!index.isFile && File(dir, ObdexIndex.SOURCE).isFile) ObdexIndex.process(dir)
                    ObdexReader(index)
                }.onFailure { Log.w(TAG, "OBDex index: ${it.message}") }.getOrNull()
            }
            val oem = oemFile?.let { f -> runCatching { Wal33dOemDtc(opener.open(f)) }.onFailure { Log.w(TAG, "OEM DTC: ${it.message}") }.getOrNull() }
            oemDb = oem
            DtcCatalog(generic, { builtin[it] }, oem, oem?.let { runCatching { it.manufacturers }.getOrDefault(emptySet()) } ?: emptySet())
                .also { catalog = it; key = k }
        }
    }

    companion object {
        const val OEM_FILE = "dtc_codes.db"
        private const val TAG = "EcuDtcDb"
    }
}

val dtcDbModule = module {
    single { DtcDescriptions(androidContext(), get(), get()) }
    single<PackPostProcessor>(named("obdex-index")) { ObdexIndex }
}
