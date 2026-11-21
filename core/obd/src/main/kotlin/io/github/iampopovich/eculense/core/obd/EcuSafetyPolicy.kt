package io.github.iampopovich.eculense.core.obd

/**
 * Kinds of vehicle modules the app names itself (labels in :core:obdtext). [SRS] (airbags) is never queried.
 */
enum class ModuleKind { ENGINE, TRANSMISSION, ABS_ESC, BODY, CLUSTER, HYBRID, MOTOR, BATTERY, STEERING, SRS, UNKNOWN }

/** Why the [EcuSafetyPolicy] refused a request (it was never written to the adapter). */
enum class DenyReason {
    /** The service is not on the read-only allow list (11 reset, 27 security, 2E write, 2F/31 actuate, 34-37, 3B...). */
    SERVICE_NOT_ALLOWED,

    /** 10 (diagnostic session) with a session the caller did not list explicitly (OBDb din/dout). */
    SESSION_NOT_LISTED,

    /** 14/04 (clear codes) to a module that is not an emission ECU, or outside the safe-clear flow. */
    CLEAR_NOT_ALLOWED,

    /** An address on the block list (7D2/7DA, 7D4/7DC). */
    BLOCKED_ADDRESS,

    /** A module identified as SRS / airbag. */
    SRS_MODULE,

    /** Not a hex OBD/UDS request. */
    MALFORMED,
}

/** Whom a request goes to: [address] null = the functional (broadcast) request; [emission] = an OBD emission ECU. */
data class EcuTarget(val address: EcuAddress?, val emission: Boolean, val kind: ModuleKind = ModuleKind.UNKNOWN) {
    companion object {
        /** The functional OBD request (7DF / 18DB33F1 / C2 33 F1 / 68 6A F1): only emission ECUs answer it. */
        val FUNCTIONAL = EcuTarget(null, emission = true)
    }
}

sealed interface PolicyDecision {
    data object Allowed : PolicyDecision
    data class Denied(val reason: DenyReason) : PolicyDecision
}

/** What the request is for: module scans only read; [SAFE_CLEAR] = the existing speed-gated clear of emission codes. */
enum class RequestPurpose { READ, SAFE_CLEAR }

/**
 * R-D safety: the one gate every multi-ECU request passes BEFORE anything is written to the transport. Only reading
 * services are allowed (round 2 research C_MULTI_ECU, sources ISO 14229-1 service table, ISO 14230-3, SAE J1979):
 * 01 02 03 07 09 0A (J1979), 18 (KWP readDTCByStatus), 19 (UDS readDTCInformation), 21/22 (read data by local /
 * common identifier), 1A (KWP readECUIdentification), 3E (tester present). 14/04 only to emission ECUs inside the
 * safe-clear flow. 10 only for sessions the caller lists (OBDb din/dout, R-E). Denied: 11 ECU reset, 27 security
 * access, 28 communication control, 2E/3B write, 2F/31 actuators and routines, 34-37 up/download, 85 DTC setting,
 * and anything unknown.
 *
 * Address block list: 7D2/7DA (SRS on Kia/Hyundai per carcoding.app and the Kia e-Niro CAN sheet) and 7D4/7DC (SRS in
 * one source, steering in OBDb Kia-Sorento: conflicting, so blocked until confirmed on a real car). Any module
 * classified as [ModuleKind.SRS] is blocked whatever its address (K-Line J2178 «restraints» range 58-5F included).
 * How to calibrate on a real car: never widen the lists from a guess; an address leaves the block list only when a
 * traffic log of that model shows what answers there.
 */
class EcuSafetyPolicy(private val allowedSessions: Set<Int> = emptySet()) {
    fun check(target: EcuTarget, command: String, purpose: RequestPurpose = RequestPurpose.READ): PolicyDecision {
        val c = command.replace(" ", "").uppercase()
        if (c.length < 2 || c.length % 2 != 0 || !c.all { it in HEX }) return PolicyDecision.Denied(DenyReason.MALFORMED)
        if (target.kind == ModuleKind.SRS) return PolicyDecision.Denied(DenyReason.SRS_MODULE)
        target.address?.let { a ->
            if (a.tx.uppercase() in BLOCKED || a.rx.uppercase() in BLOCKED) return PolicyDecision.Denied(DenyReason.BLOCKED_ADDRESS)
            if (isSrsAddress(a)) return PolicyDecision.Denied(DenyReason.SRS_MODULE)
        }
        val sid = c.substring(0, 2).toInt(16)
        return when (sid) {
            in READ_SERVICES -> PolicyDecision.Allowed
            0x04, 0x14 ->
                if (purpose == RequestPurpose.SAFE_CLEAR && target.emission) PolicyDecision.Allowed else PolicyDecision.Denied(DenyReason.CLEAR_NOT_ALLOWED)
            0x10 -> {
                val session = c.drop(2).take(2).toIntOrNull(16)
                if (session != null && c.length == 4 && session in allowedSessions) PolicyDecision.Allowed else PolicyDecision.Denied(DenyReason.SESSION_NOT_LISTED)
            }
            else -> PolicyDecision.Denied(DenyReason.SERVICE_NOT_ALLOWED)
        }
    }

    /** Convenience: throws [ObdException] with [ObdError.RequestDenied] when [check] refuses. */
    fun require(target: EcuTarget, command: String, purpose: RequestPurpose = RequestPurpose.READ) {
        val d = check(target, command, purpose)
        if (d is PolicyDecision.Denied) throw ObdException(ObdError.RequestDenied(command, target.address?.label, d.reason.name))
    }

    companion object {
        private const val HEX = "0123456789ABCDEF"
        val READ_SERVICES: Set<Int> = setOf(0x01, 0x02, 0x03, 0x07, 0x09, 0x0A, 0x18, 0x19, 0x21, 0x22, 0x1A, 0x3E)

        /** Explicitly denied services (also denied as «unknown»; listed for the tests and the docs). */
        val DENIED_SERVICES: Set<Int> = setOf(0x11, 0x27, 0x28, 0x2E, 0x2F, 0x31, 0x34, 0x35, 0x36, 0x37, 0x3B, 0x85)

        /** Request and reply IDs that are never addressed (CAN 11 bit). */
        val BLOCKED: Set<String> = setOf("7D2", "7DA", "7D4", "7DC")

        /** SAE J2178-1 physical address range of restraint (airbag) controllers on K-Line / J1850: 0x58-0x5F. */
        val KLINE_SRS_RANGE = 0x58..0x5F

        fun isSrsAddress(a: EcuAddress): Boolean {
            val f = a.format ?: return false
            if (f.isCan) return false
            val src = a.rx.toIntOrNull(16) ?: return false
            return src in KLINE_SRS_RANGE
        }
    }
}
