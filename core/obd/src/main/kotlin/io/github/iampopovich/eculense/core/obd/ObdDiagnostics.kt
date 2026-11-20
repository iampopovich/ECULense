package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.FreezeFrame
import io.github.iampopovich.eculense.core.model.SensorReading

/**
 * Diagnostic reads on top of [ObdSession.request] (B1-B3, B7, B8). Every read is optional: an ECU that does not
 * support a request gives null/empty, never an exception. A lost link (IOException) still propagates.
 */
class ObdDiagnostics(private val session: ObdSession, private val clock: () -> Long = System::currentTimeMillis) {

    /** PID 01: MIL, DTC count, monitors since the codes were cleared. */
    suspend fun readiness(): Readiness? = pid01(0x01)?.takeIf { it.size >= 4 }?.let(ReadinessDecoder::status)

    /** PID 41: monitors of this drive cycle (null when unsupported). */
    suspend fun readinessThisCycle(ignition: Ignition): List<MonitorState>? =
        pid01(0x41)?.takeIf { it.size >= 4 }?.let { ReadinessDecoder.thisDriveCycle(it, ignition) }

    /** B2 counters; [supported] (service 01 mask) skips PIDs the ECU does not list. Empty set = try them all. */
    suspend fun counters(supported: Set<Int> = emptySet()): ClearedCounters {
        val data = ClearedCounters.PIDS.filter { supported.isEmpty() || it in supported }
            .mapNotNull { pid -> pid01(pid)?.let { pid to it } }.toMap()
        return ClearedCounters.decode(data)
    }

    /**
     * Freeze frame 0: 0202 names the DTC, the 02 00/20/40 masks list the PIDs, each PID is decoded like service 01.
     * Requests carry the frame number ("02 0C 00", reply "42 0C 00 data"); when the ECU rejects that form, the short
     * form "02 0C" is used for the whole read. null = no freeze frame stored.
     */
    suspend fun freezeFrame(): FreezeFrame? {
        var withFrame = true
        val firstMask = mode02(0x00, 4, true) ?: mode02(0x00, 4, false)?.also { withFrame = false } ?: return null
        val supported = mutableSetOf<Int>()
        var mask: IntArray? = firstMask
        var base = 0x00
        while (mask != null) {
            supported += SupportedPids.decode(base, mask)
            if (base + 0x20 !in supported || base >= 0xC0) break
            base += 0x20
            mask = mode02(base, 4, withFrame)
        }
        val dtc = if (0x02 in supported) mode02(0x02, 2, withFrame)?.takeIf { it[0] != 0 || it[1] != 0 }?.let { DtcDecoder.code(it[0], it[1]) } else null
        val readings = StandardPids.all.filter { it.code in supported }.mapNotNull { pid ->
            val data = mode02(pid.code, pid.bytes, withFrame) ?: return@mapNotNull null
            val frame = ByteArray(2 + pid.bytes) { i -> (if (i == 0) 0x41 else if (i == 1) pid.code else data[i - 2]).toByte() }
            pid.decodeFrom(frame)?.let { SensorReading(pid.sensorId, it, pid.unit, clock()) }
        }
        if (dtc == null && readings.isEmpty()) return null
        return FreezeFrame(dtc, readings)
    }

    /** Mode 09: VIN, calibration ids and CVNs. */
    suspend fun vehicleInfo(): VehicleInfo {
        val can = session.isCanBus
        return VehicleInfo(
            vin = quiet("0902", LONG_MS)?.let { VehicleInfoDecoder.vin(it, can) },
            calibrationIds = quiet("0904", LONG_MS)?.let { VehicleInfoDecoder.calibrationIds(it, can) }.orEmpty(),
            cvns = quiet("0906", LONG_MS)?.let { VehicleInfoDecoder.cvns(it, can) }.orEmpty(),
        )
    }

    /** B8 on-board test results; null on K-Line (the legacy TID format is not supported). */
    suspend fun mode06(): List<TestResult>? {
        if (!session.isCanBus) return null
        val mids = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xE0) {
            val mask = quiet("06%02X".format(base))?.firstNotNullOfOrNull { Mode06.supported(it, base) } ?: break
            mids += mask
            if (base + 0x20 !in mask) break
            base += 0x20
        }
        return mids.filter { it % 0x20 != 0 }.sorted().take(MAX_MIDS).flatMap { mid ->
            quiet("06%02X".format(mid))?.flatMap { Mode06.results(it, mid) }.orEmpty()
        }
    }

    private suspend fun pid01(pid: Int): IntArray? =
        quiet("01%02X".format(pid))?.map { it.toUnsigned() }
            ?.firstOrNull { it.size > 2 && it[0] == 0x41 && it[1] == pid }?.drop(2)?.toIntArray()

    /** Data bytes of "42 pid [frame] data": the frame byte is detected from the expected data length. */
    private suspend fun mode02(pid: Int, bytes: Int, withFrame: Boolean): IntArray? {
        val command = if (withFrame) "02%02X00".format(pid) else "02%02X".format(pid)
        val reply = quiet(command)?.map { it.toUnsigned() }?.firstOrNull { it.size >= 2 && it[0] == 0x42 && it[1] == pid } ?: return null
        val rest = reply.drop(2)
        // A reply one byte longer than the PID data carries the frame number (some ECUs send it to the short form too).
        return when {
            rest.size >= bytes + 1 -> rest.drop(1).take(bytes)
            rest.size >= bytes -> rest.take(bytes)
            else -> null
        }?.toIntArray()
    }

    /** Request with adapter errors and negative replies mapped to null. */
    private suspend fun quiet(command: String, timeoutMs: Long = 2_000): List<ByteArray>? = try {
        session.request(command, timeoutMs)?.filter { it.isNotEmpty() && (it[0].toInt() and 0xFF) != 0x7F }?.takeIf { it.isNotEmpty() }
    } catch (e: ObdException) {
        null
    }

    private companion object {
        const val LONG_MS = 5_000L
        const val MAX_MIDS = 64
    }
}
