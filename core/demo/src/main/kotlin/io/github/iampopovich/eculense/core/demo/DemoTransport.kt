package io.github.iampopovich.eculense.core.demo

import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.EndpointLabel
import io.github.iampopovich.eculense.core.obd.ObdError
import io.github.iampopovich.eculense.core.obd.ObdIoException
import io.github.iampopovich.eculense.core.obd.ObdTransport
import io.github.iampopovich.eculense.core.obd.StandardPids
import io.github.iampopovich.eculense.core.obd.TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Virtual ELM327 v1.5 on CAN 11/500 (protocol 6), connected to the "running car" [DemoEngine].
 * Understands exactly what Elm327Session sends and answers like a real adapter:
 * echo/spaces/headers/linefeeds per ATE/ATS/ATH/ATL, SEARCHING... on the first request,
 * masks 0100/0120/0140, NO DATA for unsupported PIDs, DTCs in CAN format with a count, VIN as a multi-frame response.
 */
class DemoTransport internal constructor(
    private val clock: () -> Long,
    private val latencyMs: (String) -> Long,
    private val engine: DemoEngine,
    private val flaky: Boolean = false,
    lateScope: CoroutineScope? = null,
    /** Elapsed-time window (ms after open) in which the engine is off: the ECU is silent, ATRV shows 12.3 V. */
    private val engineOff: LongRange? = null,
) : ObdTransport {

    constructor(
        clock: () -> Long = System::currentTimeMillis,
        latencyMs: (String) -> Long = ::defaultLatency,
        flaky: Boolean = false,
        engineOff: LongRange? = null,
    ) : this(clock, latencyMs, DemoEngine(), flaky, engineOff = engineOff)

    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val inbox = StringBuilder()
    private var startMs = 0L
    @Volatile private var closed = false

    // Flaky clone mode: replies that come after the command timeout are delivered from this scope.
    private val ownsScope = flaky && lateScope == null
    private val scope: CoroutineScope? = if (flaky) lateScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default) else null
    private var lateReply: Job? = null
    private var liveRequests = 0

    private var echo = true
    private var linefeeds = false
    private var spaces = true
    private var headers = false
    private var protocolFound = false

    private var stored = listOf(0x0133, 0x0300)
    private var pending = listOf(0x0171)
    private var clearedAtMs: Long? = null

    override suspend fun open() {
        startMs = clock()
    }

    override suspend fun write(data: ByteArray) {
        if (closed) throw ObdIoException(ObdError.DemoDisconnected)
        // A real adapter is busy until its late reply is out; keep the order of replies.
        lateReply?.join()
        inbox.append(data.decodeToString())
        while (true) {
            val end = inbox.indexOf("\r")
            if (end < 0) break
            val raw = inbox.substring(0, end)
            inbox.delete(0, end + 1)
            respond(raw.trim())
        }
    }

    override suspend fun read(): ByteArray = try {
        incoming.receive()
    } catch (e: ClosedReceiveChannelException) {
        throw ObdIoException(ObdError.DemoDisconnected, e)
    }

    override suspend fun readAvailable(): ByteArray? = incoming.tryReceive().getOrNull()

    override suspend fun close() {
        closed = true
        if (ownsScope) scope?.cancel()
        incoming.close()
    }

    private suspend fun respond(raw: String) {
        if (raw.isEmpty()) return
        val command = raw.replace(" ", "").uppercase()
        val wasSearching = !protocolFound
        val echoed = echo // ATE0 itself is still echoed
        val body = handle(command)
        val searching = wasSearching && protocolFound
        delay(latencyMs(command) + if (searching) SEARCH_DELAY_MS else 0)
        val eol = if (linefeeds) "\r\n" else "\r"
        val text = buildString {
            if (echoed) append(raw).append(eol)
            if (searching) append("SEARCHING...").append(eol)
            if (flaky && command == "ATZ") append("+READY").append(eol)
            append(body.replace("\r", eol)).append(eol).append(eol).append('>')
        }
        val bytes = text.encodeToByteArray()
        val live = isLivePid(command)
        if (live) liveRequests++
        when {
            flaky && command == "ATZ" -> incoming.send(GARBAGE + bytes)
            flaky && live && liveRequests % LATE_EVERY == 0 -> lateReply = scope?.launch {
                delay(LATE_DELAY_MS)
                incoming.send(bytes)
            }
            flaky && live && liveRequests % ERROR_EVERY == 0 -> incoming.send("CAN ERROR$eol$eol>".encodeToByteArray())
            else -> incoming.send(bytes)
        }
    }

    /** Service 01 data request (with or without a response-count suffix, single or multi-PID), not a mask. */
    private fun isLivePid(command: String): Boolean {
        if (!command.startsWith("01") || command.length < 4) return false
        val pids = command.substring(2).let { if (it.length % 2 == 1) it.dropLast(1) else it }.chunked(2)
        return pids.isNotEmpty() && pids.all { p -> p.length == 2 && p.toIntOrNull(16)?.let { it % 0x20 != 0 } == true }
    }

    private fun engineIsOff(): Boolean = engineOff?.contains(elapsed()) == true

    private fun handle(command: String): String =
        if (command.startsWith("AT")) handleAt(command.substring(2)) else handleObd(command)

    private fun handleAt(at: String): String = when {
        at == "Z" -> {
            echo = true; linefeeds = false; spaces = true; headers = false; protocolFound = false
            VERSION
        }
        at == "I" || at == "WS" -> VERSION
        at == "@1" -> "OBDII to RS232 Interpreter"
        at == "D" -> {
            linefeeds = false; spaces = true; headers = false
            "OK"
        }
        at == "E0" || at == "E1" -> { echo = at == "E1"; "OK" }
        at == "L0" || at == "L1" -> { linefeeds = at == "L1"; "OK" }
        at == "S0" || at == "S1" -> { spaces = at == "S1"; "OK" }
        at == "H0" || at == "H1" -> { headers = at == "H1"; "OK" }
        at.matches(Regex("(SP|TP)A?[0-9A-C]")) -> { protocolFound = false; "OK" }
        at == "DPN" -> if (flaky) "0" else if (protocolFound) "A6" else "A0"
        at == "DP" -> if (protocolFound) "AUTO, ISO 15765-4 (CAN 11/500)" else "AUTO"
        at == "RV" -> String.format(Locale.US, "%.1fV", if (engineIsOff()) ENGINE_OFF_VOLTAGE else engine.voltage(elapsed()))
        at.matches(Regex("ST[0-9A-F]{2}|AT[0-2]|M[01]|CAF[01]|AL|NL|R[01]|PC")) -> "OK"
        else -> "?"
    }

    private fun handleObd(raw: String): String {
        if (raw.length < 2 || !raw.all { it in HEX }) return "?"
        // A trailing single digit is the response-count suffix ("010C1"): answer as for "010C".
        val command = if (raw.length % 2 == 1 && raw.length >= 3) raw.dropLast(1) else raw
        if (command.length % 2 != 0) return "?"
        protocolFound = true
        if (engineIsOff()) return NO_DATA
        val mode = command.substring(0, 2).toInt(16)
        val pid = command.drop(2).take(2).takeIf { it.length == 2 }?.toInt(16)
        if (mode == 0x01 && command.length > 4) return multiPid(command.substring(2).chunked(2).map { it.toInt(16) })
        return when (mode) {
            0x01 -> pid?.let { mode01(it) }?.let(::singleFrame) ?: NO_DATA
            0x03 -> singleFrame(dtcReply(0x43, stored))
            0x07 -> singleFrame(dtcReply(0x47, pending))
            0x0A -> singleFrame(dtcReply(0x4A, emptyList()))
            0x04 -> {
                stored = emptyList()
                pending = emptyList()
                clearedAtMs = elapsed()
                singleFrame("44")
            }
            0x09 -> when (pid) {
                0x00 -> singleFrame("490040000000")
                0x02 -> multiFrame("490201" + VIN_HEX)
                else -> NO_DATA
            }
            else -> NO_DATA
        }
    }

    /** Up to 6 PIDs in one service 01 request: one combined "41 pid data pid data..." message (ISO-TP when long). */
    private fun multiPid(pids: List<Int>): String {
        if (pids.size > 6) return "?"
        val parts = pids.mapNotNull { mode01(it)?.drop(2) }
        if (parts.isEmpty()) return NO_DATA
        val hex = "41" + parts.joinToString("")
        return if (hex.length / 2 <= 7) singleFrame(hex) else multiFrame(hex)
    }

    private fun mode01(pid: Int): String? = when {
        pid % 0x20 == 0 -> supportMask(pid)
        pid == 0x01 -> "4101%02X076500".format(if (stored.isEmpty()) 0 else 0x80 or stored.size)
        else -> encodePid(pid, engine.values(elapsed(), clearedAtMs))
    }

    private fun dtcReply(sid: Int, codes: List<Int>): String =
        "%02X%02X".format(sid, codes.size) + codes.joinToString("") { "%04X".format(it) }

    private fun elapsed(): Long = clock() - startMs

    /** Single CAN frame response. hex — message bytes without spaces. */
    private fun singleFrame(hex: String): String {
        val data = if (headers) "7E8%02X".format(hex.length / 2) + hex else hex
        return format(data, firstHeaderLength = if (headers) 3 else 0)
    }

    /** ISO-TP: First Frame + Consecutive Frames. With ATH0 the ELM prints the length and «N:» before each frame. */
    private fun multiFrame(hex: String): String {
        val bytes = hex.chunked(2)
        val first = bytes.take(6)
        val rest = bytes.drop(6).chunked(7).map { it + List(7 - it.size) { "00" } }
        return if (headers) {
            val lines = mutableListOf("7E8" + "1%03X".format(bytes.size) + first.joinToString(""))
            rest.forEachIndexed { i, frame -> lines += "7E8" + "%X".format(0x20 + ((i + 1) and 0x0F)) + frame.joinToString("") }
            lines.joinToString("\r") { format(it, firstHeaderLength = 3) }
        } else {
            val lines = mutableListOf("%03X".format(bytes.size), "0:" + format(first.joinToString(""), 0))
            rest.forEachIndexed { i, frame -> lines += "${"%X".format((i + 1) and 0x0F)}:" + format(frame.joinToString(""), 0) }
            lines.joinToString("\r") { it.replace(":", if (spaces) ": " else ":") }
        }
    }

    private fun format(hex: String, firstHeaderLength: Int): String {
        if (!spaces) return hex
        val head = hex.take(firstHeaderLength)
        val body = hex.drop(firstHeaderLength).chunked(2).joinToString(" ")
        return if (head.isEmpty()) body else "$head $body"
    }

    private fun supportMask(base: Int): String? {
        if (base != 0 && ANSWERED.none { it > base }) return null
        var bits = 0L
        for (i in 1..32) {
            val pid = base + i
            val advertised = pid in ANSWERED || (i == 32 && ANSWERED.any { it > pid })
            if (advertised) bits = bits or (1L shl (32 - i))
        }
        return "41%02X%08X".format(base, bits)
    }

    companion object {
        private const val VERSION = "ELM327 v1.5"
        private const val NO_DATA = "NO DATA"
        private const val SEARCH_DELAY_MS = 400L

        // Flaky clone: bytes before the reset banner, every Nth live PID answers after the 2 s command timeout.
        private val GARBAGE = byteArrayOf(0xFC.toByte(), 0x00)
        internal const val LATE_EVERY = 25
        internal const val LATE_DELAY_MS = 2_300L

        // Flaky clone: every Nth live request answers CAN ERROR once (the session recovers with ATPC + ATSP).
        internal const val ERROR_EVERY = 40
        private const val ENGINE_OFF_VOLTAGE = 12.3
        private const val HEX = "0123456789ABCDEF"
        private val VIN_HEX = DemoEngine.VIN.encodeToByteArray().joinToString("") { "%02X".format(it) }

        /** All service 01 PIDs the demo answers (except masks): 0x01 + everything from StandardPids. */
        private val ANSWERED: Set<Int> = StandardPids.all.map { it.code }.toSet() + 0x01

        /** Latency like a BT ELM327 clone: 30–60 ms per response, reset takes longer. */
        fun defaultLatency(command: String): Long =
            Random.nextLong(30, 61) + if (command == "ATZ") 300 else 0

        /** Inverse SAE formulas for StandardPids: value → response "41 PID data" (hex without spaces). */
        internal fun encodePid(pid: Int, values: Map<Int, Double>): String? {
            val v = values[pid] ?: return null
            val data = when (pid) {
                0x04, 0x11, 0x2F, 0x45 -> byte(v * 255 / 100)
                0x05, 0x0F, 0x46, 0x5C -> byte(v + 40)
                0x06, 0x07, 0x08, 0x09 -> byte((v + 100) * 128 / 100)
                0x0B, 0x0D, 0x33, 0x30 -> byte(v)
                0x03 -> byte(v) + "00"
                0x14, 0x15 -> byte(v * 200) + "FF"
                0x3C -> word((v + 40) * 10)
                0x43 -> word(v * 255 / 100)
                0x44 -> word(v * 65_536 / 2)
                0x0C -> word(v * 4)
                0x0E -> byte((v + 64) * 2)
                0x10 -> word(v * 100)
                0x1F, 0x21, 0x31 -> word(v)
                0x42 -> word(v * 1000)
                0x5E -> word(v * 20)
                else -> return null
            }
            return "41%02X".format(pid) + data
        }

        private fun byte(v: Double) = "%02X".format(v.roundToInt().coerceIn(0, 0xFF))
        private fun word(v: Double) = "%04X".format(v.roundToInt().coerceIn(0, 0xFFFF))
    }
}

