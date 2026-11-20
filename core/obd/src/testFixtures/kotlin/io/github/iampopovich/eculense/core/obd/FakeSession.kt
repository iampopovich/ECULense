package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.SensorReading

/**
 * [replies]: raw diagnostics requests ("0101", "020C00") → reply hex ("41 01 83 07 65 00"; several messages separated
 * by '|'), used by [request]. [afterClear] replaces [replies] once [clearDtcs] was called. [clearError] makes the
 * clear fail with that typed error.
 */
class FakeSession(
    private val values: Map<Int, Double> = emptyMap(),
    private val failing: Set<Int> = emptySet(),
    var dtcs: List<Dtc> = emptyList(),
    private val replies: Map<String, String> = emptyMap(),
    private val afterClear: Map<String, String>? = null,
    private val clearError: ObdError? = null,
    override val isCanBus: Boolean = true,
) : ObdSession {
    var clearCalls = 0

    override suspend fun initialize() = AdapterInfo("fake", '6', true, 12.5)
    override suspend fun supportedPids() = values.keys
    override suspend fun read(pid: Pid): SensorReading? {
        if (pid.code in failing) throw ObdException(ObdError.Other("failure ${pid.command}"))
        return values[pid.code]?.let { SensorReading(pid.sensorId, it, pid.unit, 0L) }
    }
    override suspend fun readDtcs(kind: DtcKind) = dtcs.filter { it.kind == kind }
    override suspend fun clearDtcs(): Boolean {
        clearError?.let { throw ObdException(it) }
        clearCalls++
        dtcs = emptyList()
        return true
    }
    override suspend fun readVin(): String? = null

    override suspend fun request(command: String, timeoutMs: Long): List<ByteArray>? {
        val table = if (clearCalls > 0 && afterClear != null) afterClear else replies
        val reply = table[command] ?: return null
        return reply.split('|').map { line ->
            val hex = line.replace(" ", "")
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
    }
}
