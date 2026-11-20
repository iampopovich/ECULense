package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.SensorReading
import kotlinx.coroutines.delay

/** One PID of a polling cycle: the reading (null = no value), whether the adapter failed, time spent on it. */
data class PidResult(val pid: Pid, val reading: SensorReading?, val failed: Boolean, val elapsedMs: Long)

/** High-level vehicle operations. ELM327 implementation — Elm327Session; in feature tests — FakeSession. */
interface ObdSession {
    suspend fun initialize(): AdapterInfo
    suspend fun supportedPids(): Set<Int>
    suspend fun read(pid: Pid): SensorReading?
    suspend fun readDtcs(kind: DtcKind): List<Dtc>
    suspend fun clearDtcs(): Boolean
    suspend fun readVin(): String?

    /** Identification of the adapter, known after [initialize]. */
    val capabilities: AdapterCapabilities get() = AdapterCapabilities.NONE

    /**
     * One polling cycle over [pids]. Adapter errors of single PIDs become failed results; an IOException (link lost)
     * is rethrown. The ELM327 session batches PIDs into multi-PID requests on CAN.
     */
    suspend fun readAll(pids: List<Pid>): List<PidResult> = pids.map { pid ->
        try {
            PidResult(pid, read(pid), failed = false, elapsedMs = 0)
        } catch (e: ObdException) {
            PidResult(pid, null, failed = true, elapsedMs = 0)
        }
    }

    /** Battery voltage measured by the adapter (ATRV); null when the adapter does not answer. */
    suspend fun batteryVoltage(): Double? = null

    /** True when the ECU answers a basic request (0100). */
    suspend fun ecuResponds(): Boolean = true

    /** On K-Line: sends a keepalive request when nothing was sent for [idleMs]. True when one was sent. */
    suspend fun keepAlive(idleMs: Long): Boolean = false

    /**
     * Raw OBD request ("0101", "020C00", "0902") for the diagnostics reads: the messages answering it, positive
     * (SID + 0x40) or negative (7F SID NRC). null = NO DATA. Adapter errors throw [ObdException].
     */
    suspend fun request(command: String, timeoutMs: Long = 2_000): List<ByteArray>? = null

    /** CAN framing (count bytes, ISO-TP) vs K-Line/J1850 (numbered lines); known after [initialize]. */
    val isCanBus: Boolean get() = true
}

/** Settings and learned adapter facts handed to a new session; [onProfile] persists what the session learned. */
internal class SessionSetup(
    val settings: AdapterSettings = AdapterSettings(),
    val profile: AdapterProfile = AdapterProfile(),
    val onProfile: suspend (AdapterProfile) -> Unit = {},
)

