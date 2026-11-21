package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.DtcKind

/**
 * R-D: a request channel with headers on (ATH1), handed out by [ObdSession.withHeaders] for the duration of one
 * module scan. Every request passes the [EcuSafetyPolicy] before anything (also ATSH/ATCRA) is written.
 */
interface HeaderedChannel {
    val protocol: Char
    val format: HeaderFormat

    /** The scope's safety policy (R-E: extended reads check all their requests with it before the first step). */
    val policy: EcuSafetyPolicy get() = EcuSafetyPolicy()

    /** The functional OBD request (default header); replies grouped per ECU. null = NO DATA. */
    suspend fun functional(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): HeaderedReply?

    /**
     * A physical request to one module (CAN only: ATSH tx + ATCRA rx); the messages of that module, null = NO DATA.
     * Throws [ObdException] with [ObdError.ModuleScanUnsupported] when the adapter does not know ATCRA, and with
     * [ObdError.RequestDenied] when the policy refuses (nothing written then).
     */
    suspend fun physical(target: EcuTarget, command: String, timeoutMs: Long = PROBE_TIMEOUT_MS): List<ByteArray>?

    /**
     * R-E: one adapter setting of an extended read ([ExtendedSettings.ALLOWED] only; ATSH/ATCRA also become the
     * channel's current header/filter). A setting equal to the current one is not sent again. false = the adapter
     * answered '?'. Throws [ObdException] with [ObdError.ModuleScanUnsupported] for commands outside the list and for
     * an ATSP naming another protocol. Every changed setting is put back when the scope ends.
     */
    suspend fun setting(command: String): Boolean = throw ObdException(ObdError.ModuleScanUnsupported("settings"))

    /**
     * R-E: a request under the header and filter set by [setting] (no implicit ATSH/ATCRA); checked against [target]
     * by the scope's policy before it is written. The messages of [target]'s reply ID (any ECU when the filter is
     * off), null = NO DATA. A 7F xx 78 «response pending» is waited for like in [physical].
     */
    suspend fun request(target: EcuTarget, command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<ByteArray>? =
        throw ObdException(ObdError.ModuleScanUnsupported("requests"))

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L

        /**
         * Upper bound for one physical probe. The ELM327 itself prints NO DATA after its ATST timeout (default
         * 0x32 × 4 ms = 200 ms, ELM327DSJ «ST hh»), so a missing module costs ~0.2 s, not this bound. How to
         * calibrate on a real car: read the traffic log durations of the 3E00 probes and lower it if all answer fast.
         */
        const val PROBE_TIMEOUT_MS = 1_000L
    }
}

/** A module the scan found. [emission] = answered the functional OBD request (an emission ECU: mode 03 applies). */
data class FoundModule(val address: EcuAddress, val kind: ModuleKind, val emission: Boolean, val supportedPids: Int = 0)

/** Which read produced the codes. */
enum class ModuleDtcService { MODE_03, UDS_19_02, KWP_18_00, KWP_18_02 }

/** Status bits of one module code (UDS ISO 14229-1 Annex D; KWP2000 storage state, DaimlerChrysler KWP2000 2002). */
enum class ModuleDtcStatus { TEST_FAILED, PENDING, CONFIRMED, WARNING_LAMP }

/** One code of a module: [code] in the J2012 P/C/B/U form, [ftb] = UDS failure type byte (null on KWP/mode 03). */
data class ModuleDtc(val code: String, val status: Set<ModuleDtcStatus>, val ftb: Int? = null)

/** What one module said about its codes. */
sealed interface ModuleDtcRead {
    data class Codes(val service: ModuleDtcService, val dtcs: List<ModuleDtc>) : ModuleDtcRead

    /** Negative replies 11/12/31 to every variant (and no emission fallback). */
    data object NotSupported : ModuleDtcRead

    /** The module stayed silent (NO DATA) to the code reads. */
    data object NoReply : ModuleDtcRead

    /** Another negative reply code (e.g. 22 conditions not correct, 33 security). */
    data class Refused(val nrc: Int) : ModuleDtcRead
}

data class ModuleReport(val module: FoundModule, val read: ModuleDtcRead)

