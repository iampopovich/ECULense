package io.github.iampopovich.eculense.core.model

/** Одно значение датчика. sensorId стабилен между сессиями: "01.0C" = сервис 01, PID 0x0C (обороты). */
data class SensorReading(
    val sensorId: String,
    val value: Double,
    val unit: String,
    val timestampMs: Long,
)

enum class DtcKind { STORED, PENDING, PERMANENT }

data class Dtc(val code: String, val kind: DtcKind)

/** Что адаптер рассказал о себе после инициализации. protocolNumber — ответ ATDPN без префикса 'A'. */
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
