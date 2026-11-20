package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.SensorReading
import kotlinx.coroutines.delay

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
        val reset = try {
            link.send("ATZ", timeoutMs = 5_000, idle = false)
        } catch (e: ElmTimeoutException) {
            ""
        }
        // Clones keep printing banners after the reset prompt: wait, then drop whatever came.
        delay(RESET_SETTLE_MS)
        link.drain()
        var version = versionIn(reset)
        for (command in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) link.send(command)
        if (version.isEmpty()) version = versionIn(runCatching { link.send("ATI") }.getOrDefault(""))
        when (val probe = exchange("0100", timeoutMs = SEARCH_TIMEOUT_MS, idle = false)) {
            is ElmResult.Error -> throw ObdException(ObdError.NoProtocol(probe.message))
            ElmResult.NoData -> throw ObdException(ObdError.EcuSilent)
            ElmResult.BusInitFailed -> throw ObdException(ObdError.BusInitFailed)
            is ElmResult.Data -> Unit
        }
        val dpn = (exchange("ATDPN") as? ElmResult.Data)?.lines?.lastOrNull().orEmpty()
        val protocol = dpn.removePrefix("A").singleOrNull()?.takeIf { it in KNOWN_PROTOCOLS } ?: tryProtocols(dpn)
        isCan = protocol in CAN_PROTOCOLS
        val voltage = (exchange("ATRV") as? ElmResult.Data)?.lines?.lastOrNull()?.removeSuffix("V")?.toDoubleOrNull()
        return AdapterInfo(version, protocol, isCan, voltage)
    }

    private fun versionIn(reply: String): String = reply.lines().map { it.trim() }.lastOrNull { it.startsWith("ELM") } ?: ""

    /**
     * The adapter answered but cannot name the protocol (clones report "0"/"A0"): try them one by one, so CAN vs
     * K-Line is never guessed from an unknown number.
     */
    private suspend fun tryProtocols(dpn: String): Char {
        link.note("ATDPN '$dpn' is not a protocol number; trying protocols one by one")
        for (p in FALLBACK_ORDER) {
            exchange("ATTP$p")
            val probe = try {
                exchange("0100", timeoutMs = TRY_PROTOCOL_TIMEOUT_MS, idle = false)
            } catch (e: ElmTimeoutException) {
                continue
            }
            if (probe is ElmResult.Data && elmMessages(probe.lines).any { replyMatches("0100", it) }) return p
        }
        throw ObdException(ObdError.NoProtocol("ATDPN $dpn"))
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
        val result = exchange("04", timeoutMs = 5_000)
        return result is ElmResult.Data && result.lines.any { it.startsWith("44") }
    }

    /** MVP: VIN is assembled only from a single-message CAN response. K-Line VIN (5 numbered lines) — backlog 1.24. */
    override suspend fun readVin(): String? {
        val messages = query("0902", timeoutMs = 5_000) ?: return null
        val message = messages.map { it.toUnsigned() }.firstOrNull { it.size > 3 && it[0] == 0x49 } ?: return null
        return message.drop(3).map { it.toChar() }.filter { it.isLetterOrDigit() }
            .joinToString("").takeIf { it.length == 17 }
    }

    /** Send + parse; "STOPPED" (the adapter was interrupted) is retried once — the link has already read up to '>'. */
    private suspend fun exchange(command: String, timeoutMs: Long = 2_000, idle: Boolean = true): ElmResult {
        val result = ElmResponseParser.parse(link.send(command, timeoutMs, idle), command)
        if (result is ElmResult.Error && result.message.contains("STOPPED")) {
            link.note("'$command': STOPPED, retrying once")
            return ElmResponseParser.parse(link.send(command, timeoutMs, idle), command)
        }
        return result
    }

    /**
     * null = NO DATA (PID not supported or the ECU stayed silent). Adapter error → ObdException.
     * Messages that do not answer [command] (a late reply to an earlier request) are dropped; if only such messages
     * came, the own reply may still be on its way, so one more reply is awaited.
     */
    private suspend fun query(command: String, timeoutMs: Long = 2_000): List<ByteArray>? {
        val messages = messagesOf(command, exchange(command, timeoutMs)) ?: return null
        val own = messages.filter { replyMatches(command, it) }
        if (own.isNotEmpty() || messages.isEmpty()) return own
        link.note("'$command': dropped a reply to another request")
        val next = link.awaitNextReply(command, timeoutMs) ?: return null
        return messagesOf(command, ElmResponseParser.parse(next, command))
            ?.filter { replyMatches(command, it) }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun messagesOf(command: String, result: ElmResult): List<ByteArray>? = when (result) {
        is ElmResult.Data -> elmMessages(result.lines)
        ElmResult.NoData -> null
        ElmResult.BusInitFailed -> throw ObdException(ObdError.BusInitFailed)
        is ElmResult.Error -> throw ObdException(ObdError.CommandFailed(command, result.message))
    }

    companion object {
        private const val RESET_SETTLE_MS = 1_000L
        private const val SEARCH_TIMEOUT_MS = 15_000L
        private const val TRY_PROTOCOL_TIMEOUT_MS = 10_000L
        private const val KNOWN_PROTOCOLS = "123456789ABC"
        private const val CAN_PROTOCOLS = "6789ABC"
        private const val FALLBACK_ORDER = "681792345A"

        /** Positive reply: SID + 0x40 (and the same PID for services 01/02/09); negative: 7F SID. */
        internal fun replyMatches(command: String, message: ByteArray): Boolean {
            if (command.length < 2 || command.length % 2 != 0 || !command.all { it in "0123456789ABCDEFabcdef" }) return true
            val request = command.hexToBytes().toUnsigned()
            val reply = message.toUnsigned()
            if (reply.isEmpty()) return false
            val sid = request[0]
            if (reply[0] == 0x7F) return reply.size >= 2 && reply[1] == sid
            if (reply[0] != sid + 0x40) return false
            if (request.size >= 2 && sid in setOf(0x01, 0x02, 0x09)) return reply.size >= 2 && reply[1] == request[1]
            return true
        }
    }
}