/** Result of one scan. [limitedByAdapter] = the adapter did not accept ATCRA, so only functional responders are listed. */
data class ModuleScanResult(
    val protocol: Char,
    val modules: List<ModuleReport>,
    val limitedByAdapter: Boolean = false,
    /** Addresses the policy never queried (airbags / conflicting sources). */
    val skipped: List<String> = emptyList(),
)

sealed interface ScanProgress {
    data object Discovering : ScanProgress
    data class Probing(val address: EcuAddress, val index: Int, val total: Int) : ScanProgress
    data class Reading(val module: FoundModule, val index: Int, val total: Int) : ScanProgress
}

/** A known module address pair and the harmless read that proves it is there. */
data class KnownModule(val address: EcuAddress, val kind: ModuleKind, val probe: String = "3E00")

/**
 * Kia/Hyundai CAN 11-bit module pairs (reply = request + 8), round 2 research C_MULTI_ECU: OBDb Kia-Sorento
 * signalset `ecu` block (7E0 engine, 7E1 transmission, 7E2 hybrid, 7E3 motor, 7E4 battery, 7A0 body/TPMS, 7C6
 * cluster, 7D4 steering), carcoding.app + the gist (7D1/7D9 ESC/ABS), carcoding.app + the Kia e-Niro CAN sheet
 * (7D2/7DA SRS). Addresses vary by year and platform: a missing module only means «not at this address».
 * How to calibrate on a real car: compare with the traffic log of a scan on that model and extend the table only from
 * such logs.
 */
object KnownModules {
    val KIA_HYUNDAI_CAN: List<KnownModule> = listOf(
        KnownModule(EcuAddress.can11(0x7E1), ModuleKind.TRANSMISSION),
        KnownModule(EcuAddress.can11(0x7D1), ModuleKind.ABS_ESC),
        KnownModule(EcuAddress.can11(0x7E2), ModuleKind.HYBRID),
        KnownModule(EcuAddress.can11(0x7E3), ModuleKind.MOTOR),
        KnownModule(EcuAddress.can11(0x7E4), ModuleKind.BATTERY),
        KnownModule(EcuAddress.can11(0x7A0), ModuleKind.BODY),
        KnownModule(EcuAddress.can11(0x7C6), ModuleKind.CLUSTER),
        // Listed so the scan reports them as skipped; the policy blocks both before anything is written.
        KnownModule(EcuAddress.can11(0x7D4), ModuleKind.STEERING),
        KnownModule(EcuAddress.can11(0x7D2), ModuleKind.SRS),
    )

    /**
     * Kind of a module from its reply address. CAN 11 bit: the known pairs, 7E8 engine, 7E9 transmission (ISO 15765-4
     * convention used by J1979 tools). K-Line / CAN 29 bit source byte: SAE J2178-1 physical address ranges (10-17
     * engine, 18-1F transmission, 28-2F brakes, 58-5F restraints; the ELM327 datasheet example shows 10 = engine,
     * 18 = transmission).
     */
    fun kindOf(address: EcuAddress): ModuleKind {
        val f = address.format ?: return ModuleKind.UNKNOWN
        if (f == HeaderFormat.CAN_11) {
            if (address.rx == "7E8") return ModuleKind.ENGINE
            if (address.rx == "7E9") return ModuleKind.TRANSMISSION
            return KIA_HYUNDAI_CAN.firstOrNull { it.address.rx == address.rx }?.kind ?: ModuleKind.UNKNOWN
        }
        val src = (if (f == HeaderFormat.CAN_29) address.rx.takeLast(2) else address.rx).toIntOrNull(16) ?: return ModuleKind.UNKNOWN
        return when (src) {
            in 0x10..0x17 -> ModuleKind.ENGINE
            in 0x18..0x1F -> ModuleKind.TRANSMISSION
            in 0x28..0x2F -> ModuleKind.ABS_ESC
            in EcuSafetyPolicy.KLINE_SRS_RANGE -> ModuleKind.SRS
            else -> ModuleKind.UNKNOWN
        }
    }
}

