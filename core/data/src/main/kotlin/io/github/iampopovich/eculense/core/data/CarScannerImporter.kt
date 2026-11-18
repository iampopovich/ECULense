package io.github.iampopovich.eculense.core.data

import android.content.Context
import android.net.Uri
import io.github.iampopovich.eculense.core.carscanner.BrcReader
import io.github.iampopovich.eculense.core.carscanner.BrcRecord
import io.github.iampopovich.eculense.core.carscanner.CarScannerSensors
import io.github.iampopovich.eculense.core.carscanner.CbzBackup
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.SessionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.InputStream

data class ImportReport(val imported: Int, val skippedDuplicates: Int, val failed: List<String>)

/**
 * open — как открыть выбранный пользователем Uri; вынесено в параметр, чтобы юнит-тесты обходились без Context.
 */
class CarScannerImporter internal constructor(
    private val open: (Uri) -> InputStream?,
    private val sessions: SessionRepository,
    private val dtcHistory: DtcRepository,
) {
    constructor(context: Context, sessions: SessionRepository, dtcHistory: DtcRepository) :
        this({ uri: Uri -> context.contentResolver.openInputStream(uri) }, sessions, dtcHistory)

    private val dtcCode = Regex("\"Code\":\"([A-Z0-9]{5})\"")
    private val dtcRequest = Regex("\"RequestCommand\":\"([0-9A-F]{2})\"")

    /**
     * uri — полный бэкап .cbz или одиночная запись .brc.
     * onRecording(n) вызывается после каждой обработанной записи (n — сколько записей уже обработано).
     */
    suspend fun import(uri: Uri, fileName: String, onRecording: (Int) -> Unit = {}): ImportReport = withContext(Dispatchers.IO) {
        val input = open(uri) ?: error("Не удалось открыть файл")
        importStream(input, fileName, onRecording)
    }

    internal suspend fun importStream(input: InputStream, fileName: String, onRecording: (Int) -> Unit = {}): ImportReport {
        var imported = 0
        var duplicates = 0
        val failed = mutableListOf<String>()
        suspend fun one(name: String, bytes: ByteArray) {
            try {
                if (importRecording(name, bytes)) imported++ else duplicates++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += "$name: ${e.message}"
            }
            onRecording(imported + duplicates + failed.size)
        }
        input.buffered().use { stream ->
            if (fileName.endsWith(".brc", ignoreCase = true)) one(fileName, stream.readBytes())
            else CbzBackup.forEachRecording(stream) { name, bytes -> one(name, bytes) }
        }
        return ImportReport(imported, duplicates, failed)
    }

    /** false — запись уже импортирована раньше. Битая запись не оставляет полусессию: её externalId не должен блокировать повторный импорт. */
    internal suspend fun importRecording(fileName: String, bytes: ByteArray): Boolean {
        val externalId = "carscanner:$fileName"
        if (sessions.hasExternal(externalId)) return false
        val reader = BrcReader(bytes)
        val header = reader.header
        val id = sessions.start(
            name = "Car Scanner · ${fileName.removeSuffix(".brc")}",
            source = SessionSource.IMPORT_CARSCANNER,
            vin = header.vin.ifBlank { null },
            startedAtMs = header.startedAtMs,
            externalId = externalId,
        )
        try {
            importRecords(id, reader)
        } catch (e: Throwable) {
            withContext(NonCancellable) { sessions.delete(id) }
            throw e
        }
        return true
    }

    private suspend fun importRecords(id: Long, reader: BrcReader) {
        val header = reader.header
        val formats = mutableMapOf<Int, Int>()
        val batch = ArrayList<SensorReading>(BATCH)
        val dtcs = mutableListOf<Pair<Long, Dtc>>()
        var lastMs = header.startedAtMs
        reader.records().forEach { record ->
            when (record) {
                is BrcRecord.SensorDef -> formats[record.id] = record.format
                is BrcRecord.DataPoint -> if (record.value.isFinite() && CarScannerSensors.isImported(record.sensorId)) {
                    val at = header.startedAtMs + (record.secondsFromStart * 1000).toLong()
                    lastMs = maxOf(lastMs, at)
                    batch += SensorReading(
                        CarScannerSensors.sensorId(record.sensorId),
                        record.value,
                        CarScannerSensors.unit(formats[record.sensorId] ?: -1),
                        at,
                    )
                    if (batch.size == BATCH) {
                        sessions.append(id, batch.toList())
                        batch.clear()
                    }
                }
                is BrcRecord.DtcEvent -> dtcCode.find(record.json)?.groupValues?.get(1)?.let { code ->
                    val kind = when (dtcRequest.find(record.json)?.groupValues?.get(1)) {
                        "07" -> DtcKind.PENDING
                        "0A" -> DtcKind.PERMANENT
                        else -> DtcKind.STORED
                    }
                    dtcs += record.atMs to Dtc(code, kind)
                }
                is BrcRecord.GpsPoint -> Unit // GPS-трек не импортируем (приватность), бэклог 1.24 п.11
            }
        }
        if (batch.isNotEmpty()) sessions.append(id, batch)
        sessions.finish(id, endedAtMs = lastMs)
        // Car Scanner пишет один код дважды (запрос без заголовков и с ними) — схлопываем в пределах записи
        dtcs.distinctBy { it.second }.forEach { (at, dtc) -> dtcHistory.saveSnapshot(listOf(dtc), vin = null, takenAtMs = at) }
    }

    private companion object {
        const val BATCH = 2_000
    }
}
