package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.DtcKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** rank — для сортировки: сначала опасное, затем «нет данных», в конце «в норме». */
enum class Severity(val rank: Int) { OK(0), NO_DATA(1), INFO(2), WARNING(3), CRITICAL(4) }

/** Доказательство находки: подпись и значение, уже округлённое и с единицей. */
data class Evidence(val label: String, val value: String)

/**
 * Результат правила. Числа в message/evidence посчитаны кодом; ИИ их только объясняет.
 * OK и NO_DATA — тоже результаты: «проверено, в норме» и «проверить нечем» (а не ложная находка).
 */
data class Finding(
    val ruleId: String,
    val severity: Severity,
    val title: String,
    val message: String,
    val evidence: List<Evidence> = emptyList(),
    val sessionIds: List<Long> = emptyList(),
    val fromMs: Long? = null,
    val toMs: Long? = null,
)

/** Событие кода ошибки: живой снимок или импортированное событие Car Scanner. */
data class DtcEvent(val code: String, val kind: DtcKind, val atMs: Long)

data class AnalysisInput(
    val car: CarProfile?,
    val sessions: List<SessionDigest>,
    val dtcEvents: List<DtcEvent>,
    val nowMs: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
)

interface AnalyticsRule {
    /** Все ruleId, которые правило может вернуть (для «нет данных» и ссылок). */
    val ids: List<String>
    fun evaluate(input: AnalysisInput): List<Finding>
}

object AnalyticsEngine {
    val RULES: List<AnalyticsRule> = listOf(CoolantOverheatRule, VoltageRule, FuelTrimRule, MafIdleRule, DtcRule, MilDistanceRule, WarmupTrendRule)

    fun evaluate(input: AnalysisInput, rules: List<AnalyticsRule> = RULES): List<Finding> =
        rules.flatMap { it.evaluate(input) }.sortedByDescending { it.severity.rank }
}

/** Числа по-русски: запятая, округление; знак у коррекций. */
internal object Fmt {
    private val ru = Locale.forLanguageTag("ru")
    fun n(v: Double, decimals: Int = 1): String = String.format(ru, "%.${decimals}f", v)
    fun signed(v: Double, decimals: Int = 1): String = (if (v > 0) "+" else "") + n(v, decimals)
    fun seconds(ms: Long): String = "${ms / 1000} с"
    fun minutes(ms: Long): String = "${ms / 60_000} мин"
    fun times(n: Int): String = if (n % 10 in 2..4 && n % 100 !in 12..14) "раза" else "раз"
    private val date = DateTimeFormatter.ofPattern("d MMM yyyy", ru)
    fun date(ms: Long, zone: ZoneId): String = date.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun range(from: Long, to: Long, zone: ZoneId): String =
        if (date(from, zone) == date(to, zone)) date(from, zone) else "${date(from, zone)} – ${date(to, zone)}"
}

private fun ok(id: String, title: String, message: String, evidence: List<Evidence> = emptyList()) =
    Finding(id, Severity.OK, title, message, evidence)

private fun noData(id: String, title: String, message: String) = Finding(id, Severity.NO_DATA, title, message)

/** ОЖ ≥ 105 °C дольше 30 с — WARNING, ≥ 110 °C дольше 30 с — CRITICAL. */
object CoolantOverheatRule : AnalyticsRule {
    private const val ID = "coolant-overheat"
    private const val TITLE = "Перегрев двигателя"
    override val ids = listOf(ID)

