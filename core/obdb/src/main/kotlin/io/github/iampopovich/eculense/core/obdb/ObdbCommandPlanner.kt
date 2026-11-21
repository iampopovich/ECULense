package io.github.iampopovich.eculense.core.obdb

import io.github.iampopovich.eculense.core.obd.EcuAddress
import io.github.iampopovich.eculense.core.obd.EcuSafetyPolicy
import io.github.iampopovich.eculense.core.obd.EcuTarget
import io.github.iampopovich.eculense.core.obd.ElmStep
import io.github.iampopovich.eculense.core.obd.ExtendedCommand
import io.github.iampopovich.eculense.core.obd.HeaderFormat
import io.github.iampopovich.eculense.core.obd.ModuleKind

/** Why a signalset command is not read (shown as a count, never sent). */
enum class PlanRefusal {
    /** Only services 01, 21 and 22 are read (OBDb `cmd` keys); anything else is refused. */
    SERVICE_NOT_SUPPORTED,

    /** K-Line rate the ELM327 cannot set (ATIB knows only 10400, 9600 and 4800 baud: 12500 and 15625 are refused). */
    BAUD_NOT_SUPPORTED,

    /** The command's protocol family differs from the connected one (the scope never switches protocols). */
    PROTOCOL_MISMATCH,

    /** No decodable signal. */
    NO_SIGNALS,
}

/** Something the planner left out but still reads the command. */
enum class PlanWarning {
    /** `init` (fast-init lo/hi ms): the ELM327 has no command for it, the adapter's own 25/25 ms init applies. */
    INIT_TIMING_IGNORED,
}

data class PlannedCommand(val source: SignalCommand, val command: ExtendedCommand, val periodMs: Long, val warnings: Set<PlanWarning> = emptySet())

data class RefusedCommand(val source: SignalCommand, val reason: PlanRefusal)

/** All commands to one header; [sessions] = OBDb din/dout sessions the group's policy allows (nothing else). */
data class PlannedGroup(val header: String, val commands: List<PlannedCommand>, val sessions: Set<Int>) {
    val policy: EcuSafetyPolicy get() = EcuSafetyPolicy(sessions)
    val periodMs: Long get() = commands.minOf { it.periodMs }
}

data class ObdbPlan(val groups: List<PlannedGroup>, val refused: List<RefusedCommand>) {
    val commandCount: Int get() = groups.sumOf { it.commands.size }
}

/**
 * R-E: turns OBDb signalset v3 commands into ELM327 steps (round 2 research D; ELM327DSJ for every AT command):
 * - proto → the protocol family (15765-4-11bit → 6, 15765-4-29bit → 7, 9141-2 → 3, 14230 → 5 fast init) and an
 *   `ATSP` naming the connected protocol; another family is refused (no protocol switch inside live polling);
 * - baud → ATIB10 / ATIB96 / ATIB48, other rates refused; init → skipped with [PlanWarning.INIT_TIMING_IGNORED];
 * - pri → ATCP, hdr → ATSH (29 bit: hdr + tester byte), eax → ATCEA, tst → ATTA, tmo → ATST (optional), rax →
 *   ATCRA on CAN (11 bit default hdr + 8, ISO 15765-4) / ATSR on K-Line, fcm1 → ATFCSH hdr, ATFCSD 30 00 00, ATFCSM1;
 * - din/dout → «10 din» before and «10 dout» after the read, allowed only through the group's [EcuSafetyPolicy]
 *   session list (the policy denies any other session);
 * - cmd services 01, 21, 22 only.
 * Commands are grouped by header in the signalset order, so the header changes once per group.
 * Pure: no I/O, no Android.
 */
object ObdbCommandPlanner {
    /** Default read period when a command has no `freq`, ms (OBDb freq is in seconds). */
    const val DEFAULT_PERIOD_MS = 1_000L

    private val SERVICES = setOf("01", "21", "22")

    fun plan(commands: List<SignalCommand>, connectedProtocol: Char, ecus: List<Ecu> = emptyList()): ObdbPlan {
        val planned = mutableListOf<PlannedCommand>()
        val refused = mutableListOf<RefusedCommand>()
        for (c in commands) {
            when (val r = planOne(c, connectedProtocol, ecus)) {
                is PlannedCommand -> planned += r
                is RefusedCommand -> refused += r
            }
        }
        val groups = planned.groupBy { it.source.hdr.uppercase() }.map { (hdr, list) ->
            PlannedGroup(hdr, list, list.flatMap { listOfNotNull(it.source.din, it.source.dout) }.mapNotNull { it.toIntOrNull(16) }.toSet())
        }
        return ObdbPlan(groups, refused)
    }

    /** OBDb `proto` name → ELM protocol number; null = unknown name. */
    fun protocolOf(name: String): Char? = when (name) {
        "15765-4-11bit" -> '6'
        "15765-4-29bit" -> '7'
        "9141-2" -> '3'
        "14230" -> '5'
        else -> null
    }