/** Negative response 7F <sid> <nrc> of [sid] in [messages], or null. */
internal fun negativeCode(messages: List<ByteArray>, sid: Int): Int? = messages
    .map { it.toUnsigned() }.firstOrNull { it.size >= 3 && it[0] == 0x7F && it[1] == sid }?.get(2)

/** Decoders of module DTC replies (pure). */
object ModuleDtcDecoder {
    /**
     * UDS 19 02 status mask: testFailed (bit 0) | pendingDTC (bit 2) | confirmedDTC (bit 3) = 0x0D, ISO 14229-1:2013
     * Annex D.2 (DTCStatusMask). The ECU ANDs it with its availability mask; records without any of these bits are
     * dropped too (some ECUs ignore the mask). How to calibrate on a real car: compare with the codes a dealer tool
     * shows; widen to 0x2D (+testFailedSinceLastClear) only if history codes are wanted.
     */
    const val UDS_MASK = 0x0D

    /** 59 02 <availability mask> then 4-byte records (DTC high, middle, failure type, status). null = not a 59 02 reply. */
    fun uds(message: ByteArray): List<ModuleDtc>? {
        val u = message.toUnsigned()
        if (u.size < 3 || u[0] != 0x59 || u[1] != 0x02) return null
        return u.drop(3).chunked(4).filter { it.size == 4 && (it[0] != 0 || it[1] != 0 || it[2] != 0) }.mapNotNull { r ->
            val s = r[3]
            if (s and UDS_MASK == 0) return@mapNotNull null
            val status = buildSet {
                if (s and 0x01 != 0) add(ModuleDtcStatus.TEST_FAILED)
                if (s and 0x04 != 0) add(ModuleDtcStatus.PENDING)
                if (s and 0x08 != 0) add(ModuleDtcStatus.CONFIRMED)
                if (s and 0x80 != 0) add(ModuleDtcStatus.WARNING_LAMP)
            }
            ModuleDtc(DtcDecoder.code(r[0], r[1]), status, ftb = r[2])
        }
    }

    /**
     * KWP2000 58 N then N × (high, low, status). Status (DaimlerChrysler KWP2000 2002, readDTCByStatus): bits 6-5 =
     * storage state 11 present at the time of request, 01 stored but not present, 10 maturing / intermittent, 00 none;
     * bit 7 = warning lamp. null = not a 58 reply.
     */
    fun kwp(message: ByteArray): List<ModuleDtc>? {
        val u = message.toUnsigned()
        if (u.size < 2 || u[0] != 0x58) return null
        val n = u[1]
        return u.drop(2).chunked(3).take(n).filter { it.size == 3 && (it[0] != 0 || it[1] != 0) }.mapNotNull { r ->
            val s = r[2]
            val status = buildSet {
                when ((s shr 5) and 0x03) {
                    0x3 -> { add(ModuleDtcStatus.TEST_FAILED); add(ModuleDtcStatus.CONFIRMED) }
                    0x1 -> add(ModuleDtcStatus.CONFIRMED)
                    0x2 -> add(ModuleDtcStatus.PENDING)
                }
                if (s and 0x80 != 0) add(ModuleDtcStatus.WARNING_LAMP)
            }
            if (status.isEmpty()) null else ModuleDtc(DtcDecoder.code(r[0], r[1]), status)
        }
    }
}

/**
 * R-D: finds the vehicle's modules and reads their codes, only through a [HeaderedChannel] (policy-checked).
 * 1. Functional 0100 with headers: every responder is an emission ECU; the engine is 7E8/10 or the one with the most
 *    supported PIDs.
 * 2. CAN 11 bit only: the [knownModules] are probed with a harmless 3E00 (a positive 7E or any 7F 3E reply proves the
 *    module); blocked addresses are skipped (listed in [ModuleScanResult.skipped]). K-Line: no blind physical probing.
 * 3. Codes: CAN = UDS 19 02 (mode 03 + 07 for emission ECUs when 19 is not supported); KWP = 18 00 FF 00, then
 *    18 02 FF 00 on 7F 18 12, then mode 03 for emission ECUs; ISO 9141 = mode 03 + 07. K-Line requests stay
 *    functional (C2 33 F1 on KWP): a header change can cost a new bus init there, replies are split by source byte.
 * Never clears anything: there is no clear on this path (the policy denies 14/04 outside the safe-clear flow).
 */
