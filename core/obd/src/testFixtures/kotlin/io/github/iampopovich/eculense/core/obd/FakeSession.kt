package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.SensorReading

class FakeSession(
    private val values: Map<Int, Double> = emptyMap(),
    private val failing: Set<Int> = emptySet(),
    var dtcs: List<Dtc> = emptyList(),
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
        clearCalls++
        dtcs = emptyList()
        return true
    }
    override suspend fun readVin(): String? = null
}