    override fun evaluate(input: AnalysisInput): List<Finding> {
        val withCoolant = input.sessions.filter { it.coolant.count > 0 }
        if (withCoolant.isEmpty()) return listOf(noData(ID, TITLE, "Нет записей температуры ОЖ (PID 01.05)."))
        val max = withCoolant.maxOf { it.coolant.max }
        val crit = withCoolant.filter { it.overheatCritMs >= Thresholds.OVERHEAT_MIN_MS }
        val warn = withCoolant.filter { it.overheatWarnMs >= Thresholds.OVERHEAT_MIN_MS }
        val evidence = listOf(
            Evidence("Максимум ОЖ", "${Fmt.n(max)} °C"),
            Evidence("Записей с ОЖ", withCoolant.size.toString()),
        )
        fun hit(severity: Severity, sessions: List<SessionDigest>, limit: Double, longest: (SessionDigest) -> Long) = Finding(
            ID, severity, TITLE,
            "Температура ОЖ держалась ≥ ${Fmt.n(limit, 0)} °C дольше ${Thresholds.OVERHEAT_MIN_MS / 1000} с в ${sessions.size} записях.",
            evidence + Evidence("Самый долгий отрезок", Fmt.seconds(sessions.maxOf(longest))),
            sessionIds = sessions.map { it.sessionId },
            fromMs = sessions.minOf { it.startedAtMs }, toMs = sessions.maxOf { it.startedAtMs },
        )
        return listOf(
            when {
                crit.isNotEmpty() -> hit(Severity.CRITICAL, crit, Thresholds.OVERHEAT_CRIT_C) { it.overheatCritMs }
                warn.isNotEmpty() -> hit(Severity.WARNING, warn, Thresholds.OVERHEAT_WARN_C) { it.overheatWarnMs }
                else -> ok(ID, TITLE, "Перегрева нет: выше ${Fmt.n(Thresholds.OVERHEAT_WARN_C, 0)} °C дольше 30 с не было.", evidence)
            },
        )
    }
}

/** Напряжение на работающем двигателе: < 13,2 В дольше 60 с и > 14,8 В дольше 10 с. */
object VoltageRule : AnalyticsRule {
    private const val LOW = "voltage-low"
    private const val HIGH = "voltage-high"
    override val ids = listOf(LOW, HIGH)

    override fun evaluate(input: AnalysisInput): List<Finding> {
        val withV = input.sessions.filter { it.runningVoltage.count > 0 }
        if (withV.isEmpty()) {
            val msg = "Нет напряжения на работающем двигателе (PID 01.42 или напряжение адаптера)."
            return listOf(noData(LOW, "Недозаряд АКБ", msg), noData(HIGH, "Перезаряд АКБ", msg))
        }
        val all = withV.map { it.runningVoltage }.merged()
        val median = withV.map { it.runningVoltageHist }.fold(Histogram.EMPTY) { a, b -> a + b }.quantile(0.5)
        val sources = withV.mapNotNull { it.voltageSensor }.distinct().joinToString { SensorIds.NAMES[it] ?: it }
        val evidence = listOfNotNull(
            Evidence("Минимум на работающем двигателе", "${Fmt.n(all.min)} В"),
            median?.let { Evidence("Медиана", "${Fmt.n(it)} В") },
            Evidence("Максимум", "${Fmt.n(all.max)} В"),
            Evidence("Источник", sources),
        )
        val low = withV.filter { it.lowVoltageMs >= Thresholds.VOLTAGE_LOW_MIN_MS }
        val high = withV.filter { it.highVoltageMs >= Thresholds.VOLTAGE_HIGH_MIN_MS }
        return listOf(
            if (low.isEmpty()) {
                ok(LOW, "Недозаряд АКБ", "Генератор заряжает: ниже ${Fmt.n(Thresholds.VOLTAGE_LOW_V)} В дольше минуты не было.", evidence)
            } else {
                Finding(
                    LOW, Severity.WARNING, "Недозаряд АКБ",
                    "Напряжение ниже ${Fmt.n(Thresholds.VOLTAGE_LOW_V)} В на работающем двигателе дольше минуты в ${low.size} записях — проверьте генератор, ремень и клеммы.",
                    evidence + Evidence("Самый долгий отрезок", Fmt.seconds(low.maxOf { it.lowVoltageMs })),
                    low.map { it.sessionId }, low.minOf { it.startedAtMs }, low.maxOf { it.startedAtMs },
                )
            },
            if (high.isEmpty()) {
                ok(HIGH, "Перезаряд АКБ", "Выше ${Fmt.n(Thresholds.VOLTAGE_HIGH_V)} В не было.", evidence)
            } else {
                Finding(
                    HIGH, Severity.WARNING, "Перезаряд АКБ",
                    "Напряжение выше ${Fmt.n(Thresholds.VOLTAGE_HIGH_V)} В в ${high.size} записях — возможна неисправность регулятора напряжения.",
                    evidence + Evidence("Самый долгий отрезок", Fmt.seconds(high.maxOf { it.highVoltageMs })),
                    high.map { it.sessionId }, high.minOf { it.startedAtMs }, high.maxOf { it.startedAtMs },
                )
            },
        )
    }
}

