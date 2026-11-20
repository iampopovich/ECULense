package io.github.iampopovich.eculense.core.obd

/** Unit of a scaled mode 06 value; the UI renders the symbol (units are not translated). */
enum class UasUnit(val symbol: String) {
    NONE(""), RPM("rpm"), KMH("km/h"), MILLIVOLT("mV"), VOLT("V"), MILLIAMPERE("mA"), AMPERE("A"), MILLISECOND("ms"),
    SECOND("s"), MILLIOHM("mΩ"), OHM("Ω"), KILOOHM("kΩ"), CELSIUS("°C"), KILOPASCAL("kPa"), DEGREE("°"), RATIO(""),
    HERTZ("Hz"), KILOMETER("km"), GRAMS_PER_SECOND("g/s"), PERCENT("%"), MINUTE("min"), PASCAL("Pa"), COUNT(""),
}

/** One unit-and-scaling id (J1979 appendix): value = raw (16 bit, signed when [signed]) * [scale] + [offset]. */
data class Uas(val scale: Double, val unit: UasUnit, val signed: Boolean = false, val offset: Double = 0.0) {
    fun apply(raw: Int): Double {
        val v = if (signed && raw >= 0x8000) raw - 0x10000 else raw
        return v * scale + offset
    }
}

/**
 * The common UAS ids, written from the J1979 scaling definitions (checked against public mode 06 summaries, e.g. a
 * manufacturer's mode $06 definitions). Unknown ids are shown raw.
 */
object UasTable {
    private val TABLE: Map<Int, Uas> = mapOf(
        0x01 to Uas(1.0, UasUnit.COUNT),
        0x02 to Uas(0.1, UasUnit.COUNT),
        0x03 to Uas(0.01, UasUnit.COUNT),
        0x04 to Uas(0.001, UasUnit.COUNT),
        0x05 to Uas(0.0000305, UasUnit.COUNT),
        0x06 to Uas(0.000305, UasUnit.COUNT),
        0x07 to Uas(0.25, UasUnit.RPM),
        0x08 to Uas(0.01, UasUnit.KMH),
        0x09 to Uas(1.0, UasUnit.KMH),
        0x0A to Uas(0.122, UasUnit.MILLIVOLT),
        0x0B to Uas(0.001, UasUnit.VOLT),
        0x0C to Uas(0.01, UasUnit.VOLT),
        0x0D to Uas(0.00390625, UasUnit.MILLIAMPERE),
        0x0E to Uas(0.001, UasUnit.AMPERE),
        0x0F to Uas(0.01, UasUnit.AMPERE),
        0x10 to Uas(1.0, UasUnit.MILLISECOND),
        0x11 to Uas(100.0, UasUnit.MILLISECOND),
        0x12 to Uas(1.0, UasUnit.SECOND),
        0x13 to Uas(1.0, UasUnit.MILLIOHM),
        0x14 to Uas(1.0, UasUnit.OHM),
        0x15 to Uas(1.0, UasUnit.KILOOHM),
        0x16 to Uas(0.1, UasUnit.CELSIUS, offset = -40.0),
        0x17 to Uas(0.01, UasUnit.KILOPASCAL),
        0x1A to Uas(1.0, UasUnit.KILOPASCAL),
        0x1C to Uas(0.01, UasUnit.DEGREE),
        0x1D to Uas(0.5, UasUnit.DEGREE),
        0x1E to Uas(0.0000305, UasUnit.RATIO),
        0x20 to Uas(0.00390625, UasUnit.RATIO),
        0x22 to Uas(1.0, UasUnit.HERTZ),
        0x24 to Uas(1.0, UasUnit.COUNT),
        0x25 to Uas(1.0, UasUnit.KILOMETER),
        0x27 to Uas(0.01, UasUnit.GRAMS_PER_SECOND),
        0x2F to Uas(0.01, UasUnit.PERCENT),
        0x34 to Uas(1.0, UasUnit.MINUTE),
        0x81 to Uas(1.0, UasUnit.COUNT, signed = true),
        0x82 to Uas(0.1, UasUnit.COUNT, signed = true),
        0x83 to Uas(0.01, UasUnit.COUNT, signed = true),
        0x84 to Uas(0.001, UasUnit.COUNT, signed = true),
        0x8A to Uas(0.122, UasUnit.MILLIVOLT, signed = true),
        0x8B to Uas(0.001, UasUnit.VOLT, signed = true),
        0x8C to Uas(0.01, UasUnit.VOLT, signed = true),
        0x90 to Uas(1.0, UasUnit.MILLISECOND, signed = true),
        0x96 to Uas(0.1, UasUnit.CELSIUS, signed = true),
        0x9C to Uas(0.01, UasUnit.DEGREE, signed = true),
        0xAF to Uas(0.01, UasUnit.PERCENT, signed = true),
        0xFC to Uas(0.01, UasUnit.KILOPASCAL, signed = true),
        0xFD to Uas(0.001, UasUnit.KILOPASCAL, signed = true),
        0xFE to Uas(0.25, UasUnit.PASCAL, signed = true),
    )