class ModuleScanner(private val knownModules: List<KnownModule> = KnownModules.KIA_HYUNDAI_CAN) {

    suspend fun scan(session: ObdSession, policy: EcuSafetyPolicy = EcuSafetyPolicy(), onProgress: (ScanProgress) -> Unit = {}): ModuleScanResult =
        session.withHeaders(policy) { channel -> run(channel, policy, onProgress) }

    internal suspend fun run(ch: HeaderedChannel, policy: EcuSafetyPolicy, onProgress: (ScanProgress) -> Unit): ModuleScanResult {
        functionalCache.clear()
        onProgress(ScanProgress.Discovering)
        val discovery = ch.functional("0100", DISCOVERY_TIMEOUT_MS)
        val found = mutableListOf<FoundModule>()
        discovery?.byEcu?.forEach { (rx, messages) ->
            val mask = messages.map { it.toUnsigned() }.firstOrNull { it.size >= 6 && it[0] == 0x41 && it[1] == 0x00 } ?: return@forEach
            val address = EcuAddress.fromReply(ch.protocol, rx)
            val kind = KnownModules.kindOf(address)
            if (kind == ModuleKind.SRS) return@forEach // never listed nor asked again
            found += FoundModule(address, kind, emission = true, supportedPids = SupportedPids.decode(0, mask.subList(2, 6).toIntArray()).size)
        }
        markEngine(found)

        val skipped = mutableListOf<String>()
        var limited = false
        if (ch.format == HeaderFormat.CAN_11) {
            val candidates = knownModules.filter { k -> found.none { it.address.rx == k.address.rx } }
            for ((i, k) in candidates.withIndex()) {
                val target = EcuTarget(k.address, emission = false, kind = k.kind)
                if (policy.check(target, k.probe) is PolicyDecision.Denied) {
                    skipped += k.address.label
                    continue
                }
                onProgress(ScanProgress.Probing(k.address, i + 1, candidates.size))
                val reply = try {
                    ch.physical(target, k.probe)
                } catch (e: ObdException) {
                    if (e.error is ObdError.ModuleScanUnsupported) {
                        limited = true
                        break
                    }
                    null
                }
                val sid = k.probe.substring(0, 2).toInt(16)
                val present = reply?.any { m -> m.toUnsigned().let { it.isNotEmpty() && (it[0] == sid + 0x40 || (it.size >= 2 && it[0] == 0x7F && it[1] == sid)) } } == true
                if (present) found += FoundModule(k.address, k.kind, emission = false)
            }
        }

        val reports = found.mapIndexed { i, m ->
            onProgress(ScanProgress.Reading(m, i + 1, found.size))
            ModuleReport(m, readCodes(ch, m))
        }
        return ModuleScanResult(ch.protocol, reports, limited, skipped)
    }

    private fun markEngine(found: MutableList<FoundModule>) {
        if (found.isEmpty()) return
        val engine = found.firstOrNull { it.address.rx == "7E8" || it.address.rx == "10" || it.address.rx.endsWith("F110") }
            ?: found.maxBy { it.supportedPids }
        found.replaceAll { m ->
            when {
                m === engine -> m.copy(kind = ModuleKind.ENGINE)
                m.kind == ModuleKind.ENGINE -> m.copy(kind = ModuleKind.UNKNOWN) // a second «engine» range responder
                else -> m
            }
        }
    }

    // K-Line functional replies are shared by all modules of one scan.
    private val functionalCache = HashMap<String, HeaderedReply?>()

    private suspend fun functionalOnce(ch: HeaderedChannel, command: String): HeaderedReply? =
        if (functionalCache.containsKey(command)) functionalCache[command] else ch.functional(command).also { functionalCache[command] = it }

