package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.SensorReading

/**
 * R-E: one step of a planned extended (manufacturer, non-J1979) read. Steps are produced outside :core:obd (the
 * OBDb planner in :core:obdb knows the signalset format; this module only knows ELM327 commands) and executed by
 * [runExtended] inside [ObdSession.withHeaders], so the scoped header mode and its restore apply to them.
 */
sealed interface ElmStep {
    /**
     * An adapter setting from [ExtendedSettings.ALLOWED]. [optional]: an adapter answering '?' only loses the tuning
     * (e.g. ATST); otherwise '?' marks the whole command unsupported on this adapter (capability fallback).
     */
    data class Setting(val command: String, val optional: Boolean = false) : ElmStep

    /**
     * A request to [target]; it passes the [EcuSafetyPolicy] of the scope before anything is written. [collect] =
     * the reply is the data of the command (false for the 10 xx session switches around it).
     */
    data class Request(val target: EcuTarget, val command: String, val collect: Boolean = true) : ElmStep
}

/** A planned extended command: [id] is stable per command (OBDb style «7E4.7EC.220101»), used by [CommandGuard]. */
data class ExtendedCommand(val id: String, val target: EcuTarget, val steps: List<ElmStep>)

/** What one extended command gave. */
sealed interface ExtendedOutcome {
    /** Positive reply messages of the addressed ECU. */
    data class Reply(val messages: List<ByteArray>) : ExtendedOutcome

    data object NoData : ExtendedOutcome

    /** 7F <sid> <nrc>: the ECU refused. */
    data class Negative(val nrc: Int) : ExtendedOutcome

    /** The adapter cannot do a required setting ('?') or the protocol differs; [reason] is technical English. */
    data class Unsupported(val reason: String) : ExtendedOutcome

    /** The [EcuSafetyPolicy] refused a request: nothing was written. */
    data class Denied(val reason: String) : ExtendedOutcome
}

/**
 * The adapter settings an extended read may change, and how [HeaderedChannel] puts them back (ELM327DSJ defaults:
 * CP 18, CEA off, TA F1, ST 32, FCSM 0, AR = automatic receive, IB 10 = 10400 baud). ATSH/ATCRA/ATH are restored by
 * the scope itself. ATSP is accepted only when it names the protocol already in use (the scope never switches
 * protocols: a switch would drop live polling into a new protocol search).
 */
object ExtendedSettings {
    val ALLOWED: List<String> = listOf("ATFCSH", "ATFCSD", "ATFCSM", "ATCEA", "ATCRA", "ATCP", "ATSH", "ATSP", "ATTA", "ATST", "ATSR", "ATIB")

    /** The settings family of [command] («ATFCSH7E4» → «ATFCSH»), or null when it is not allowed. */
    fun keyOf(command: String): String? {
        val c = command.replace(" ", "").uppercase()
        val key = ALLOWED.firstOrNull { c.startsWith(it) } ?: return null
        val arg = c.removePrefix(key)
        return key.takeIf { arg.all { ch -> ch in "0123456789ABCDEF" } }
    }

    /** Command restoring the default of [key]; null = restored by the scope (ATSH/ATCRA) or never changed (ATSP). */
    fun restoreOf(key: String): String? = when (key) {
        "ATCP" -> "ATCP18"
        "ATCEA" -> "ATCEA"
        "ATTA" -> "ATTAF1"
        "ATST" -> "ATST32"
        "ATFCSH", "ATFCSD", "ATFCSM" -> "ATFCSM0"
        "ATSR" -> "ATAR"
        "ATIB" -> "ATIB10"
        else -> null
    }
}

/**
 * Runs one [ExtendedCommand] on the channel. Policy refusals and missing capabilities become outcomes (never
 * exceptions), an IOException (link lost) propagates.
 */
suspend fun HeaderedChannel.runExtended(command: ExtendedCommand, timeoutMs: Long = HeaderedChannel.DEFAULT_TIMEOUT_MS): ExtendedOutcome {
    // Every request of the command passes the policy before the first step: no ATSH/ATCRA to a blocked module either.
    for (step in command.steps) {
        if (step !is ElmStep.Request) continue
        val decision = policy.check(step.target, step.command)
        if (decision is PolicyDecision.Denied) return ExtendedOutcome.Denied(decision.reason.name)
    }
    var data: ExtendedOutcome = ExtendedOutcome.NoData
    var collected = false
    for (step in command.steps) {
        when (step) {
            is ElmStep.Setting -> {
                val ok = try {
                    setting(step.command)
                } catch (e: ObdException) {
                    return ExtendedOutcome.Unsupported(e.error.technicalMessage)
                }
                if (!ok && !step.optional) return ExtendedOutcome.Unsupported("'${step.command}' answered '?'")
            }
            is ElmStep.Request -> {
                val reply = try {
                    request(step.target, step.command, timeoutMs)
                } catch (e: ObdException) {
                    val err = e.error
                    return if (err is ObdError.RequestDenied) ExtendedOutcome.Denied(err.reason) else ExtendedOutcome.Unsupported(err.technicalMessage)
                }
                val sid = step.command.take(2).toInt(16)
                val nrc = reply?.let { negativeCode(it, sid) }
                if (!step.collect) {
                    // After the data: a failed «10 dout» does not spoil the reply (the ECU drops back by itself).
                    if (collected) continue
                    // Before the data: a refused session switch means the read would run in the wrong session.
                    if (reply == null) return ExtendedOutcome.NoData
                    if (nrc != null) return ExtendedOutcome.Negative(nrc)
                    continue
                }
                collected = true
                data = when {
                    reply == null -> ExtendedOutcome.NoData
                    nrc != null -> ExtendedOutcome.Negative(nrc)
                    else -> reply.filter { it.isNotEmpty() && (it[0].toInt() and 0xFF) == sid + 0x40 }
                        .takeIf { it.isNotEmpty() }?.let { ExtendedOutcome.Reply(it) } ?: ExtendedOutcome.NoData
                }
            }
        }
    }
    return data
}

