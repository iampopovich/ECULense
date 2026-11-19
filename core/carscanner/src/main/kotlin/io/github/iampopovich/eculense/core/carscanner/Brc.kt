package io.github.iampopovich.eculense.core.carscanner

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal const val MAGIC = "CARSCANNERRECORD"
internal const val SENSOR_DEF = 0x00D56190
internal const val DATA_POINT = 0x01274350
internal const val GPS = 0x1F76FB5C
internal const val DTC_EVENT = 0x16E13228

/** .NET ticks (100 ns since 0001-01-01) at 1970-01-01. */
const val DOTNET_EPOCH_TICKS = 621_355_968_000_000_000L

class BrcFormatException(message: String) : Exception(message)

data class BrcHeader(
    val version: Int,
    val vin: String,
    val carName: String,
    val profile: String,
    val adapter: String,
    val startedAtMs: Long,
)

sealed interface BrcRecord {
    data class SensorDef(val id: Int, val fullName: String, val shortName: String, val format: Int) : BrcRecord
    data class DataPoint(val secondsFromStart: Double, val sensorId: Int, val value: Double) : BrcRecord
    data class GpsPoint(val secondsFromStart: Double, val latitude: Double, val longitude: Double) : BrcRecord
    data class DtcEvent(val atMs: Long, val json: String) : BrcRecord
}

/**
 * Car Scanner recording reader. Times in the file are the phone's wall-clock local time without a zone,
 * so zone = the zone where it was recorded (defaults to the device's current zone).
 */
class BrcReader(bytes: ByteArray, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    /** true if the last record is truncated (Car Scanner closed the file mid-record); this is normal, 5 of 73 files. */
    var truncated = false
        private set

    val header: BrcHeader = try {
        readHeader()
    } catch (e: BufferUnderflowException) {
        throw BrcFormatException("Файл слишком короткий для записи Car Scanner")
    }

    fun records(): Sequence<BrcRecord> = sequence {
        while (buf.remaining() >= 4) {
            val offset = buf.position()
            val record = try {
                when (val marker = buf.int) {
                    SENSOR_DEF -> BrcRecord.SensorDef(buf.int, string(), string(), buf.int)
                    DATA_POINT -> BrcRecord.DataPoint(buf.double, buf.int, buf.double)
                    GPS -> BrcRecord.GpsPoint(buf.double, buf.double, buf.double)
                    DTC_EVENT -> BrcRecord.DtcEvent(ticksToEpochMs(buf.long), string())
                    else -> throw BrcFormatException("Неизвестная запись 0x%08X по смещению %d".format(marker, offset))
                }
            } catch (e: BufferUnderflowException) {
                truncated = true
                return@sequence
            }
            yield(record)
        }
    }

    private fun readHeader(): BrcHeader {
        val magic = runCatching { string() }.getOrNull()
        if (magic != MAGIC) throw BrcFormatException("Это не запись Car Scanner")
        return BrcHeader(
            version = buf.int,
            vin = string(),
            carName = string(),
            profile = string(),
            adapter = string(),
            startedAtMs = ticksToEpochMs(buf.long),
        )
    }

    /** .NET BinaryWriter string: length in 7-bit groups, then UTF-8. */
    private fun string(): String {
        var length = 0
        var shift = 0
        while (true) {
            val b = buf.get().toInt() and 0xFF
            length = length or ((b and 0x7F) shl shift)
            if (b < 0x80) break
            shift += 7
            if (shift > 28) throw BrcFormatException("Битая длина строки")
        }
        if (length > buf.remaining()) throw BufferUnderflowException()
        return ByteArray(length).also { buf.get(it) }.decodeToString()
    }

    private fun ticksToEpochMs(ticks: Long): Long {
        val wallClockAsUtc = Instant.ofEpochMilli((ticks - DOTNET_EPOCH_TICKS) / 10_000)
        return wallClockAsUtc.atZone(ZoneOffset.UTC).toLocalDateTime().atZone(zone).toInstant().toEpochMilli()
    }
}