    private suspend fun readCodes(ch: HeaderedChannel, m: FoundModule): ModuleDtcRead = try {
        when (ch.format) {
            HeaderFormat.CAN_11, HeaderFormat.CAN_29 -> readCan(ch, m)
            HeaderFormat.KWP -> readKwp(ch, m)
            HeaderFormat.ISO_9141 -> readMode03(m, functionalOnce(ch, "03")?.byEcu?.get(m.address.rx), functionalOnce(ch, "07")?.byEcu?.get(m.address.rx), isCan = false)
        }
    } catch (e: ObdException) {
        if (e.error is ObdError.RequestDenied) throw e
        ModuleDtcRead.NoReply
    }

    private suspend fun readCan(ch: HeaderedChannel, m: FoundModule): ModuleDtcRead {
        val target = EcuTarget(m.address, m.emission, m.kind)
        val reply = ch.physical(target, "1902%02X".format(ModuleDtcDecoder.UDS_MASK), CODES_TIMEOUT_MS)
        if (reply != null) {
            reply.firstNotNullOfOrNull { ModuleDtcDecoder.uds(it) }?.let { return ModuleDtcRead.Codes(ModuleDtcService.UDS_19_02, it) }
        }
        val nrc = reply?.let { negativeCode(it, 0x19) }
        if (m.emission && (reply == null || nrc in NOT_SUPPORTED)) {
            return readMode03(m, ch.physical(target, "03", CODES_TIMEOUT_MS), ch.physical(target, "07", CODES_TIMEOUT_MS), isCan = true)
        }
        return when {
            nrc == null -> ModuleDtcRead.NoReply
            nrc in NOT_SUPPORTED -> ModuleDtcRead.NotSupported
            else -> ModuleDtcRead.Refused(nrc)
        }
    }

    private suspend fun readKwp(ch: HeaderedChannel, m: FoundModule): ModuleDtcRead {
        var last: Int? = null
        for ((command, service) in listOf("1800FF00" to ModuleDtcService.KWP_18_00, "1802FF00" to ModuleDtcService.KWP_18_02)) {
            val messages = functionalOnce(ch, command)?.byEcu?.get(m.address.rx) ?: run { last = null; break }
            messages.firstNotNullOfOrNull { ModuleDtcDecoder.kwp(it) }?.let { return ModuleDtcRead.Codes(service, it) }
            last = negativeCode(messages, 0x18)
            if (last != 0x12) break // only «sub-function not supported» tries the other variant
        }
        if (m.emission && (last == null || last in NOT_SUPPORTED)) {
            return readMode03(m, functionalOnce(ch, "03")?.byEcu?.get(m.address.rx), functionalOnce(ch, "07")?.byEcu?.get(m.address.rx), isCan = false)
        }
        return when (val nrc = last) {
            null -> ModuleDtcRead.NoReply
            in NOT_SUPPORTED -> ModuleDtcRead.NotSupported
            else -> ModuleDtcRead.Refused(nrc)
        }
    }

    private fun readMode03(m: FoundModule, stored: List<ByteArray>?, pending: List<ByteArray>?, isCan: Boolean): ModuleDtcRead {
        if (stored == null && pending == null) return ModuleDtcRead.NoReply
        val codes = LinkedHashMap<String, MutableSet<ModuleDtcStatus>>()
        stored?.let { DtcDecoder.parse(it, 0x43, isCan, DtcKind.STORED) }
            ?.forEach { codes.getOrPut(it.code) { mutableSetOf() } += ModuleDtcStatus.CONFIRMED }
        pending?.let { DtcDecoder.parse(it, 0x47, isCan, DtcKind.PENDING) }
            ?.forEach { codes.getOrPut(it.code) { mutableSetOf() } += ModuleDtcStatus.PENDING }
        return ModuleDtcRead.Codes(ModuleDtcService.MODE_03, codes.map { (code, status) -> ModuleDtc(code, status) })
    }

    companion object {
        /** serviceNotSupported 11, subFunctionNotSupported 12, requestOutOfRange 31 (ISO 14229-1 Annex A.1). */
        val NOT_SUPPORTED = setOf(0x11, 0x12, 0x31)

        /** Functional 0100 may need a K-Line bus wake-up first (2-3 s, ISO 14230-2 fast init + P2 timing). */
        const val DISCOVERY_TIMEOUT_MS = 5_000L
        const val CODES_TIMEOUT_MS = 2_000L
    }
}