/** Топливные коррекции на прогретом двигателе. Знак «+» — ЭБУ добавляет топливо (смесь бедная). */
object FuelTrimRule : AnalyticsRule {
    private const val LONG = "fuel-trim-long"
    private const val TOTAL = "fuel-trim-total"
    private const val ASYM = "fuel-trim-bank-asymmetry"
    override val ids = listOf(LONG, TOTAL, ASYM)

    private data class Bank(val n: Int, val stft: Agg, val ltft: Agg, val sessions: List<Long>)

    override fun evaluate(input: AnalysisInput): List<Finding> {
        fun merged(id: String) = input.sessions.mapNotNull { it.warmTrims[id] }.merged()
        fun sessionsWith(vararg ids: String) = input.sessions.filter { d -> ids.any { (d.warmTrims[it]?.count ?: 0) > 0 } }.map { it.sessionId }
        val banks = listOf(
            Bank(1, merged(SensorIds.STFT1), merged(SensorIds.LTFT1), sessionsWith(SensorIds.STFT1, SensorIds.LTFT1)),
            Bank(2, merged(SensorIds.STFT2), merged(SensorIds.LTFT2), sessionsWith(SensorIds.STFT2, SensorIds.LTFT2)),
        )
        val withLt = banks.filter { it.ltft.count > 0 }
        if (withLt.isEmpty()) {
            val msg = "Нет долгосрочных коррекций (01.07/01.09) на прогретом двигателе (ОЖ ≥ 80 °C)."
            return listOf(noData(LONG, "Долгосрочная коррекция топлива", msg), noData(TOTAL, "Суммарная коррекция топлива", msg), noData(ASYM, "Разница банков", msg))
        }
        val few = { b: Bank -> b.ltft.count < Thresholds.MIN_TRIM_SAMPLES }
        val evidence = withLt.flatMap { b ->
            listOfNotNull(
                Evidence("LTFT банк ${b.n}", "${Fmt.signed(b.ltft.mean!!)} % (замеров: ${b.ltft.count})"),
                b.stft.mean?.let { Evidence("STFT банк ${b.n}", "${Fmt.signed(it)} %, макс. ${Fmt.signed(b.stft.max)} % (замеров: ${b.stft.count})") },
            )
        }
        val sessions = withLt.flatMap { it.sessions }.distinct()
        val fewNote = if (withLt.any(few)) " Замеров мало — подтвердите длительной записью на прогретом двигателе." else ""

        val out = withLt.filter { abs(it.ltft.mean!!) > Thresholds.LTFT_LIMIT_PCT }
        val long = if (out.isEmpty()) {
            ok(LONG, "Долгосрочная коррекция топлива", "Долгосрочные коррекции в пределах ±${Fmt.n(Thresholds.LTFT_LIMIT_PCT, 0)} %.", evidence)
        } else {
            val parts = out.joinToString { "банк ${it.n} ${Fmt.signed(it.ltft.mean!!)} %" }
            val lean = out.all { it.ltft.mean!! > 0 }
            Finding(
                LONG, Severity.WARNING, "Долгосрочная коррекция топлива",
                "Долгосрочная коррекция вне ±${Fmt.n(Thresholds.LTFT_LIMIT_PCT, 0)} % на прогретом двигателе: $parts." +
                    (if (lean) " ЭБУ добавляет топливо — смесь бедная (подсос воздуха, MAF, давление топлива)." else "") + fewNote,
                evidence, sessions,
            )
        }

        val totals = withLt.filter { it.stft.count > 0 }.map { it to it.stft.mean!! + it.ltft.mean!! }
        val total = when {
            totals.isEmpty() -> noData(TOTAL, "Суммарная коррекция топлива", "Нет краткосрочных коррекций (01.06/01.08) на прогретом двигателе.")
            totals.none { abs(it.second) > Thresholds.TOTAL_TRIM_CRIT_PCT } ->
                ok(TOTAL, "Суммарная коррекция топлива", "STFT+LTFT в пределах ±${Fmt.n(Thresholds.TOTAL_TRIM_CRIT_PCT, 0)} %.", evidence)
            else -> {
                val bad = totals.filter { abs(it.second) > Thresholds.TOTAL_TRIM_CRIT_PCT }
                val enough = bad.all { (b, _) -> b.ltft.count >= Thresholds.MIN_TRIM_SAMPLES && b.stft.count >= Thresholds.MIN_TRIM_SAMPLES }
                Finding(
                    TOTAL, if (enough) Severity.CRITICAL else Severity.WARNING, "Суммарная коррекция топлива",
                    "STFT+LTFT за ±${Fmt.n(Thresholds.TOTAL_TRIM_CRIT_PCT, 0)} %: " + bad.joinToString { (b, t) -> "банк ${b.n} ${Fmt.signed(t)} %" } +
                        " — ЭБУ на пределе регулирования смеси." + if (enough) "" else " Замеров мало, вывод предварительный.",
                    evidence, sessions,
                )
            }
        }

        val asym = if (withLt.size < 2) {
            noData(ASYM, "Разница банков", "Долгосрочная коррекция есть только для одного банка.")
        } else {
            val diff = abs(withLt[0].ltft.mean!! - withLt[1].ltft.mean!!)
            val ev = evidence + Evidence("Разница LTFT", "${Fmt.n(diff)} %")
            if (diff > Thresholds.BANK_ASYMMETRY_PCT) {
                Finding(ASYM, Severity.WARNING, "Разница банков", "Долгосрочные коррекции банков расходятся на ${Fmt.n(diff)} % — проблема на одной стороне (подсос, форсунки, датчик O2).$fewNote", ev, sessions)
            } else {
                ok(ASYM, "Разница банков", "Банки корректируются одинаково (разница ${Fmt.n(diff)} %).", ev)
            }
        }
        return listOf(long, total, asym)
    }
}

