package io.github.iampopovich.eculense.core.obd

/**
 * Service 01 PID. decode receives only the data bytes (without 41 and the PID number).
 * [name] is the English SAE-style name for logs; the UI shows the localized name from :core:obdtext.
 */
class Pid(
    val code: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    private val decode: (IntArray) -> Double,
) {
    val sensorId: String get() = "01.%02X".format(code)
    val command: String get() = "01%02X".format(code)

    fun decodeFrom(message: ByteArray): Double? {
        val u = message.toUnsigned()
        if (u.size < 2 + bytes || u[0] != 0x41 || u[1] != code) return null
        return decode(u.subList(2, 2 + bytes).toIntArray())
    }

    override fun toString() = "Pid($sensorId $name)"
}

object StandardPids {
    private fun pct(a: Int) = a * 100.0 / 255
    private fun trim(a: Int) = a * 100.0 / 128 - 100
    private fun word(d: IntArray) = d[0] * 256 + d[1]

    val all: List<Pid> = listOf(
        Pid(0x03, "Fuel system status", "", 2) { it[0].toDouble() },
        Pid(0x04, "Engine load", "%", 1) { pct(it[0]) },
        Pid(0x05, "Coolant temperature", "°C", 1) { it[0] - 40.0 },
        Pid(0x06, "Short-term fuel trim, bank 1", "%", 1) { trim(it[0]) },
        Pid(0x07, "Long-term fuel trim, bank 1", "%", 1) { trim(it[0]) },
        Pid(0x08, "Short-term fuel trim, bank 2", "%", 1) { trim(it[0]) },
        Pid(0x09, "Long-term fuel trim, bank 2", "%", 1) { trim(it[0]) },
        Pid(0x0B, "Intake manifold pressure", "kPa", 1) { it[0].toDouble() },
        Pid(0x0C, "Engine speed", "rpm", 2) { word(it) / 4.0 },
        Pid(0x0D, "Vehicle speed", "km/h", 1) { it[0].toDouble() },
        Pid(0x0E, "Ignition timing advance", "°", 1) { it[0] / 2.0 - 64 },
        Pid(0x0F, "Intake air temperature", "°C", 1) { it[0] - 40.0 },
        Pid(0x10, "Mass air flow", "g/s", 2) { word(it) / 100.0 },
        Pid(0x11, "Throttle position", "%", 1) { pct(it[0]) },
        Pid(0x14, "O2 sensor voltage, bank 1 sensor 1", "V", 2) { it[0] / 200.0 },
        Pid(0x15, "O2 sensor voltage, bank 1 sensor 2", "V", 2) { it[0] / 200.0 },
        Pid(0x1F, "Time since engine start", "s", 2) { word(it).toDouble() },
        Pid(0x21, "Distance with Check Engine on", "km", 2) { word(it).toDouble() },
        Pid(0x2F, "Fuel level", "%", 1) { pct(it[0]) },
        Pid(0x30, "Warm-ups since codes cleared", "", 1) { it[0].toDouble() },
        Pid(0x31, "Distance since codes cleared", "km", 2) { word(it).toDouble() },
        Pid(0x33, "Barometric pressure", "kPa", 1) { it[0].toDouble() },
        Pid(0x3C, "Catalyst temperature, bank 1 sensor 1", "°C", 2) { word(it) / 10.0 - 40 },
        Pid(0x42, "Control module voltage", "V", 2) { word(it) / 1000.0 },
        Pid(0x43, "Absolute load", "%", 2) { word(it) * 100.0 / 255 },
        Pid(0x44, "Commanded air-fuel ratio (lambda)", "λ", 2) { word(it) * 2.0 / 65_536 },
        Pid(0x45, "Relative throttle position", "%", 1) { pct(it[0]) },
        Pid(0x46, "Ambient air temperature", "°C", 1) { it[0] - 40.0 },
        Pid(0x5C, "Engine oil temperature", "°C", 1) { it[0] - 40.0 },
        Pid(0x5E, "Fuel rate", "L/h", 2) { word(it) / 20.0 },
    )

    /** What the dashboard shows by default, in order of importance (if the ECU supports it). */
    val dashboardDefaults: List<Int> = listOf(0x0C, 0x0D, 0x05, 0x04, 0x11, 0x42, 0x0F, 0x10)

    /** PID 03 byte A: 2 = closed loop, 16 = closed loop with a feedback fault; everything else is open loop. */
    fun isClosedLoop(fuelSystemStatus: Double): Boolean = fuelSystemStatus.toInt() == 2 || fuelSystemStatus.toInt() == 16

    val FUEL_TRIMS: Set<Int> = setOf(0x06, 0x07, 0x08, 0x09)

    /** C1: the system a PID belongs to, for grouping on the car data screen. */
    fun systemOf(code: Int): PidSystem = when (code) {
        0x04, 0x05, 0x0C, 0x0E, 0x11, 0x1F, 0x43, 0x45, 0x5C -> PidSystem.ENGINE
        0x03, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x22, 0x23, 0x2F, 0x5E -> PidSystem.FUEL
        0x0B, 0x0F, 0x10, 0x33, 0x46 -> PidSystem.AIR
        in 0x14..0x1B, in 0x24..0x2B, in 0x34..0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x44 -> PidSystem.EXHAUST
        0x42 -> PidSystem.ELECTRICAL
        else -> PidSystem.OTHER
    }

    private val index = all.associateBy { it.code }

    fun byCode(code: Int): Pid? = index[code]
}

/** Groups of the car data screen (C1); names are rendered in :core:obdtext. */
enum class PidSystem { ENGINE, FUEL, AIR, EXHAUST, ELECTRICAL, OTHER }
