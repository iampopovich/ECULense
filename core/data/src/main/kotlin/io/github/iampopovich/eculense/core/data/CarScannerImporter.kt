package io.github.iampopovich.eculense.core.data

import android.content.Context
import android.net.Uri
import io.github.iampopovich.eculense.core.carscanner.BrcReader
import io.github.iampopovich.eculense.core.carscanner.BrcRecord
import io.github.iampopovich.eculense.core.carscanner.CarScannerBackupMeta
import io.github.iampopovich.eculense.core.carscanner.CarScannerSensors
import io.github.iampopovich.eculense.core.carscanner.CarScannerSettings
import io.github.iampopovich.eculense.core.carscanner.CbzArchive
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream

/** What was carried over from the backup: recordings (1.21), car, units, fuel price, trips (1.25), updates (1.27). */
data class ImportReport(
    val imported: Int,
    val skippedDuplicates: Int,
    val failed: List<String>,
    val car: ImportedCar? = null,
    val tripsImported: Int = 0,
    /** Already known trips with no changes. */
    val tripsSkipped: Int = 0,
    /** true — units were taken from the backup (the user had not changed them). */
    val unitsImported: Boolean = false,
    /** New DTC events, including ones appended to already imported recordings. */
    val dtcEventsAdded: Int = 0,
    /** Trips that Car Scanner appended/changed since the previous backup. */
    val tripsUpdated: Int = 0,
    /** Of [skippedDuplicates]: .brc files fully imported earlier (name+size+CRC) — skipped without extraction. */
    val recordingsUnchanged: Int = 0,
    /** Profile fields of an already known car updated from the backup (the user had not edited them); field ids like "year", "fuelPrice". */
    val carFieldsUpdated: List<String> = emptyList(),
)

/** created = false — the car already existed (repeat import) or the default car was filled in. */
data class ImportedCar(
    val name: String,
    val brand: String?,
    val year: Int?,
    val fuelPrice: Double?,
    val currency: String?,
    val created: Boolean,
)

/** Import progress: done of total .brc recordings. */
data class ImportProgress(val done: Int, val total: Int)