/** MAF на прогретом холостом ходу относительно объёма двигателя из профиля. */
object MafIdleRule : AnalyticsRule {
    private const val ID = "maf-idle"
    private const val TITLE = "Расход воздуха на холостом ходу"
    override val ids = listOf(ID)

    override fun evaluate(input: AnalysisInput): List<Finding> {
        val maf = input.sessions.map { it.warmIdleMaf }.merged()
        val volume = input.car?.engineVolumeL?.takeIf { it > 0 }
        if (volume == null) {
            val measured = maf.mean?.let { " Измерено: ${Fmt.n(it)} г/с на прогретом ХХ." }.orEmpty()
            return listOf(noData(ID, TITLE, "Нет данных: укажите объём двигателя в Настройки → Автомобиль — без него норму MAF не посчитать.$measured"))
        }
        if (maf.count < Thresholds.MAF_IDLE_MIN_SAMPLES) {
            return listOf(noData(ID, TITLE, "Нет замеров MAF (01.10) на прогретом холостом ходу (ОЖ ≥ 80 °C, скорость 0, обороты 400–1100)."))
        }
        val perL = maf.mean!! / volume
        val evidence = listOf(
            Evidence("MAF на прогретом ХХ", "${Fmt.n(maf.mean!!)} г/с (замеров: ${maf.count})"),
            Evidence("Объём двигателя", "${Fmt.n(volume)} л"),
            Evidence("На литр", "${Fmt.n(perL, 2)} г/с·л (ориентир ${Fmt.n(Thresholds.MAF_IDLE_MIN_G_PER_L)}–${Fmt.n(Thresholds.MAF_IDLE_MAX_G_PER_L)})"),
        )
        val sessions = input.sessions.filter { it.warmIdleMaf.count > 0 }.map { it.sessionId }
        return listOf(
            when {
                perL < Thresholds.MAF_IDLE_MIN_G_PER_L -> Finding(
                    ID, Severity.WARNING, TITLE,
                    "MAF на холостом ходу ниже ориентира: ${Fmt.n(perL, 2)} г/с на литр. Возможны загрязнение датчика MAF или подсос воздуха после него.",
                    evidence, sessions,
                )
                perL > Thresholds.MAF_IDLE_MAX_G_PER_L -> Finding(
                    ID, Severity.INFO, TITLE,
                    "MAF на холостом ходу выше ориентира: ${Fmt.n(perL, 2)} г/с на литр (повышенные обороты ХХ, нагрузка, датчик).",
                    evidence, sessions,
                )
                else -> ok(ID, TITLE, "MAF на холостом ходу в норме: ${Fmt.n(perL, 2)} г/с на литр.", evidence)
            },
        )
    }
}

