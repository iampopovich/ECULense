package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.SensorReading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.yield
import java.io.IOException

/** Polling speed (A7, for the UI later): median cycle time and median time per PID over the last cycles. */
data class PollStats(val cycles: Int, val cycleMsP50: Long, val pidMsP50: Map<Int, Long>)

/** Every PID of [noDataPids] cycles in a row gave nothing: the ECU (or the link) stopped answering. */
internal class EcuStalledException(val noDataPids: Set<Int>) : Exception("no PID answered in several cycles")

/**
 * Endless polling cycles. A failure of one PID does not stop the others; IOException is rethrown.
 * - C2: each cycle reads only the PIDs due by their [PollTier] ([PollScheduler]); on CAN the session batches them
 *   into multi-PID requests. [background] doubles the MEDIUM/SLOW periods. The adapter voltage (ATRV) is read every
 *   [voltageEveryMs] and emitted as [PollTiers.ADAPTER_VOLTAGE_ID].
 * - A9: a PID without a value [disableAfter] reads in a row (while others answer) goes to [disabled] until reconnect.
 * - A3/A4: [stallAfter] cycles in a row where no PID answered end the flow with [EcuStalledException]; the connection
 *   manager then checks the adapter (ATRV) and the ECU.
 */
internal class LivePoller(
    private val session: ObdSession,
    private val clock: () -> Long = System::currentTimeMillis,
    private val disabled: MutableStateFlow<Set<Int>> = MutableStateFlow(emptySet()),
    private val stats: MutableStateFlow<PollStats?> = MutableStateFlow(null),
    private val pauseBetweenCyclesMs: Long = 0,
    private val disableAfter: Int = 3,
    private val stallAfter: Int = 3,
    private val scheduler: PollScheduler = PollScheduler(),
    private val background: StateFlow<Boolean> = MutableStateFlow(false),
    private val voltageEveryMs: Long = PollTiers.ADAPTER_VOLTAGE_EVERY_MS,
    /** R-E: NAK storm protection of the extended commands; one per connection. */
    private val guard: CommandGuard = CommandGuard(clock),
    private val extendedDisabled: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
) {
    fun poll(pids: List<Pid>, extended: List<ExtendedPollGroup> = emptyList()): Flow<SensorReading> = flow {
        if (pids.isEmpty() && extended.isEmpty()) return@flow
        val misses = mutableMapOf<Int, Int>()
        var silentCycles = 0
        val cycleTimes = ArrayDeque<Long>()
        val pidTimes = mutableMapOf<Int, ArrayDeque<Long>>()
        var cycles = 0
        var cycle = 0L
        var lastVoltageAt = clock()
        // R-E: per group the cycle and time of its last read (never read = due at once).
        val groupCycle = LongArray(extended.size) { Long.MIN_VALUE / 2 }
        val groupAt = LongArray(extended.size) { Long.MIN_VALUE / 2 }
        var nextGroup = 0
        while (true) {
            val current = cycle++
            var worked = false
            val j1979Idle = pids.isEmpty() || pids.all { it.code in disabled.value }
            val active = if (j1979Idle) emptyList() else scheduler.due(current, pids, background.value).filter { it.code !in disabled.value }
            if (active.isNotEmpty()) {
                worked = true
                val started = clock()
                val results = session.readAll(active)
                results.forEach { r -> r.reading?.let { emit(it) } }
                if (results.none { it.reading != null }) {
                    if (++silentCycles >= stallAfter) {
                        throw EcuStalledException(results.filter { !it.failed }.map { it.pid.code }.toSet())
                    }
                } else {
                    silentCycles = 0
                    for (r in results) {
                        val code = r.pid.code
                        if (r.reading != null) {
                            misses.remove(code)
                        } else {
                            val n = (misses[code] ?: 0) + 1
                            misses[code] = n
                            if (n >= disableAfter) disabled.update { it + code }
                        }
                    }
                }
                cycles++
                cycleTimes.push(clock() - started)
                results.forEach { r -> pidTimes.getOrPut(r.pid.code) { ArrayDeque() }.push(r.elapsedMs) }
                stats.value = PollStats(cycles, cycleTimes.median(), pidTimes.mapValues { it.value.median() })
            }
            // R-E: at most one extended group per cycle, so the J1979 PIDs keep being read between the groups.
            if (extended.isNotEmpty()) {
                val minCycles = if (pids.isEmpty()) 0 else PollTiers.periodCycles(PollTier.SLOW, background.value).toLong()
                val now = clock()
                val pick = extended.indices.map { (nextGroup + it) % extended.size }.firstOrNull { g ->
                    current - groupCycle[g] >= minCycles && now - groupAt[g] >= extended[g].periodMs &&
                        extended[g].commands.any { guard.allowed(it.command.id) }
                }
                if (pick != null) {
                    worked = true
                    nextGroup = (pick + 1) % extended.size
                    groupCycle[pick] = current
                    groupAt[pick] = now
                    readGroup(extended[pick], extended).forEach { emit(it) }
                }
            }
            if (!worked) {
                // Nothing due in this cycle (only slow PIDs, every PID disabled, extended groups waiting): move on.
                if (j1979Idle) delay(if (pids.isEmpty()) EXTENDED_IDLE_PAUSE_MS else ALL_DISABLED_PAUSE_MS) else yield()
                continue
            }
            if (voltageEveryMs > 0 && clock() - lastVoltageAt >= voltageEveryMs) {
                lastVoltageAt = clock()
                adapterVoltage()?.let { emit(SensorReading(PollTiers.ADAPTER_VOLTAGE_ID, it, "V", clock())) }
            }
            if (pauseBetweenCyclesMs > 0) delay(pauseBetweenCyclesMs) else yield()
        }
    }

    /**
     * R-E: one headered scope for the due commands of [group]. Readings are collected and emitted by the caller
     * (emitting from inside the scope would change the flow's context). A link loss propagates; an adapter without
     * header support switches every extended command off for this connection; other adapter errors count as NO DATA.
     */
    private suspend fun readGroup(group: ExtendedPollGroup, all: List<ExtendedPollGroup>): List<SensorReading> {
        val due = group.commands.filter { guard.allowed(it.command.id) }
        if (due.isEmpty()) return emptyList()
        val out = mutableListOf<SensorReading>()
        var done = 0
        try {
            session.withHeaders(group.policy) { channel ->
                for (c in due) {
                    val outcome = channel.runExtended(c.command)
                    guard.record(c.command.id, outcome)
                    done++
                    if (outcome is ExtendedOutcome.Reply) out += runCatching { c.decode(outcome.messages, clock()) }.getOrDefault(emptyList())
                }
            }
        } catch (e: IOException) {
            throw e
        } catch (e: ObdException) {
            if (e.error is ObdError.ModuleScanUnsupported) {
                guard.disableAll(all.flatMap { g -> g.commands.map { it.command.id } })
            } else {
                due.drop(done).forEach { guard.record(it.command.id, ExtendedOutcome.NoData) }
            }
        }
        extendedDisabled.value = guard.disabled
        return out
    }

    /** ATRV is optional data: an adapter error is ignored, only a lost link (IOException) ends the flow. */
    private suspend fun adapterVoltage(): Double? = try {
        session.batteryVoltage()
    } catch (e: IOException) {
        throw e
    } catch (e: ObdException) {
        null
    }

    private fun ArrayDeque<Long>.push(value: Long) {
        addLast(value)
        if (size > WINDOW) removeFirst()
    }

    private fun ArrayDeque<Long>.median(): Long = sorted().let { it[it.size / 2] }

    private companion object {
        const val WINDOW = 20
        const val ALL_DISABLED_PAUSE_MS = 1_000L

        /** Only extended groups are polled and none is due: look again after this pause instead of spinning. */
        const val EXTENDED_IDLE_PAUSE_MS = 100L
    }
}