/**
 * open — how to open the Uri picked by the user; made a parameter so unit tests can do without Context.
 * The .cbz is copied to disk and read as a random-access zip ([CbzArchive]): first the settings and
 * drivecyclesv2.db (the car is needed before the recordings), then the .brc recordings.
 *
 * Repeat import is incremental (1.27): recordings by externalId; fully imported .brc files (name+size+CRC in
 * [ImportLedger]) are not even extracted; trips by externalId «carscanner:<car>:<start>», changed ones
 * are updated; DTCs use the 5 s twin rule; car profile and units: a field changes only if it is empty or
 * the user has not touched it since the previous import (current value == previous import snapshot).
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
    private val ledger: ImportLedger = InMemoryImportLedger(),
    /** After import (analytics recompute); does not block the result. */
    private val afterImport: (ImportReport) -> Unit = {},
) {
    constructor(
        context: Context,
        sessions: SessionRepository,
        dtcHistory: DtcRepository,
        cars: CarRepository,
        trips: TripRepository,
        units: UnitsRepository,
        ledger: ImportLedger,
        afterImport: (ImportReport) -> Unit = {},
    ) : this(
        { uri: Uri -> context.contentResolver.openInputStream(uri) },
        sessions, dtcHistory, cars, trips, units, AndroidSqlite, context.cacheDir, ledger, afterImport,
    )

    private val dtcCode = Regex("\"Code\":\"([A-Z0-9]{5})\"")
    private val dtcRequest = Regex("\"RequestCommand\":\"([0-9A-F]{2})\"")

    /**
     * uri — a full .cbz backup or a single .brc recording (content:// from SAF). The file is copied to a temporary cache:
     * no storage permissions needed, the zip is read with random access.
     */
    suspend fun import(uri: Uri, fileName: String, onProgress: (ImportProgress) -> Unit = {}): ImportReport = withContext(Dispatchers.IO) {
        val copy = File.createTempFile("import-", if (fileName.endsWith(".brc", true)) ".brc" else ".cbz", tempDir)
        val report = try {
            (open(uri) ?: throw ImportFileUnavailableException()).use { input -> copy.outputStream().use { input.copyTo(it) } }
            importFile(copy, fileName, onProgress)
        } finally {
            copy.delete()
        }
        afterImport(report)
        report
    }

    /** Stream → temporary file → [importFile]. onRecording(n) — how many recordings have been processed so far. */
    internal suspend fun importStream(input: () -> InputStream, fileName: String, onRecording: (Int) -> Unit = {}): ImportReport {
        val copy = File.createTempFile("import-", ".tmp", tempDir)
        try {
            input().use { i -> copy.outputStream().use { i.copyTo(it) } }
            return importFile(copy, fileName) { if (it.done > 0) onRecording(it.done) }
        } finally {
            copy.delete()
        }
    }

    suspend fun importFile(file: File, fileName: String, onProgress: (ImportProgress) -> Unit = {}): ImportReport {
        if (fileName.endsWith(".brc", ignoreCase = true)) return importSingleBrc(file, fileName, onProgress)
        val failed = mutableListOf<String>()
        CbzArchive(file).use { cbz ->
            val meta = try {
                cbz.meta(tempDir, sqlite)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += "settings and trips: ${e.message}"
                null
            }
            val carMapping = meta?.let { importCars(it) } ?: CarMapping.EMPTY
            val unitsImported = meta?.settings?.takeIf { it.hasUnits }?.let { mergeUnits(it.toUnits()) } ?: false
            val total = cbz.recordings.size
            var imported = 0
            var duplicates = 0
            var unchanged = 0
            var dtcAdded = 0
            var processed = 0
            onProgress(ImportProgress(0, total))
            for (entry in cbz.recordings) {
                currentCoroutineContext().ensureActive()
                val mark = ledger.importedFile(entry.fileName)
                if (mark != null && mark.size == entry.size && mark.crc == entry.crc && sessions.hasExternal(externalId(entry.fileName))) {
                    duplicates++
                    unchanged++
                } else {
                    try {
                        val result = importRecording(entry.fileName, cbz.read(entry), carMapping)
                        dtcAdded += result.dtcAdded
                        if (result.created) imported++ else duplicates++
                        ledger.markFile(ImportedFileMark(entry.fileName, entry.size, entry.crc))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += "${entry.fileName}: ${e.message}"
                    }
                }
                onProgress(ImportProgress(++processed, total))
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
                    // trip key = Car Scanner car + start time: stable across backups (the trip id in
                    // drivecyclesv2 is autoincrement and restarts after Car Scanner is reinstalled)
                    externalId = "carscanner:${t.carId ?: 0}:${t.startedUtcMs}",
                )
            }
            val merge = if (tripList.isEmpty()) TripMerge(0, 0, 0) else trips.mergeImported(tripList)
            return ImportReport(
                imported = imported,
                skippedDuplicates = duplicates,
                failed = failed,
                car = carMapping.report,
                tripsImported = merge.added,
                tripsSkipped = merge.unchanged,
                unitsImported = unitsImported,
                dtcEventsAdded = dtcAdded,
                tripsUpdated = merge.updated,
                recordingsUnchanged = unchanged,
                carFieldsUpdated = carMapping.updatedFields,
            )
        }
    }

    private suspend fun importSingleBrc(file: File, fileName: String, onProgress: (ImportProgress) -> Unit): ImportReport {
        onProgress(ImportProgress(0, 1))
        val report = try {
            val result = importRecording(fileName, file.readBytes(), CarMapping.EMPTY)
            ImportReport(if (result.created) 1 else 0, if (result.created) 0 else 1, emptyList(), dtcEventsAdded = result.dtcAdded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportReport(0, 0, listOf("$fileName: ${e.message}"))
        }
        onProgress(ImportProgress(1, 1))
        return report
    }

    private fun externalId(fileName: String) = "carscanner:$fileName"

    private data class RecordingResult(val created: Boolean, val dtcAdded: Int)

    private class CarMapping(
        val byExternal: Map<Long, Long>,
        val byName: Map<String, Long>,
        val first: Long?,
        val report: ImportedCar?,
        val updatedFields: List<String> = emptyList(),
    ) {
        companion object {
            val EMPTY = CarMapping(emptyMap(), emptyMap(), null, null)
        }
    }

    /** Car values the backup brings; the previous import's snapshot is stored in [ImportLedger] as JSON. */
    private data class CarValues(
        val name: String?, val brand: String?, val year: Int?, val diagProfile: String?, val fuelPrice: Double?, val currency: String?,
    ) {
        fun toJson(): String = buildJsonObject {
            put("name", name); put("brand", brand); put("year", year); put("diagProfile", diagProfile)
            put("fuelPrice", fuelPrice); put("currency", currency)
        }.toString()

        companion object {
            fun parse(json: String?): CarValues? = json?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }?.let { o ->
                fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                CarValues(s("name"), s("brand"), o["year"]?.jsonPrimitive?.intOrNull, s("diagProfile"), o["fuelPrice"]?.jsonPrimitive?.doubleOrNull, s("currency"))
            }
        }
    }

    /**
     * Car Scanner cars → ours. A new or untouched default car is filled entirely from the backup. An already
     * imported one (same externalId): an empty field is filled, and a filled one changes only if the user
     * has not edited it since the previous import (value == previous import snapshot). Manual edits always win.
     */
    private suspend fun importCars(meta: CarScannerBackupMeta): CarMapping {
        if (meta.cars.isEmpty()) return CarMapping.EMPTY
        val s = meta.settings
        val byExternal = mutableMapOf<Long, Long>()
        val byName = mutableMapOf<String, Long>()
        var report: ImportedCar? = null
        var updatedFields = emptyList<String>()
        meta.cars.forEachIndexed { index, cs ->
            val externalId = "carscanner:${cs.id}"
            val existing = cars.byExternalId(externalId)
            val all = cars.cars()
            val adopt = all.singleOrNull()?.takeIf { existing == null && index == 0 && it.isUntouchedDefault }
            val base = existing ?: adopt
            val name = cs.name.ifBlank { listOfNotNull(s.brand, cs.year?.toString()).joinToString(" ").ifBlank { "Car Scanner" } }
            // Car Scanner settings are app-wide — attribute them to the first car
            val incoming = CarValues(
                name = name, brand = s.brand.takeIf { index == 0 }, year = cs.year, diagProfile = s.diagProfile.takeIf { index == 0 },
                fuelPrice = s.fuelPricePerL.takeIf { index == 0 }, currency = s.currency.takeIf { index == 0 },
            )
            val changed = mutableListOf<String>()
            val merged = if (existing == null) {
                (base ?: CarProfile(name = name)).let { car ->
                    car.copy(
                        name = name, externalId = externalId, year = car.year ?: incoming.year, brand = car.brand ?: incoming.brand,
                        diagProfile = car.diagProfile ?: incoming.diagProfile, fuelPrice = car.fuelPrice ?: incoming.fuelPrice,
                        currency = car.currency ?: incoming.currency,
                    )
                }
            } else {
                val previous = CarValues.parse(ledger.snapshot(snapshotKey(existing.id)))
                fun <T> field(label: String, current: T?, new: T?, old: T?): T? {
                    val value = mergeField(current, new, old, previous != null)
                    if (value != current) changed += label
                    return value
                }
                existing.copy(
                    name = field("name", existing.name, incoming.name, previous?.name) ?: existing.name,
                    brand = field("brand", existing.brand, incoming.brand, previous?.brand),
                    year = field("year", existing.year, incoming.year, previous?.year),
                    diagProfile = field("diagProfile", existing.diagProfile, incoming.diagProfile, previous?.diagProfile),
                    fuelPrice = field("fuelPrice", existing.fuelPrice, incoming.fuelPrice, previous?.fuelPrice),
                    currency = field("currency", existing.currency, incoming.currency, previous?.currency),
                )
            }
            val id = if (existing != null && changed.isEmpty()) existing.id else cars.save(merged)
            ledger.putSnapshot(snapshotKey(id), incoming.toJson())
            if (adopt != null) cars.setActive(id)
            byExternal[cs.id] = id
            byName.putIfAbsent(merged.name, id)
            byName.putIfAbsent(name, id)
            if (index == 0) {
                report = ImportedCar(merged.name, merged.brand, merged.year, merged.fuelPrice, merged.currency, created = base == null)
                updatedFields = changed.toList()
            }
        }
        return CarMapping(byExternal, byName, byExternal[meta.cars.first().id], report, updatedFields)
    }

    /**
     * Units as a whole: taken from the backup if the user has not changed them (current == previous import snapshot, or
     * the defaults before the first import). Returns true if the units changed.
     */
    private suspend fun mergeUnits(incoming: UnitsSettings): Boolean {
        val current = units.units.first()
        val previous = ledger.snapshot(UNITS_KEY)?.let(::parseUnits)
        val untouched = if (previous != null) current == previous else current == UnitsSettings()
        val apply = current != incoming && untouched
        if (apply) units.save(incoming)
        ledger.putSnapshot(UNITS_KEY, unitsJson(incoming))
        return apply
    }

    /** false — the recording was already imported earlier. A broken recording leaves no half-session: its externalId must not block a repeat import. */
    internal suspend fun importRecording(fileName: String, bytes: ByteArray): Boolean = importRecording(fileName, bytes, CarMapping.EMPTY).created

    private suspend fun importRecording(fileName: String, bytes: ByteArray, mapping: CarMapping): RecordingResult {
        val externalId = externalId(fileName)
        val reader = BrcReader(bytes)
        val header = reader.header
        // the car name in the recording header matches cars.name in the backup
        val carId = mapping.byName[header.carName] ?: mapping.first
        if (sessions.hasExternal(externalId)) {
            if (carId != null) sessions.assignCar(externalId, carId)
            // Append DTC events lost by the old "one code = one event per recording" rule.
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
     * Car Scanner writes a code twice in a row (response without and with headers) — it is the same poll: twins within
     * [DTC_TWIN_WINDOW_MS] are collapsed. Separate reads (seconds/minutes later) stay separate events.
     * An event already in the DB (same code in the same window) is not duplicated — repeat import is idempotent.
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
                is BrcRecord.GpsPoint -> Unit // GPS track is not imported (privacy), backlog 1.24 item 11
            }
        }
        if (batch.isNotEmpty()) sessions.append(id, batch)
        sessions.finish(id, endedAtMs = lastMs)
        return saveDtcEvents(dtcs, carId)
    }

    private companion object {
        const val BATCH = 2_000
        /** "Without headers / with headers" twins are written within fractions of a second; separate reads in the backup are 9 s or more apart. */
        const val DTC_TWIN_WINDOW_MS = 5_000L
        const val UNITS_KEY = "units"
        fun snapshotKey(carId: Long) = "car:$carId"
    }
}

