package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.SensorReading

/** High-level vehicle operations. ELM327 implementation — Elm327Session; in feature tests — FakeSession. */
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

    override suspend fun supportedPids(): Set<Int> {
        val result = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xC0) {
            val messages = query("01%02X".format(base)) ?: break
            val masks = messages.map { it.toUnsigned() }.filter { it.size >= 6 && it[0] == 0x41 && it[1] == base }
            if (masks.isEmpty()) break
            masks.forEach { u -> result += SupportedPids.decode(base, u.subList(2, 6).toIntArray()) }
            if (base + 0x20 !in result) break
            base += 0x20
        }
        return result
    }

    override suspend fun read(pid: Pid): SensorReading? {
        val messages = query(pid.command) ?: return null
        val value = messages.firstNotNullOfOrNull { pid.decodeFrom(it) } ?: return null
        return SensorReading(pid.sensorId, value, pid.unit, clock())
    }

    override suspend fun readDtcs(kind: DtcKind): List<Dtc> {
        val (request, responseSid) = when (kind) {
            DtcKind.STORED -> "03" to 0x43
            DtcKind.PENDING -> "07" to 0x47
            DtcKind.PERMANENT -> "0A" to 0x4A
        }
        val messages = query(request, timeoutMs = 5_000) ?: return emptyList()
        return DtcDecoder.parse(messages, responseSid, isCan, kind)
    }

    override suspend fun clearDtcs(): Boolean {
        val result = ElmResponseParser.parse(link.send("04", timeoutMs = 5_000))
        return result is ElmResult.Data && result.lines.any { it.startsWith("44") }
    }

    /** MVP: VIN is assembled only from a single-message CAN response. K-Line VIN (5 numbered lines) — backlog 1.24. */
    override suspend fun readVin(): String? {
        val messages = query("0902", timeoutMs = 5_000) ?: return null
        val message = messages.map { it.toUnsigned() }.firstOrNull { it.size > 3 && it[0] == 0x49 } ?: return null
        return message.drop(3).map { it.toChar() }.filter { it.isLetterOrDigit() }
            .joinToString("").takeIf { it.length == 17 }
    }

    /** null = NO DATA (PID not supported or the ECU stayed silent). Adapter error → ObdException. */
    private suspend fun query(command: String, timeoutMs: Long = 2_000): List<ByteArray>? =
        when (val result = ElmResponseParser.parse(link.send(command, timeoutMs))) {
            is ElmResult.Data -> elmMessages(result.lines)
            ElmResult.NoData -> null
            is ElmResult.Error -> throw ObdException("$command → ${result.message}")
        }
}
