package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.SensorReading

/** Высокоуровневые операции с машиной. Реализация для ELM327 — Elm327Session; в тестах фич — FakeSession. */
interface ObdSession {
    suspend fun initialize(): AdapterInfo
    suspend fun supportedPids(): Set<Int>
    suspend fun read(pid: Pid): SensorReading?
    suspend fun readDtcs(kind: DtcKind): List<Dtc>
    suspend fun clearDtcs(): Boolean
    suspend fun readVin(): String?
}

internal class Elm327Session(
    private val link: ElmLink,
    private val clock: () -> Long = System::currentTimeMillis,
) : ObdSession {
    private var isCan = true

    override suspend fun initialize(): AdapterInfo {
        val version = link.send("ATZ", timeoutMs = 5_000).lines().map { it.trim() }
            .lastOrNull { it.startsWith("ELM") } ?: "неизвестно"
        for (command in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) link.send(command)
        when (val probe = ElmResponseParser.parse(link.send("0100", timeoutMs = 15_000))) {
            is ElmResult.Error -> throw ObdException("Адаптер не нашёл протокол: ${probe.message}")
            ElmResult.NoData -> throw ObdException("ЭБУ молчит. Зажигание включено?")
            is ElmResult.Data -> Unit
        }
        val protocol = link.send("ATDPN").trim().removePrefix("A").firstOrNull() ?: '0'
        isCan = protocol in "6789ABC"
        val voltage = link.send("ATRV").trim().removeSuffix("V").toDoubleOrNull()
        return AdapterInfo(version, protocol, isCan, voltage)
    }

    override suspend fun supportedPids(): Set<Int> = TODO("задача 1.8")
    override suspend fun read(pid: Pid): SensorReading? = TODO("задача 1.8")
    override suspend fun readDtcs(kind: DtcKind): List<Dtc> = TODO("задача 1.8")
    override suspend fun clearDtcs(): Boolean = TODO("задача 1.8")
    override suspend fun readVin(): String? = TODO("задача 1.8")
}
