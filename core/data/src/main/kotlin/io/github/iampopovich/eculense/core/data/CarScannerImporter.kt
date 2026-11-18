package io.github.iampopovich.eculense.core.data

import android.content.Context
import android.net.Uri
import io.github.iampopovich.eculense.core.carscanner.BrcReader
import io.github.iampopovich.eculense.core.carscanner.BrcRecord
import io.github.iampopovich.eculense.core.carscanner.CarScannerBackupMeta
import io.github.iampopovich.eculense.core.carscanner.CarScannerSensors
import io.github.iampopovich.eculense.core.carscanner.CarScannerSettings
import io.github.iampopovich.eculense.core.carscanner.CbzBackup
import io.github.iampopovich.eculense.core.carscanner.CbzSettingsReader
import io.github.iampopovich.eculense.core.carscanner.SqliteOpener
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.SessionSource
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import io.github.iampopovich.eculense.core.model.UnitsSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** Что перенесено из бэкапа: записи (как в 1.21) + машина, единицы, цена топлива, поездки (1.25). */
data class ImportReport(
    val imported: Int,
    val skippedDuplicates: Int,
    val failed: List<String>,
    val car: ImportedCar? = null,
    val tripsImported: Int = 0,
    val tripsSkipped: Int = 0,
    val unitsImported: Boolean = false,
    /** Новые события DTC, в т. ч. дозаписанные в уже импортированные записи. */
    val dtcEventsAdded: Int = 0,
)

/** created = false — машина уже была (повторный импорт) или заполнена машина по умолчанию. */
data class ImportedCar(
    val name: String,
    val brand: String?,
    val year: Int?,
    val fuelPrice: Double?,
    val currency: String?,
    val created: Boolean,
)

/**
 * open — как открыть выбранный пользователем Uri; вынесено в параметр, чтобы юнит-тесты обходились без Context.
 * .cbz читается дважды: сначала настройки и drivecyclesv2.db (машина нужна до записей), потом записи .brc.
 */
