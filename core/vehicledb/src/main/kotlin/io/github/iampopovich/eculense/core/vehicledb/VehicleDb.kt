package io.github.iampopovich.eculense.core.vehicledb

import io.github.iampopovich.eculense.core.datapacks.PackDb
import io.github.iampopovich.eculense.core.model.DriveType
import io.github.iampopovich.eculense.core.model.FuelType
import io.github.iampopovich.eculense.core.model.Gearbox

data class VehicleMake(val id: Long, val name: String)
data class VehicleModel(val id: Long, val name: String, val yearStart: Int?, val yearEnd: Int?)
data class VehicleGeneration(val id: Long, val name: String, val yearStart: Int?, val yearEnd: Int?, val bodyType: String?) {
    /** «2002–2006», «2020–н. в.». */
    val years: String get() = listOfNotNull(yearStart?.toString(), yearEnd?.toString() ?: yearStart?.let { "н. в." }).joinToString("–")
}

/** Модификация (мотор + КПП + привод) с нормализованными полями и баком из сырых specs. */
data class VehicleEngine(
    val id: Long,
    val label: String,
    val fuelType: String?,
    val cylinders: Int?,
    val displacementCc: Int?,
    val powerHp: Double?,
    val torqueNm: Double?,
    val transmission: String?,
    val drivetrain: String?,
    val fuelEconomyL100: Double?,
    val curbWeightKg: Int?,
    val tankL: Double? = null,
) {
    val engineVolumeL: Double? get() = displacementCc?.let { Math.round(it / 100.0) / 10.0 }
    val fuel: FuelType? get() = VehicleSpecs.fuel(fuelType)
    val gearbox: Gearbox? get() = VehicleSpecs.gearbox(transmission)
    val drive: DriveType? get() = VehicleSpecs.drive(drivetrain)
}

/**
 * Запросы к `vehicles.sqlite` (gor3a/vehicle-makes-models). Файл открывается только на чтение и не меняется.
 * Схема релиза v1.0.3: makes → models → generations → engines (+ engine_specs key/value).
 */
class VehicleDb(private val db: PackDb) : AutoCloseable {
    /** Недостающие таблицы/колонки — пустой список, если схема та, под которую написан код. */
    fun schemaProblems(): List<String> = EXPECTED.flatMap { (table, columns) ->
        val actual = db.query("SELECT name FROM pragma_table_info(?)", listOf(table)) { it.string(0) }.toSet()
        if (actual.isEmpty()) listOf("нет таблицы $table") else columns.filter { it !in actual }.map { "нет колонки $table.$it" }
    }

    fun makes(query: String = ""): List<VehicleMake> =
        db.query("SELECT id, name FROM makes WHERE name LIKE ? ORDER BY name COLLATE NOCASE", listOf(like(query))) {
            VehicleMake(it.long(0)!!, it.string(1)!!)
        }

    /** Марка по имени из профиля («Kia», «KIA», «kia»). */
    fun makeByName(name: String): VehicleMake? =
        db.query("SELECT id, name FROM makes WHERE name = ? COLLATE NOCASE OR \"group\" = ? COLLATE NOCASE LIMIT 1", listOf(name.trim(), name.trim())) {
            VehicleMake(it.long(0)!!, it.string(1)!!)
        }.firstOrNull()

    fun models(makeId: Long, query: String = ""): List<VehicleModel> =
        db.query(
            "SELECT id, name, year_start, year_end FROM models WHERE make_id = ? AND name LIKE ? ORDER BY name COLLATE NOCASE",
            listOf(makeId, like(query)),
        ) { VehicleModel(it.long(0)!!, it.string(1)!!, it.long(2)?.toInt(), it.long(3)?.toInt()) }

    /** Поколения от старых к новым. */
    fun generations(modelId: Long): List<VehicleGeneration> =
        db.query(
            "SELECT id, name, year_start, year_end, body_type FROM generations WHERE model_id = ? ORDER BY year_start, name",
            listOf(modelId),
        ) { VehicleGeneration(it.long(0)!!, it.string(1)!!, it.long(2)?.toInt(), it.long(3)?.toInt(), it.string(4)) }