/**
 * NAK storm protection (R-E, research D «risks»: failed requests like 22 0104 to an ABS module can flood the bus with
 * negative replies). Per command id:
 * - a reply resets the count;
 * - NO DATA or a negative reply counts as a failure and pauses the command exponentially ([baseBackoffMs] · 2^(n-1),
 *   capped at [maxBackoffMs]);
 * - after [disableAfter] failures in a row, an «unsupported» negative reply (ISO 14229-1 NRC 0x11 service not
 *   supported, 0x12 sub-function not supported, 0x31 request out of range), a missing adapter capability or a policy
 *   refusal, the command is disabled until reconnect (a new guard per connection).
 *
 * [disableAfter] = 3 is the same rule as A9 for J1979 PIDs (LivePoller.disableAfter): one or two NO DATA in a row are
 * normal while an ECU wakes up or answers 0x21 busyRepeatRequest / 0x22 conditionsNotCorrect (ISO 14229-1 Annex A),
 * three in a row with 2 s and 4 s pauses between them span more than 6 s. How to calibrate on a real car: count the
 * NO DATA / 7F runs per command in the adapter traffic log of a drive and raise it only if a command that answers
 * later is disabled.
 */
class CommandGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val disableAfter: Int = DISABLE_AFTER,
    private val baseBackoffMs: Long = BASE_BACKOFF_MS,
    private val maxBackoffMs: Long = MAX_BACKOFF_MS,
) {
    private val failures = HashMap<String, Int>()
    private val pausedUntil = HashMap<String, Long>()
    private val _disabled = LinkedHashSet<String>()

    /** Commands switched off for this connection. */
    val disabled: Set<String> @Synchronized get() = _disabled.toSet()

    @Synchronized
    fun allowed(id: String): Boolean = id !in _disabled && clock() >= (pausedUntil[id] ?: 0L)

    @Synchronized
    fun record(id: String, outcome: ExtendedOutcome) {
        when (outcome) {
            is ExtendedOutcome.Reply -> {
                failures.remove(id)
                pausedUntil.remove(id)
            }
            is ExtendedOutcome.Unsupported, is ExtendedOutcome.Denied -> _disabled += id
            is ExtendedOutcome.Negative, ExtendedOutcome.NoData -> {
                val n = (failures[id] ?: 0) + 1
                failures[id] = n
                if (n >= disableAfter || (outcome is ExtendedOutcome.Negative && outcome.nrc in PERMANENT_NRC)) {
                    _disabled += id
                } else {
                    pausedUntil[id] = clock() + (baseBackoffMs shl (n - 1)).coerceAtMost(maxBackoffMs)
                }
            }
        }
    }

    @Synchronized
    fun disableAll(ids: Collection<String>) {
        _disabled += ids
    }

    companion object {
        const val DISABLE_AFTER = 3
        const val BASE_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L

        /** ISO 14229-1 negative response codes that will not change while connected. */
        val PERMANENT_NRC: Set<Int> = setOf(0x11, 0x12, 0x31)
    }
}

/**
 * One extended command of a poll group and how its reply becomes readings (decoding lives outside :core:obd).
 * [periodMs]: the command is read at most this often even when its group is read more often.
 */
class ExtendedPollCommand(
    val command: ExtendedCommand,
    val periodMs: Long = 0,
    val decode: (messages: List<ByteArray>, atMs: Long) -> List<SensorReading>,
)

/**
 * R-E: extended commands to one ECU header, read together in one headered scope so ATSH changes once per group.
 * [periodMs]: read at most this often (the command's freq); the poller also waits at least the SLOW tier's number of
 * J1979 cycles between two reads of a group. [policy] lists the sessions the group's commands may switch to (OBDb
 * din/dout), nothing else.
 */
class ExtendedPollGroup(
    val header: String,
    val periodMs: Long,
    val commands: List<ExtendedPollCommand>,
    val policy: EcuSafetyPolicy = EcuSafetyPolicy(),
)