internal class Elm327Session(
    private val link: ElmLink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val setup: SessionSetup = SessionSetup(),
    private val recoveryBudget: RecoveryBudget = RecoveryBudget(clock = clock),
) : ObdSession {
    private var isCan = true
    private var profile = setup.profile

    /** The protocol in use; null until [initialize] finished (no recovery before that). */
    private var protocol: Char? = null
    private var countSuffix = false
    private var multiPid = true
    private var multiPidMisses = 0
    private var keepAliveCommand: String? = null
    private var recovering = false
    private val unsupported = profile.unsupported.toMutableSet()

    override var capabilities: AdapterCapabilities = AdapterCapabilities.NONE
        private set

    override suspend fun initialize(): AdapterInfo {
        protocol = null
        val reset = try {
            link.send("ATZ", timeoutMs = 5_000, idle = false)
        } catch (e: ElmTimeoutException) {
            ""
        }
        // Clones keep printing banners after the reset prompt: wait, then drop whatever came.
        delay(RESET_SETTLE_MS)
        link.drain()
        var version = versionIn(reset)
        try {
            configure()
        } catch (e: ElmTimeoutException) {
            if (reset.isBlank()) throw ObdException(ObdError.AdapterNotResponding, e)
            throw e
        }
        if (version.isEmpty()) version = versionIn(optional("ATI").orEmpty())
        identify(version)

        val manual = setup.settings.protocol?.takeIf(ObdProtocols::isKnown)
        val cached = profile.protocol?.takeIf(ObdProtocols::isKnown)
        exchange(
            when {
                manual != null -> "ATSP$manual"
                cached != null -> "ATSPA$cached"
                else -> "ATSP0"
            },
        )
        var probe = probe()
        if (probe !is ElmResult.Data && manual == null && cached != null) {
            link.note("cached protocol $cached did not answer; automatic search")
            exchange("ATSP0")
            probe = probe()
        }
        if (probe !is ElmResult.Data) throw probeFailure(probe)

        val detected = if (manual != null) {
            manual
        } else {
            val dpn = (exchange("ATDPN") as? ElmResult.Data)?.lines?.lastOrNull().orEmpty()
            dpn.removePrefix("A").singleOrNull()?.takeIf(ObdProtocols::isKnown) ?: tryProtocols(dpn)
        }
        isCan = ObdProtocols.isCan(detected)
        val ecus = elmMessages(probe.lines).count { replyMatches("0100", it) }
        countSuffix = ecus == 1 && profile.countSuffix != false
        multiPid = isCan
        adaptiveTiming(isCan)
        for (command in setup.settings.extraInit) {
            if (InitCommands.problem(command) != null) continue
            runCatching { exchange(command) }
                .onSuccess { if (it is ElmResult.Error) link.note("extra init '$command': ${it.message}") }
        }
        val voltage = batteryVoltage()
        protocol = detected
        remember(profile.copy(protocol = detected, unsupported = unsupported.toSet()))
        return AdapterInfo(version, detected, isCan, voltage)
    }

    private suspend fun configure() {
        for (command in SETTINGS) link.send(command)
    }

    /** ATI / AT@1 / STI+STDI / VTI. Each command answered with '?' is remembered and never sent again. */
    private suspend fun identify(version: String) {
        val description = optional("AT@1")
        val sti = optional("STI")
        val stdi = if (sti != null) optional("STDI") else null
        val vti = if (sti == null) optional("VTI") else null
        capabilities = AdapterCapabilities(
            version = version,
            description = description,
            stnId = sti,
            deviceId = stdi,
            vlinkerId = vti,
            kind = AdapterCapabilities.kindOf(version, sti, vti),
            unsupported = unsupported.toSet(),
        )
    }

    private suspend fun adaptiveTiming(can: Boolean) {
        val command = when (setup.settings.adaptiveTiming) {
            AdaptiveTiming.AUTO -> if (can) "ATAT1" else "ATAT2"
            AdaptiveTiming.OFF -> "ATAT0"
            AdaptiveTiming.NORMAL -> "ATAT1"
            AdaptiveTiming.AGGRESSIVE -> "ATAT2"
        }
        optional(command)
        capabilities = capabilities.copy(unsupported = unsupported.toSet())
    }

    /**
     * Optional command: its reply text, or null when the adapter does not know it ('?', error, silence). Unknown
     * commands are remembered for this adapter and skipped from then on.
     */
    private suspend fun optional(command: String): String? {
        if (command in unsupported) return null
        val raw = try {
            link.send(command, timeoutMs = OPTIONAL_TIMEOUT_MS)
        } catch (e: ElmTimeoutException) {
            unsupported += command
            return null
        }
        return when (val result = ElmResponseParser.parse(raw, command)) {
            is ElmResult.Data -> raw.lines().map { it.trim() }.filter { it.isNotEmpty() && it != command && it != "OK" }
                .joinToString(" ").ifBlank { "OK" }
            is ElmResult.Error -> {
                if (result.message == "?") unsupported += command
                null
            }
            else -> null
        }
    }

    private suspend fun remember(updated: AdapterProfile) {
        if (updated == profile) return
        profile = updated
        runCatching { setup.onProfile(updated) }
    }

    /** First request to the car; null = the adapter timed out. */
    private suspend fun probe(): ElmResult? = try {
        exchange("0100", timeoutMs = SEARCH_TIMEOUT_MS, idle = false)
    } catch (e: ElmTimeoutException) {
        null
    }

    /** Why the car did not answer 0100, refined by the battery voltage the adapter sees (A4). */
    private suspend fun probeFailure(probe: ElmResult?): Exception {
        val voltage = batteryVoltage()
        if (voltage != null && voltage < NO_POWER_V) return ObdException(ObdError.AdapterNoPower(voltage))
        val low = voltage?.takeIf { it < IGNITION_ON_V }
        return when (probe) {
            null -> ElmTimeoutException("0100")
            is ElmResult.Error ->
                if (low != null && probe.message.contains("UNABLE TO CONNECT")) {
                    ObdException(ObdError.IgnitionOff(low))
                } else {
                    ObdException(ObdError.NoProtocol(probe.message))
                }
            ElmResult.NoData -> ObdException(if (low != null) ObdError.IgnitionOff(low) else ObdError.EcuSilent)
            ElmResult.BusInitFailed -> ObdException(if (low != null) ObdError.IgnitionOff(low) else ObdError.BusInitFailed)
            is ElmResult.Data -> ObdException(ObdError.EcuSilent)
        }
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
        val messages = if (countSuffix) {
            try {
                query(pid.command + "1", matchAs = pid.command)
            } catch (e: ObdException) {
                val error = e.error
                if (error !is ObdError.CommandFailed || error.detail != "?") throw e
                // The adapter does not know the response-count suffix: never use it again with this adapter.
                link.note("response-count suffix not supported; disabled for this adapter")
                countSuffix = false
                remember(profile.copy(countSuffix = false))
                query(pid.command)
            }
        } else {
            query(pid.command)
        } ?: return null
        val value = messages.firstNotNullOfOrNull { pid.decodeFrom(it) } ?: return null
        if (countSuffix && profile.countSuffix == null) remember(profile.copy(countSuffix = true))
        return SensorReading(pid.sensorId, value, pid.unit, clock())
    }

    override suspend fun readAll(pids: List<Pid>): List<PidResult> {
        if (!isCan || !multiPid || pids.size < 2) return pids.map { single(it) }
        val results = mutableListOf<PidResult>()
        for (group in pids.chunked(MAX_MULTI_PIDS)) {
            if (!multiPid || group.size < 2) {
                group.mapTo(results) { single(it) }
                continue
            }
            val started = clock()
            val decoded = readGroup(group)
            val share = (clock() - started) / group.size
            for (pid in group) {
                val value = decoded?.get(pid.code)
                results += when {
                    value != null -> PidResult(pid, SensorReading(pid.sensorId, value, pid.unit, clock()), failed = false, elapsedMs = share)
                    decoded != null && decoded.containsKey(pid.code) -> PidResult(pid, null, failed = false, elapsedMs = share)
                    else -> single(pid)
                }
            }
        }
        return results
    }

    private suspend fun single(pid: Pid): PidResult {
        val started = clock()
        return try {
            PidResult(pid, read(pid), failed = false, elapsedMs = clock() - started)
        } catch (e: ObdException) {
            PidResult(pid, null, failed = true, elapsedMs = clock() - started)
        }
    }

    /**
     * One multi-PID request (A8, CAN only): PID code → value. A key with a null value = no data for that PID.
     * PIDs missing from the map are read one by one by the caller. Returns null when the request failed as a whole.
     */
    private suspend fun readGroup(group: List<Pid>): Map<Int, Double?>? {
        val command = "01" + group.joinToString("") { "%02X".format(it.code) }
        val result = try {
            exchange(command)
        } catch (e: ElmTimeoutException) {
            return null
        }
        return when (result) {
            ElmResult.NoData -> group.associate { it.code to null }
            ElmResult.BusInitFailed -> null
            is ElmResult.Error -> {
                if (result.message == "?") disableMultiPid("adapter answered '?'")
                null
            }
            is ElmResult.Data -> {
                val values = mutableMapOf<Int, Double?>()
                var complete = true
                for (message in elmMessages(result.lines)) {
                    val u = message.toUnsigned()
                    if (u.isEmpty() || u[0] != 0x41) continue
                    complete = parseMulti(u, group, values) && complete
                }
                if (values.isEmpty() || !complete) {
                    if (++multiPidMisses >= MULTI_PID_MISS_LIMIT) disableMultiPid("incomplete replies")
                } else {
                    multiPidMisses = 0
                }
                values
            }
        }
    }

    /** Parses "41 pid data pid data ..." with the data lengths from [Pid.bytes]; false when the reply was cut. */
    private fun parseMulti(u: List<Int>, group: List<Pid>, into: MutableMap<Int, Double?>): Boolean {
        var i = 1
        while (i < u.size) {
            val pid = group.firstOrNull { it.code == u[i] } ?: return u.drop(i).all { it == 0 } // padding is fine
            if (i + 1 + pid.bytes > u.size) return false
            val frame = ByteArray(2 + pid.bytes) { k -> (if (k == 0) 0x41 else u[i + k - 1]).toByte() }
            into[pid.code] = pid.decodeFrom(frame)
            i += 1 + pid.bytes
        }
        return true
    }

    private fun disableMultiPid(reason: String) {
        if (!multiPid) return
        link.note("multi-PID requests off: $reason")
        multiPid = false
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
        if (result !is ElmResult.Data) return false
        val messages = elmMessages(result.lines).map { it.toUnsigned() }
        // 7F 04 NRC: the ECU refused (0x22 = conditions not correct: engine running / vehicle moving).
        messages.firstOrNull { it.size >= 3 && it[0] == 0x7F && it[1] == 0x04 }?.let { throw ObdException(ObdError.ClearRefused(it[2])) }
        return messages.any { it.firstOrNull() == 0x44 } || result.lines.any { it.startsWith("44") }
    }

    override val isCanBus: Boolean get() = isCan

    override suspend fun request(command: String, timeoutMs: Long): List<ByteArray>? = query(command, timeoutMs)

    /** VIN from mode 09 PID 02: one ISO-TP message on CAN, numbered lines on K-Line (B7). */
    override suspend fun readVin(): String? {
        val messages = query("0902", timeoutMs = 5_000) ?: return null
        return VehicleInfoDecoder.vin(messages, isCan)
    }

    override suspend fun batteryVoltage(): Double? = try {
        (ElmResponseParser.parse(link.send("ATRV", timeoutMs = 1_500), "ATRV") as? ElmResult.Data)
            ?.lines?.lastOrNull()?.removeSuffix("V")?.toDoubleOrNull()
    } catch (e: ElmTimeoutException) {
        null
    }

    override suspend fun ecuResponds(): Boolean = try {
        val result = ElmResponseParser.parse(link.send("0100", timeoutMs = SEARCH_TIMEOUT_MS, idle = false), "0100")
        result is ElmResult.Data && elmMessages(result.lines).any { replyMatches("0100", it) }
    } catch (e: ElmTimeoutException) {
        false
    }

    /**
     * K-Line keepalive (A10): clones do not keep the bus awake themselves, so after [idleMs] without traffic a
     * request is sent. KWP gets tester present (3E) when the ECU accepts it, otherwise 0100. Never on CAN.
     */
    override suspend fun keepAlive(idleMs: Long): Boolean {
        val p = protocol ?: return false
        if (!ObdProtocols.isKLine(p)) return false
        if (clock() - link.lastActivityMs < idleMs) return false
        val known = keepAliveCommand
        if (known == null && ObdProtocols.isKwp(p)) {
            // The first keepalive on KWP is the tester-present probe itself.
            val accepted = try {
                (ElmResponseParser.parse(link.send("3E", idle = false), "3E") as? ElmResult.Data)
                    ?.let { elmMessages(it.lines) }?.any { it.toUnsigned().firstOrNull() == 0x7E } == true
            } catch (e: ObdException) {
                false
            }
            keepAliveCommand = if (accepted) "3E" else "0100"
            return true
        }
        val command = known ?: "0100".also { keepAliveCommand = it }
        try {
            link.send(command, idle = false)
        } catch (e: ObdException) {
            link.note("keepalive '$command': ${e.error.technicalMessage}")
        }
        return true
    }

    /**
     * Send + parse. "STOPPED" (the adapter was interrupted) is retried once. After [initialize], an error with a
     * recovery action (A2) runs that recovery and retries the command once, within [recoveryBudget].
     */
    private suspend fun exchange(command: String, timeoutMs: Long = 2_000, idle: Boolean = true): ElmResult {
        var result = ElmResponseParser.parse(link.send(command, timeoutMs, idle), command)
        if (result is ElmResult.Error && result.message.contains("STOPPED")) {
            link.note("'$command': STOPPED, retrying once")
            result = ElmResponseParser.parse(link.send(command, timeoutMs, idle), command)
        }
        val known = protocol ?: return result
        val action = when (result) {
            is ElmResult.Error -> RecoveryAction.forError(result.message)
            ElmResult.BusInitFailed -> RecoveryAction.PROTOCOL_CLOSE
            else -> RecoveryAction.NONE
        }
        if (action == RecoveryAction.NONE || recovering || !recoveryBudget.tryAcquire()) return result
        link.note("'$command': $result -> recovery $action")
        recover(action, known)
        return ElmResponseParser.parse(link.send(command, timeoutMs, idle), command)
    }

    private suspend fun recover(action: RecoveryAction, known: Char) {
        recovering = true
        try {
            when (action) {
                RecoveryAction.PROTOCOL_CLOSE -> {
                    quietly("ATPC")
                    quietly("ATSP$known")
                }
                RecoveryAction.WARM_START -> {
                    quietly("ATWS", timeoutMs = 3_000)
                    delay(RECOVERY_SETTLE_MS)
                    link.drain()
                    restoreSettings(known)
                }
                RecoveryAction.FULL_REINIT -> {
                    quietly("ATZ", timeoutMs = 5_000)
                    delay(RESET_SETTLE_MS)
                    link.drain()
                    restoreSettings(known)
                }
                RecoveryAction.NONE -> Unit
            }
        } finally {
            recovering = false
        }
    }

    /** A recovery command whose reply does not matter; a timeout is tolerated, a lost link is not. */
    private suspend fun quietly(command: String, timeoutMs: Long = 2_000) {
        try {
            link.send(command, timeoutMs, idle = false)
        } catch (e: ObdException) {
            link.note("recovery '$command': ${e.error.technicalMessage}")
        }
    }

    private suspend fun restoreSettings(known: Char) {
        for (command in SETTINGS) quietly(command)
        quietly("ATSP$known")
        adaptiveTiming(ObdProtocols.isCan(known))
    }

    /**
     * null = NO DATA (PID not supported or the ECU stayed silent). Adapter error → ObdException.
     * Messages that do not answer [matchAs] (a late reply to an earlier request) are dropped; if only such messages
     * came, the own reply may still be on its way, so one more reply is awaited.
     */
    private suspend fun query(command: String, timeoutMs: Long = 2_000, matchAs: String = command): List<ByteArray>? {
        val messages = messagesOf(command, exchange(command, timeoutMs)) ?: return null
        val own = messages.filter { replyMatches(matchAs, it) }
        if (own.isNotEmpty() || messages.isEmpty()) return own
        link.note("'$command': dropped a reply to another request")
        val next = link.awaitNextReply(command, timeoutMs) ?: return null
        return messagesOf(command, ElmResponseParser.parse(next, command))
            ?.filter { replyMatches(matchAs, it) }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun messagesOf(command: String, result: ElmResult): List<ByteArray>? = when (result) {
        is ElmResult.Data -> elmMessages(result.lines)
        ElmResult.NoData -> null
        ElmResult.BusInitFailed -> throw ObdException(ObdError.BusInitFailed)
        is ElmResult.Error -> throw ObdException(ObdError.CommandFailed(command, result.message))
    }

    companion object {
        private val SETTINGS = listOf("ATE0", "ATL0", "ATS0", "ATH0")
        private const val RESET_SETTLE_MS = 1_000L
        private const val RECOVERY_SETTLE_MS = 300L
        private const val SEARCH_TIMEOUT_MS = 15_000L
        private const val TRY_PROTOCOL_TIMEOUT_MS = 10_000L
        private const val OPTIONAL_TIMEOUT_MS = 1_000L
        private const val FALLBACK_ORDER = "681792345A"
        internal const val MAX_MULTI_PIDS = 6
        private const val MULTI_PID_MISS_LIMIT = 3

        /** Below this the adapter is not powered by a car battery. */
        internal const val NO_POWER_V = 6.0

        /** Below this (no alternator charge) a silent ECU most likely means the ignition is off. */
        internal const val IGNITION_ON_V = 13.0

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