class CarScannerImporter internal constructor(
    private val open: (Uri) -> InputStream?,
    private val sessions: SessionRepository,
    private val dtcHistory: DtcRepository,
    private val cars: CarRepository,
    private val trips: TripRepository,
    private val units: UnitsRepository,
    private val sqlite: SqliteOpener,
    private val tempDir: File,
    /** После импорта (пересчёт аналитики); не блокирует результат. */
    private val afterImport: (ImportReport) -> Unit = {},
) {
    constructor(
        context: Context,
        sessions: SessionRepository,
        dtcHistory: DtcRepository,
        cars: CarRepository,
        trips: TripRepository,
        units: UnitsRepository,
        afterImport: (ImportReport) -> Unit = {},
    ) : this(
        { uri: Uri -> context.contentResolver.openInputStream(uri) },
        sessions, dtcHistory, cars, trips, units, AndroidSqlite, context.cacheDir, afterImport,
    )

    private val dtcCode = Regex("\"Code\":\"([A-Z0-9]{5})\"")
    private val dtcRequest = Regex("\"RequestCommand\":\"([0-9A-F]{2})\"")

    /**
     * uri — полный бэкап .cbz или одиночная запись .brc.
     * onRecording(n) вызывается после каждой обработанной записи (n — сколько записей уже обработано).
     */
    suspend fun import(uri: Uri, fileName: String, onRecording: (Int) -> Unit = {}): ImportReport = withContext(Dispatchers.IO) {
        importStream({ open(uri) ?: error("Не удалось открыть файл") }, fileName, onRecording).also(afterImport)
    }

    internal suspend fun importStream(input: () -> InputStream, fileName: String, onRecording: (Int) -> Unit = {}): ImportReport {
        val isBrc = fileName.endsWith(".brc", ignoreCase = true)
        val failed = mutableListOf<String>()
        var meta: CarScannerBackupMeta? = null
        if (!isBrc) {
            try {
                meta = input().buffered().use { CbzSettingsReader.readMeta(it, tempDir, sqlite) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += "настройки и поездки: ${e.message}"
            }
        }
        val carMapping = meta?.let { importCars(it) } ?: CarMapping.EMPTY
        val unitsImported = meta?.settings?.takeIf { it.hasUnits }?.let { units.save(it.toUnits()); true } ?: false
        var imported = 0
        var duplicates = 0
        var dtcAdded = 0
        suspend fun one(name: String, bytes: ByteArray) {
            try {
                val result = importRecording(name, bytes, carMapping)
                dtcAdded += result.dtcAdded
                if (result.created) imported++ else duplicates++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += "$name: ${e.message}"
            }
            onRecording(imported + duplicates + failed.size)
        }
        input().buffered().use { stream ->
            if (isBrc) one(fileName, stream.readBytes())
            else CbzBackup.forEachRecording(stream) { name, bytes -> one(name, bytes) }
        }
        val tripList = meta?.trips.orEmpty().mapNotNull { t ->
            val carId = carMapping.byExternal[t.carId] ?: carMapping.first ?: return@mapNotNull null
            Trip(
                carId = carId,
                startedAtMs = t.startedUtcMs,
                endedAtMs = t.finishedUtcMs,
                distanceKm = t.distanceKm,
                fuelL = t.fuelL,
                fuelPricePerL = t.fuelPricePerL,
                drivingMs = t.drivingMs,
                standingMs = t.standingMs,
                source = TripSource.IMPORT_CARSCANNER,
                externalId = "carscanner:${t.carId ?: 0}:${t.startedUtcMs}",
            )
        }
        val tripsAdded = if (tripList.isEmpty()) 0 else trips.addAll(tripList)
        return ImportReport(
            imported = imported,
            skippedDuplicates = duplicates,
            failed = failed,
            car = carMapping.report,
            tripsImported = tripsAdded,
            tripsSkipped = tripList.size - tripsAdded,
            unitsImported = unitsImported,
            dtcEventsAdded = dtcAdded,
        )
    }

    private data class RecordingResult(val created: Boolean, val dtcAdded: Int)

    private class CarMapping(val byExternal: Map<Long, Long>, val byName: Map<String, Long>, val first: Long?, val report: ImportedCar?) {
        companion object {
            val EMPTY = CarMapping(emptyMap(), emptyMap(), null, null)
        }
    }

    /**
     * Машины Car Scanner → наши. Уже импортированная (тот же externalId) только дополняется пустыми полями — правки
     * пользователя не перетираем. Нетронутая машина по умолчанию (единственная) заполняется данными из бэкапа.
     */
    private suspend fun importCars(meta: CarScannerBackupMeta): CarMapping {
        if (meta.cars.isEmpty()) return CarMapping.EMPTY
        val s = meta.settings
        val byExternal = mutableMapOf<Long, Long>()
        val byName = mutableMapOf<String, Long>()
        var report: ImportedCar? = null
        meta.cars.forEachIndexed { index, cs ->
            val externalId = "carscanner:${cs.id}"
            val existing = cars.byExternalId(externalId)
            val all = cars.cars()
            val adopt = all.singleOrNull()?.takeIf { existing == null && index == 0 && it.isUntouchedDefault }
            val base = existing ?: adopt
            val name = cs.name.ifBlank { listOfNotNull(s.brand, cs.year?.toString()).joinToString(" ").ifBlank { "Car Scanner" } }
            val merged = (base ?: CarProfile(name = name)).let { car ->
                car.copy(
                    name = if (existing != null) car.name else name,
                    externalId = externalId,
                    year = car.year ?: cs.year,
                    // настройки Car Scanner общие для приложения — относим их к первой машине
                    brand = car.brand ?: s.brand.takeIf { index == 0 },
                    diagProfile = car.diagProfile ?: s.diagProfile.takeIf { index == 0 },
                    fuelPrice = car.fuelPrice ?: s.fuelPricePerL.takeIf { index == 0 },
                    currency = car.currency ?: s.currency.takeIf { index == 0 },
                )
            }
            val id = cars.save(merged)
            if (adopt != null) cars.setActive(id)
            byExternal[cs.id] = id
            byName.putIfAbsent(merged.name, id)
            if (index == 0) {
                report = ImportedCar(merged.name, merged.brand, merged.year, merged.fuelPrice, merged.currency, created = base == null)
            }
        }
        return CarMapping(byExternal, byName, byExternal[meta.cars.first().id], report)
    }

    /** false — запись уже импортирована раньше. Битая запись не оставляет полусессию: её externalId не должен блокировать повторный импорт. */
    internal suspend fun importRecording(fileName: String, bytes: ByteArray): Boolean = importRecording(fileName, bytes, CarMapping.EMPTY).created

    private suspend fun importRecording(fileName: String, bytes: ByteArray, mapping: CarMapping): RecordingResult {
        val externalId = "carscanner:$fileName"
        val reader = BrcReader(bytes)
        val header = reader.header
        // имя машины в заголовке записи совпадает с cars.name бэкапа
        val carId = mapping.byName[header.carName] ?: mapping.first
        if (sessions.hasExternal(externalId)) {
            if (carId != null) sessions.assignCar(externalId, carId)
            // Дозапись событий DTC, потерянных старым правилом «один код — одно событие на запись».
            val dtcs = reader.records().mapNotNull { (it as? BrcRecord.DtcEvent)?.let(::toDtc) }.toList()
            return RecordingResult(created = false, dtcAdded = saveDtcEvents(dtcs, carId))
        }
        val id = sessions.start(
            name = "Car Scanner · ${fileName.removeSuffix(".brc")}",
            source = SessionSource.IMPORT_CARSCANNER,
            vin = header.vin.ifBlank { null },
            startedAtMs = header.startedAtMs,
            externalId = externalId,
            carId = carId,
        )
        val dtcAdded = try {
            importRecords(id, reader, carId)
        } catch (e: Throwable) {
            withContext(NonCancellable) { sessions.delete(id) }
            throw e
        }
        return RecordingResult(created = true, dtcAdded = dtcAdded)
    }

    private fun toDtc(record: BrcRecord.DtcEvent): Pair<Long, Dtc>? =
        dtcCode.find(record.json)?.groupValues?.get(1)?.let { code ->
            val kind = when (dtcRequest.find(record.json)?.groupValues?.get(1)) {
                "07" -> DtcKind.PENDING
                "0A" -> DtcKind.PERMANENT
                else -> DtcKind.STORED
            }
            record.atMs to Dtc(code, kind)
        }

    /**
     * Car Scanner пишет код дважды подряд (ответ без заголовков и с ними) — это один и тот же опрос: близнецы в пределах
     * [DTC_TWIN_WINDOW_MS] схлопываются. Отдельные чтения (секунды/минуты спустя) остаются отдельными событиями.
     * Событие, которое уже есть в базе (тот же код в том же окне), не дублируется — повторный импорт идемпотентен.
     */
    private suspend fun saveDtcEvents(events: List<Pair<Long, Dtc>>, carId: Long?): Int {
        val distinct = events.sortedBy { it.first }.fold(mutableListOf<Pair<Long, Dtc>>()) { acc, e ->
            val twin = acc.any { (at, d) -> d.code == e.second.code && e.first - at <= DTC_TWIN_WINDOW_MS }
            if (!twin) acc += e
            acc
        }
        var added = 0
        for ((at, dtc) in distinct) {
            if (dtcHistory.hasEvent(dtc.code, at - DTC_TWIN_WINDOW_MS, at + DTC_TWIN_WINDOW_MS)) continue
            dtcHistory.saveSnapshot(listOf(dtc), vin = null, takenAtMs = at, carId = carId)
            added++
        }
        return added
    }

    private suspend fun importRecords(id: Long, reader: BrcReader, carId: Long?): Int {
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
                is BrcRecord.DtcEvent -> toDtc(record)?.let { dtcs += it }
                is BrcRecord.GpsPoint -> Unit // GPS-трек не импортируем (приватность), бэклог 1.24 п.11
            }
        }
        if (batch.isNotEmpty()) sessions.append(id, batch)
        sessions.finish(id, endedAtMs = lastMs)
        return saveDtcEvents(dtcs, carId)
    }

    private companion object {
        const val BATCH = 2_000
        /** Близнецы «без заголовков / с заголовками» пишутся в пределах долей секунды; отдельные чтения в бэкапе — от 9 с. */
        const val DTC_TWIN_WINDOW_MS = 5_000L
    }
}

/** Флаги Car Scanner → наши единицы; ключа нет в бэкапе — остаётся значение по умолчанию. */
internal fun CarScannerSettings.toUnits(): UnitsSettings {
    val d = UnitsSettings()
    return UnitsSettings(
        metricDistance = useKm ?: d.metricDistance,
        celsius = useCelsius ?: d.celsius,
        litersPer100Km = useL100 ?: d.litersPer100Km,
        liters = useLiters ?: d.liters,
        usGallon = useUsGallon ?: d.usGallon,
        pressureKpa = pressureKpa ?: d.pressureKpa,
        horsePower = horsePower ?: d.horsePower,
        newtonMeters = torqueNm ?: d.newtonMeters,
        flowGramsPerSecond = flowGramsPerSecond ?: d.flowGramsPerSecond,
        accelerationG = accelerationG ?: d.accelerationG,
    )
}
