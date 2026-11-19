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
        Pid(0x04, "Engine load", "%", 1) { pct(it[0]) },
        Pid(0x05, "Coolant temperature", "°C", 1) { it[0] - 40.0 },
        Pid(0x06, "Short-term fuel trim, bank 1", "%", 1) { trim(it[0]) },
        Pid(0x07, "Long-term fuel trim, bank 1", "%", 1) { trim(it[0]) },
        Pid(0x0B, "Intake manifold pressure", "kPa", 1) { it[0].toDouble() },
        Pid(0x0C, "Engine speed", "rpm", 2) { word(it) / 4.0 },
        Pid(0x0D, "Vehicle speed", "km/h", 1) { it[0].toDouble() },
        Pid(0x0E, "Ignition timing advance", "°", 1) { it[0] / 2.0 - 64 },
        Pid(0x0F, "Intake air temperature", "°C", 1) { it[0] - 40.0 },
        Pid(0x10, "Mass air flow", "g/s", 2) { word(it) / 100.0 },
        Pid(0x11, "Throttle position", "%", 1) { pct(it[0]) },
        Pid(0x1F, "Time since engine start", "s", 2) { word(it).toDouble() },
        Pid(0x21, "Distance with Check Engine on", "km", 2) { word(it).toDouble() },
        Pid(0x2F, "Fuel level", "%", 1) { pct(it[0]) },
        Pid(0x31, "Distance since codes cleared", "km", 2) { word(it).toDouble() },
        Pid(0x33, "Barometric pressure", "kPa", 1) { it[0].toDouble() },
        Pid(0x42, "Control module voltage", "V", 2) { word(it) / 1000.0 },
        Pid(0x46, "Ambient air temperature", "°C", 1) { it[0] - 40.0 },
        Pid(0x5C, "Engine oil temperature", "°C", 1) { it[0] - 40.0 },
        Pid(0x5E, "Fuel rate", "L/h", 2) { word(it) / 20.0 },
    )

    /** What the dashboard shows by default, in order of importance (if the ECU supports it). */
    val dashboardDefaults: List<Int> = listOf(0x0C, 0x0D, 0x05, 0x04, 0x11, 0x42, 0x0F, 0x10)

    private val index = all.associateBy { it.code }

    fun byCode(code: Int): Pid? = index[code]
}