    fun of(id: Int): Uas? = TABLE[id]
}

/** What a monitor id belongs to (B8), for grouping; names are rendered in :core:obdtext. */
enum class MidGroup { OXYGEN_SENSOR, CATALYST, EGR_VVT, EVAP, OXYGEN_HEATER, HEATED_CATALYST, SECONDARY_AIR, FUEL_SYSTEM, BOOST, NOX, MISFIRE, PM_FILTER, OTHER }

/** One on-board test result: values scaled by [uas] (null = unknown UAS id, raw numbers kept in [raw]). */
data class TestResult(
    val mid: Int,
    val tid: Int,
    val uasId: Int,
    val value: Double,
    val min: Double,
    val max: Double,
    val unit: UasUnit?,
    val raw: Triple<Int, Int, Int>,
) {
    val passed: Boolean get() = value >= min && value <= max
    val group: MidGroup get() = Mode06.groupOf(mid)
}

/** Mode 06 on CAN (ISO 15765): supported-MID masks and 9-byte result blocks. */
object Mode06 {
    fun groupOf(mid: Int): MidGroup = when (mid) {
        in 0x01..0x10 -> MidGroup.OXYGEN_SENSOR
        in 0x21..0x24 -> MidGroup.CATALYST
        in 0x31..0x38 -> MidGroup.EGR_VVT
        in 0x39..0x3D -> MidGroup.EVAP
        in 0x41..0x50 -> MidGroup.OXYGEN_HEATER
        in 0x61..0x64 -> MidGroup.HEATED_CATALYST
        in 0x71..0x74 -> MidGroup.SECONDARY_AIR
        in 0x81..0x84 -> MidGroup.FUEL_SYSTEM
        0x85, 0x86 -> MidGroup.BOOST
        in 0x90..0x99 -> MidGroup.NOX
        in 0xA1..0xAD -> MidGroup.MISFIRE
        0xB0, 0xB1 -> MidGroup.PM_FILTER
        else -> MidGroup.OTHER
    }

    /** MIDs listed in a "46 base m1 m2 m3 m4" mask reply (including the next mask id, base+0x20). */
    fun supported(message: ByteArray, base: Int): Set<Int>? {
        val u = message.toUnsigned()
        if (u.size < 6 || u[0] != 0x46 || u[1] != base) return null
        return SupportedPids.decode(base, u.subList(2, 6).toIntArray())
    }

    /** "46 MID TID UAS vH vL minH minL maxH maxL [MID TID ...]": every 9-byte block of the requested [mid]. */
    fun results(message: ByteArray, mid: Int): List<TestResult> {
        val u = message.toUnsigned()
        if (u.isEmpty() || u[0] != 0x46) return emptyList()
        return u.drop(1).chunked(9).filter { it.size == 9 && it[0] == mid }.map { b ->
            val raw = Triple(b[3] * 256 + b[4], b[5] * 256 + b[6], b[7] * 256 + b[8])
            val uas = UasTable.of(b[2])
            fun scale(v: Int) = uas?.apply(v) ?: v.toDouble()
            TestResult(b[0], b[1], b[2], scale(raw.first), scale(raw.second), scale(raw.third), uas?.unit, raw)
        }
    }
}
