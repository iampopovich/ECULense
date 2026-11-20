package io.github.iampopovich.eculense.core.obd

/** Ignition type from PID 01 byte B bit 3 (SAE J1979): the non-continuous monitor table depends on it. */
enum class Ignition { SPARK, COMPRESSION }

/**
 * OBD readiness monitors. The first three are continuous (byte B), the rest non-continuous (bytes C/D). Spark and
 * compression ignition engines use different names for the same 8 bits of C/D; names are rendered in :core:obdtext.
 */
enum class Monitor(val continuous: Boolean = false) {
    MISFIRE(true), FUEL_SYSTEM(true), COMPONENTS(true),

    // Spark ignition, C/D bits 0..7.
    CATALYST, HEATED_CATALYST, EVAPORATIVE_SYSTEM, SECONDARY_AIR, AC_REFRIGERANT, OXYGEN_SENSOR, OXYGEN_SENSOR_HEATER, EGR,

    // Compression ignition, C/D bits 0, 1, 3, 5, 6, 7 (2 and 4 are reserved).
    NMHC_CATALYST, NOX_SCR, BOOST_PRESSURE, EXHAUST_GAS_SENSOR, PM_FILTER, EGR_VVT,
}

enum class MonitorStatus { COMPLETE, INCOMPLETE, NOT_SUPPORTED }

data class MonitorState(val monitor: Monitor, val status: MonitorStatus)

/**
 * Decoded PID 01 (status since DTCs cleared): Check Engine lamp, number of stored emission DTCs, ignition type and
 * every monitor. [monitors] lists all monitors of the ignition type, including the ones the ECU does not support.
 */
data class Readiness(
    val milOn: Boolean,
    val dtcCount: Int,
    val ignition: Ignition,
    val monitors: List<MonitorState>,
) {
    val supported: List<MonitorState> get() = monitors.filter { it.status != MonitorStatus.NOT_SUPPORTED }
    val incompleteCount: Int get() = monitors.count { it.status == MonitorStatus.INCOMPLETE }

    /** OBD readiness only (not a legal inspection verdict): every supported monitor has completed. */
    val allComplete: Boolean get() = supported.isNotEmpty() && incompleteCount == 0
}

/** Pure decoder of PIDs 01 and 41 after the SAE J1979 bit layout. */
object ReadinessDecoder {
    private val CONTINUOUS = listOf(Monitor.MISFIRE, Monitor.FUEL_SYSTEM, Monitor.COMPONENTS)

    // Index = bit number in bytes C (supported / enabled) and D (incomplete); null = reserved bit.
    private val SPARK = listOf(
        Monitor.CATALYST, Monitor.HEATED_CATALYST, Monitor.EVAPORATIVE_SYSTEM, Monitor.SECONDARY_AIR,
        Monitor.AC_REFRIGERANT, Monitor.OXYGEN_SENSOR, Monitor.OXYGEN_SENSOR_HEATER, Monitor.EGR,
    )
    private val COMPRESSION = listOf(
        Monitor.NMHC_CATALYST, Monitor.NOX_SCR, null, Monitor.BOOST_PRESSURE,
        null, Monitor.EXHAUST_GAS_SENSOR, Monitor.PM_FILTER, Monitor.EGR_VVT,
    )

    /** [data] = the 4 data bytes A..D of "41 01 A B C D". */
    fun status(data: IntArray): Readiness {
        require(data.size >= 4) { "PID 01 needs 4 bytes, got ${data.size}" }
        val (a, b) = data[0] to data[1]
        val ignition = if (b and 0x08 != 0) Ignition.COMPRESSION else Ignition.SPARK
        return Readiness(
            milOn = a and 0x80 != 0,
            dtcCount = a and 0x7F,
            ignition = ignition,
            monitors = monitors(b, data[2], data[3], ignition),
        )
    }

    /**
     * PID 41 (status this drive cycle): same layout as PID 01 without the MIL/count byte meaning; byte B bit 3 is
     * reserved there, so the ignition type comes from PID 01. Status: enabled + incomplete = INCOMPLETE.
     */
    fun thisDriveCycle(data: IntArray, ignition: Ignition): List<MonitorState> {
        require(data.size >= 4) { "PID 41 needs 4 bytes, got ${data.size}" }
        return monitors(data[1], data[2], data[3], ignition)
    }

    private fun monitors(b: Int, c: Int, d: Int, ignition: Ignition): List<MonitorState> {
        val continuous = CONTINUOUS.mapIndexed { bit, m -> MonitorState(m, status(b, bit, b, bit + 4)) }
        val table = if (ignition == Ignition.SPARK) SPARK else COMPRESSION
        val other = table.mapIndexedNotNull { bit, m -> m?.let { MonitorState(it, status(c, bit, d, bit)) } }
        return continuous + other
    }

    private fun status(supportByte: Int, supportBit: Int, incompleteByte: Int, incompleteBit: Int): MonitorStatus = when {
        supportByte shr supportBit and 1 == 0 -> MonitorStatus.NOT_SUPPORTED
        incompleteByte shr incompleteBit and 1 == 1 -> MonitorStatus.INCOMPLETE
        else -> MonitorStatus.COMPLETE
    }
}