/** The demo-mode "adapter": creates a fresh simulation for each connection. */
class DemoTransportFactory(
    private val clock: () -> Long = System::currentTimeMillis,
    private val latencyMs: (String) -> Long = DemoTransport.Companion::defaultLatency,
) : TransportFactory {
    override val kind = KIND
    override suspend fun discover() = listOf(ENDPOINT)
    override fun create(endpoint: AdapterEndpoint): ObdTransport = DemoTransport(clock, latencyMs)

    companion object {
        const val KIND = "demo"
        val ENDPOINT = AdapterEndpoint(KIND, "Virtual ELM327 · CAN 11/500 · no car", "Demo mode (simulation)", EndpointLabel.DEMO)
    }
}

/**
 * The demo car whose engine is switched off 30 s after connecting and started again 30 s later, so the
 * «engine off» state (A4) can be watched without a car.
 */
class EngineStopDemoTransportFactory(
    private val clock: () -> Long = System::currentTimeMillis,
    private val latencyMs: (String) -> Long = DemoTransport.Companion::defaultLatency,
    private val offWindow: LongRange = 30_000L..60_000L,
) : TransportFactory {
    override val kind = KIND
    override suspend fun discover() = listOf(ENDPOINT)
    override fun create(endpoint: AdapterEndpoint): ObdTransport = DemoTransport(clock, latencyMs, engineOff = offWindow)

    companion object {
        const val KIND = "demo-engine-off"
        val ENDPOINT = AdapterEndpoint(KIND, "Virtual ELM327 · engine stops after 30 s", "Demo: engine stops", EndpointLabel.DEMO_ENGINE_OFF)
    }
}

/**
 * The same simulation behind a misbehaving cheap clone (garbage on reset, ATDPN "0", occasional late replies),
 * so the recovery path of the protocol layer can be watched on the device.
 */
class FlakyDemoTransportFactory(
    private val clock: () -> Long = System::currentTimeMillis,
    private val latencyMs: (String) -> Long = DemoTransport.Companion::defaultLatency,
) : TransportFactory {
    override val kind = KIND
    override suspend fun discover() = listOf(ENDPOINT)
    override fun create(endpoint: AdapterEndpoint): ObdTransport = DemoTransport(clock, latencyMs, flaky = true)

    companion object {
        const val KIND = "demo-flaky"
        val ENDPOINT = AdapterEndpoint(KIND, "Virtual ELM327 clone · late replies, garbage", "Demo: flaky clone", EndpointLabel.DEMO_FLAKY)
    }
}
