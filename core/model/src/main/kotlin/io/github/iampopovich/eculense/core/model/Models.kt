package io.github.iampopovich.eculense.core.model

/** One sensor value. sensorId is stable across sessions: "01.0C" = service 01, PID 0x0C (RPM). */
data class SensorReading(
    val sensorId: String,
    val value: Double,
    val unit: String,
    val timestampMs: Long,
)

enum class DtcKind { STORED, PENDING, PERMANENT }

data class Dtc(val code: String, val kind: DtcKind)

/** What the adapter reported about itself after init. protocolNumber: ATDPN response without the 'A' prefix. */
data class AdapterInfo(
    val version: String,
    val protocolNumber: Char,
    val isCan: Boolean,
    val batteryVoltage: Double?,
)

enum class SessionSource { LIVE, IMPORT_CARSCANNER }

data class Session(
    val id: Long,
    val name: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val source: SessionSource,
)