/** Пробег с горящим Check Engine (PID 01.21) и его рост. */
object MilDistanceRule : AnalyticsRule {
    private const val ID = "mil-distance"
    private const val TITLE = "Пробег с горящим Check Engine"
    override val ids = listOf(ID)

    override fun evaluate(input: AnalysisInput): List<Finding> {
        val with = input.sessions.filter { it.milDistanceKm.count > 0 }.sortedBy { it.startedAtMs }
        if (with.isEmpty()) return listOf(noData(ID, TITLE, "Нет замеров PID 01.21."))
        val lit = with.filter { it.milDistanceKm.max > 0 }
        if (lit.isEmpty()) return listOf(ok(ID, TITLE, "Check Engine не горел: пробег с MIL 0 км.", listOf(Evidence("Записей с PID 01.21", with.size.toString()))))
        val first = lit.first().milDistanceKm.min.takeIf { it > 0 } ?: lit.first().milDistanceKm.max
        val last = lit.maxOf { it.milDistanceKm.max }
        val evidence = listOf(
            Evidence("Пробег с MIL", "${Fmt.n(last, 0)} км"),
            Evidence("Первое ненулевое значение", "${Fmt.n(first, 0)} км, ${Fmt.date(lit.first().startedAtMs, input.zone)}"),
            Evidence("Записей с MIL", lit.size.toString()),
        )
        val growth = if (last > first) " Рост с ${Fmt.n(first, 0)} до ${Fmt.n(last, 0)} км." else ""
        return listOf(
            Finding(
                ID, Severity.WARNING, TITLE,
                "Машина проехала ${Fmt.n(last, 0)} км с горящим Check Engine.$growth Ошибку стоит устранить, а не ездить с ней.",
                evidence, lit.map { it.sessionId }, lit.first().startedAtMs, lit.last().startedAtMs,
            ),
        )
    }
}

/** Время прогрева до 80 °C с холодного пуска: тренд между ранними и поздними поездками. */
object WarmupTrendRule : AnalyticsRule {
    private const val ID = "warmup-trend"
    private const val TITLE = "Время прогрева"
    override val ids = listOf(ID)

    private fun median(v: List<Long>): Long = v.sorted()[(v.size - 1) / 2]

    override fun evaluate(input: AnalysisInput): List<Finding> {
        val starts = input.sessions.filter { it.warmupMs != null }.sortedBy { it.startedAtMs }
        val min = Thresholds.WARMUP_MIN_STARTS_PER_HALF
        if (starts.size < 2 * min) {
            return listOf(noData(ID, TITLE, "Мало холодных пусков с прогревом до 80 °C: ${starts.size} (нужно хотя бы ${2 * min})."))
        }
        val half = starts.size / 2
        val early = median(starts.take(half).map { it.warmupMs!! })
        val late = median(starts.drop(starts.size - half).map { it.warmupMs!! })
        val evidence = listOf(
            Evidence("Медиана прогрева раньше", "${Fmt.minutes(early)} (${Fmt.range(starts.first().startedAtMs, starts[half - 1].startedAtMs, input.zone)})"),
            Evidence("Медиана прогрева позже", "${Fmt.minutes(late)} (${Fmt.range(starts[starts.size - half].startedAtMs, starts.last().startedAtMs, input.zone)})"),
            Evidence("Холодных пусков", starts.size.toString()),
        )
        return listOf(
            if (late > early * Thresholds.WARMUP_TREND_RATIO) {
                Finding(
                    ID, Severity.INFO, TITLE,
                    "Прогрев до 80 °C стал дольше: ${Fmt.minutes(early)} → ${Fmt.minutes(late)}. Влияет погода; если холоднее не стало — проверьте термостат.",
                    evidence, starts.drop(starts.size - half).map { it.sessionId },
                )
            } else {
                ok(ID, TITLE, "Время прогрева стабильно.", evidence)
            },
        )
    }
}