    fun engines(generationId: Long, query: String = ""): List<VehicleEngine> =
        db.query("$ENGINE_SELECT WHERE e.generation_id = ? AND e.label LIKE ? ORDER BY e.label", listOf(generationId, like(query)), ::engineRow)

    /** Модификация вместе с объёмом бака из сырых specs («21.1 gallons (79.9 L)»). */
    fun engine(id: Long): VehicleEngine? {
        val e = db.query("$ENGINE_SELECT WHERE e.id = ?", listOf(id), ::engineRow).firstOrNull() ?: return null
        val tank = db.query("SELECT value FROM engine_specs WHERE engine_id = ? AND key = ?", listOf(id, TANK_KEY)) { it.string(0) }
            .firstNotNullOfOrNull { it?.let(VehicleSpecs::tankLitres) }
        return e.copy(tankL = tank)
    }

    override fun close() = db.close()

    private fun engineRow(r: io.github.iampopovich.eculense.core.datapacks.PackRow) = VehicleEngine(
        id = r.long(0)!!, label = r.string(1)!!, fuelType = r.string(2), cylinders = r.long(3)?.toInt(),
        displacementCc = r.long(4)?.toInt(), powerHp = r.double(5), torqueNm = r.double(6), transmission = r.string(7),
        drivetrain = r.string(8), fuelEconomyL100 = r.double(9), curbWeightKg = r.long(10)?.toInt(),
    )

    private fun like(q: String) = "%" + q.trim().replace("%", "").replace("_", "") + "%"

    companion object {
        const val TANK_KEY = "ENGINE SPECS / Fuel capacity"
        private const val ENGINE_SELECT =
            "SELECT e.id, e.label, e.fuel_type, e.cylinders, e.displacement_cc, e.power_hp, e.torque_nm, e.transmission, " +
                "e.drivetrain, e.fuel_economy_combined_l100, e.curb_weight_kg FROM engines e"

        val EXPECTED: Map<String, List<String>> = mapOf(
            "makes" to listOf("id", "group", "name"),
            "models" to listOf("id", "make_id", "name", "year_start", "year_end"),
            "generations" to listOf("id", "model_id", "name", "year_start", "year_end", "body_type"),
            "engines" to listOf(
                "id", "generation_id", "label", "fuel_type", "cylinders", "displacement_cc", "power_hp", "torque_nm",
                "transmission", "drivetrain", "fuel_economy_combined_l100", "curb_weight_kg",
            ),
            "engine_specs" to listOf("engine_id", "key", "value"),
        )
    }
}

/** Строки справочника (англ., как на autoevolution) → наши перечисления. Неизвестное → null/OTHER, поле остаётся редактируемым. */
object VehicleSpecs {
    private val litres = Regex("""([\d]+(?:[.,]\d+)?)\s*L\b""")

    fun tankLitres(spec: String): Double? = litres.findAll(spec).lastOrNull()?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()

    fun fuel(s: String?): FuelType? {
        val v = s?.lowercase() ?: return null
        return when {
            "hybrid" in v -> FuelType.HYBRID
            "electric" in v -> FuelType.ELECTRIC
            "diesel" in v -> FuelType.DIESEL
            "lpg" in v || "liquefied petroleum" in v -> FuelType.LPG
            "natural gas" in v || v == "gnl" || "cng" in v || v.startsWith("ng") -> FuelType.CNG
            "gasoline" in v || "petrol" in v -> FuelType.PETROL
            v == "unknown" -> null
            else -> FuelType.OTHER
        }
    }

    fun gearbox(s: String?): Gearbox? {
        val v = s?.lowercase() ?: return null
        return when {
            "cvt" in v || "continuously" in v -> Gearbox.CVT
            "dual-clutch" in v || "dual clutch" in v || "dsg" in v || "robot" in v || "automated manual" in v || "sequential" in v -> Gearbox.ROBOT
            "manual" in v -> Gearbox.MANUAL
            "automatic" in v -> Gearbox.AUTOMATIC
            else -> Gearbox.OTHER
        }
    }

    fun drive(s: String?): DriveType? {
        val v = s?.lowercase() ?: return null
        return when {
            "all" in v || "4x4" in v || "four" in v -> DriveType.ALL
            "front" in v -> DriveType.FRONT
            "rear" in v -> DriveType.REAR
            else -> null
        }
    }
}