    /** OBDb `baud` → ATIB argument; null = the ELM327 cannot set it. */
    fun baudOf(baud: Int): String? = when (baud) {
        10400 -> "10"
        9600 -> "96"
        4800 -> "48"
        else -> null
    }

    fun kindOf(type: String?): ModuleKind = when (type) {
        "engine" -> ModuleKind.ENGINE
        "transmission" -> ModuleKind.TRANSMISSION
        "abs" -> ModuleKind.ABS_ESC
        "battery" -> ModuleKind.BATTERY
        "motor" -> ModuleKind.MOTOR
        "hybridControl", "vehicleControl" -> ModuleKind.HYBRID
        "steering" -> ModuleKind.STEERING
        "cluster" -> ModuleKind.CLUSTER
        "bodyControl" -> ModuleKind.BODY
        "airbag", "srs", "restraints" -> ModuleKind.SRS
        else -> ModuleKind.UNKNOWN
    }

    private fun family(p: Char): HeaderFormat? = HeaderFormat.of(p)

    private fun planOne(c: SignalCommand, connected: Char, ecus: List<Ecu>): Any {
        if (c.service !in SERVICES) return RefusedCommand(c, PlanRefusal.SERVICE_NOT_SUPPORTED)
        if (c.signals.none { it.fmt != null }) return RefusedCommand(c, PlanRefusal.NO_SIGNALS)
        val connectedFormat = family(connected) ?: return RefusedCommand(c, PlanRefusal.PROTOCOL_MISMATCH)
        // Without proto OBDb means CAN 11 bit (every Kia/Hyundai signalset).
        val wanted = c.protocol?.let { protocolOf(it) ?: return RefusedCommand(c, PlanRefusal.PROTOCOL_MISMATCH) } ?: '6'
        val format = family(wanted)
        if (format != connectedFormat) return RefusedCommand(c, PlanRefusal.PROTOCOL_MISMATCH)
        val ib = c.baud?.let { baudOf(it) ?: return RefusedCommand(c, PlanRefusal.BAUD_NOT_SUPPORTED) }
        val hdr = c.hdr.uppercase()
        val rax = c.rax?.uppercase()
        val tester = c.tst?.uppercase() ?: "F1"
        val priority = c.pri?.uppercase() ?: "18"
        val address = when (format) {
            HeaderFormat.CAN_11 -> EcuAddress(connected, hdr, rax ?: "%03X".format(hdr.toInt(16) + 8))
            HeaderFormat.CAN_29 -> {
                val tx = priority + hdr + tester
                val rx = when (rax?.length) {
                    8 -> rax
                    6 -> priority + rax
                    2 -> priority + "DA" + tester + rax
                    else -> priority + "DA" + tester + hdr.takeLast(2)
                }
                EcuAddress(connected, tx, rx)
            }
            else -> EcuAddress(connected, hdr, rax ?: hdr)
        }
        val ecuType = ecus.firstOrNull { it.hdr.equals(hdr, ignoreCase = true) }?.type
        val target = EcuTarget(address, emission = false, kind = kindOf(ecuType))
        val warnings = mutableSetOf<PlanWarning>()
        if (c.init != null) warnings += PlanWarning.INIT_TIMING_IGNORED
        val steps = buildList {
            if (c.protocol != null) add(ElmStep.Setting("ATSP$connected"))
            ib?.let { add(ElmStep.Setting("ATIB$it")) }
            if (format == HeaderFormat.CAN_29 || c.pri != null) add(ElmStep.Setting("ATCP$priority"))
            add(ElmStep.Setting(if (format == HeaderFormat.CAN_29) "ATSH$hdr$tester" else "ATSH$hdr"))
            c.eax?.let { add(ElmStep.Setting("ATCEA${it.uppercase()}")) }
            c.tst?.let { add(ElmStep.Setting("ATTA${it.uppercase()}")) }
            c.tmo?.let { add(ElmStep.Setting("ATST${it.uppercase()}", optional = true)) }
            if (format.isCan) {
                add(ElmStep.Setting("ATCRA${address.rx}"))
            } else if (rax != null) {
                add(ElmStep.Setting("ATSR$rax"))
            }
            if (c.fcm1 && format.isCan) {
                add(ElmStep.Setting("ATFCSH" + if (format == HeaderFormat.CAN_29) address.tx else hdr))
                add(ElmStep.Setting("ATFCSD300000"))
                add(ElmStep.Setting("ATFCSM1"))
            }
            c.din?.let { add(ElmStep.Request(target, "10" + it.uppercase(), collect = false)) }
            add(ElmStep.Request(target, c.service + c.pid.uppercase()))
            c.dout?.let { add(ElmStep.Request(target, "10" + it.uppercase(), collect = false)) }
        }
        val period = ((c.freq ?: 1.0) * 1_000).toLong().coerceAtLeast(DEFAULT_PERIOD_MS)
        return PlannedCommand(c, ExtendedCommand(c.id, target, steps), period, warnings)
    }
}