/** Коды ошибок: группы связанных кодов, повторы за 30 дней, остальные — справкой. */
object DtcRule : AnalyticsRule {
    private data class Group(val id: String, val title: String, val codes: (String) -> Boolean, val explain: String)

    private val GROUPS = listOf(
        Group("dtc-air-sensors", "Датчики воздуха (MAF/MAP/IAT)", { c -> c.length == 5 && c.startsWith("P01") && c.drop(3).toIntOrNull()?.let { it in 0..14 } == true },
            "коды одной системы измерения воздуха — часто общий разъём, проводка или питание датчиков"),
        Group("dtc-mixture", "Смесь (P0171/P0174)", { c -> c in setOf("P0171", "P0172", "P0174", "P0175") },
            "ЭБУ не может удержать состав смеси на обоих банках — подсос воздуха, MAF, давление топлива"),
    )
    override val ids = GROUPS.map { it.id } + listOf("dtc-repeat", "dtc-other", "dtc-history")

    /** Чтения одного кода чаще раза в 6 часов — одно событие. */
    private fun occurrences(events: List<DtcEvent>): Map<String, List<DtcEvent>> =
        events.groupBy { it.code }.mapValues { (_, list) ->
            list.sortedBy { it.atMs }.fold(mutableListOf<DtcEvent>()) { acc, e ->
                if (acc.isEmpty() || e.atMs - acc.last().atMs >= Thresholds.DTC_SAME_EVENT_MS) acc += e
                acc
            }
        }

    private fun repeated(list: List<DtcEvent>) = list.zipWithNext().any { (a, b) -> b.atMs - a.atMs <= Thresholds.DTC_REPEAT_WINDOW_MS }

    override fun evaluate(input: AnalysisInput): List<Finding> {
        if (input.dtcEvents.isEmpty()) return listOf(ok("dtc-history", "Коды ошибок", "В истории нет кодов ошибок."))
        val occ = occurrences(input.dtcEvents)
        fun evidence(code: String): Evidence {
            val list = occ.getValue(code)
            val pending = if (list.all { it.kind == DtcKind.PENDING }) ", ожидающий" else ""
            return Evidence(code, "${list.size} ${Fmt.times(list.size)}$pending: ${Fmt.range(list.first().atMs, list.last().atMs, input.zone)}")
        }
        fun summary(codes: List<String>) = codes.joinToString { "$it×${occ.getValue(it).size}" }
        val used = mutableSetOf<String>()
        val out = mutableListOf<Finding>()
        for (g in GROUPS) {
            val codes = occ.keys.filter(g.codes).sorted()
            if (codes.isEmpty()) continue
            val grouped = codes.size >= 2 || codes.any { occ.getValue(it).size >= 2 }
            if (!grouped) continue
            used += codes
            val all = codes.flatMap { occ.getValue(it) }
            out += Finding(
                g.id, Severity.WARNING, g.title,
                "${summary(codes)}: ${g.explain}.",
                codes.map(::evidence), fromMs = all.minOf { it.atMs }, toMs = all.maxOf { it.atMs },
            )
        }
        val repeats = occ.filterKeys { it !in used }.filterValues(::repeated).keys.sorted()
        if (repeats.isNotEmpty()) {
            used += repeats
            out += Finding(
                "dtc-repeat", Severity.WARNING, "Повторяющиеся коды",
                "Коды повторялись в течение 30 дней: ${summary(repeats)} — неисправность не ушла сама.",
                repeats.map(::evidence),
            )
        }
        val rest = occ.keys.filter { it !in used }.sorted()
        if (rest.isNotEmpty()) {
            out += Finding(
                "dtc-other", Severity.INFO, "Прочие коды",
                "Однократные или давние коды: ${summary(rest)}.",
                rest.map(::evidence),
            )
        }
        return out
    }
}