/**
 * Merging a single field: an empty one is filled; a matching one is left as is; a filled one is replaced with the backup
 * value only if there is a previous import snapshot and the current value matches it (the user has not touched the field).
 */
internal fun <T> mergeField(current: T?, incoming: T?, previous: T?, hasSnapshot: Boolean): T? = when {
    incoming == null -> current
    current == null || (current is String && current.isBlank()) -> incoming
    current == incoming -> current
    hasSnapshot && current == previous -> incoming
    else -> current
}

private fun unitsJson(u: UnitsSettings): String = buildJsonObject {
    put("km", u.metricDistance); put("celsius", u.celsius); put("l100", u.litersPer100Km); put("liters", u.liters)
    put("usGallon", u.usGallon); put("kpa", u.pressureKpa); put("hp", u.horsePower); put("nm", u.newtonMeters)
    put("gs", u.flowGramsPerSecond); put("g", u.accelerationG)
}.toString()

private fun parseUnits(json: String): UnitsSettings? = runCatching {
    val o: JsonObject = Json.parseToJsonElement(json).jsonObject
    fun b(k: String) = o[k]?.jsonPrimitive?.booleanOrNull ?: error("missing $k")
    UnitsSettings(b("km"), b("celsius"), b("l100"), b("liters"), b("usGallon"), b("kpa"), b("hp"), b("nm"), b("gs"), b("g"))
}.getOrNull()

/** Car Scanner flags → our units; if the key is missing from the backup, the default stays. */
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
