package io.github.iampopovich.eculense.core.ailocal

import kotlinx.serialization.Serializable

/** Бэкенд LiteRT-LM. */
@Serializable
enum class BackendKind(val label: String) { CPU("CPU"), GPU("GPU"), NPU("NPU") }

/**
 * Замер одного бэкенда (LiteRT-LM `benchmark()` в отдельном процессе `:bench`). ok = false — бэкенд не заработал:
 * ошибка инициализации, нехватка памяти (процесс убит) или падение в нативном коде — [error] объясняет.
 */
@Serializable
data class BackendRun(
    val backend: BackendKind,
    val ok: Boolean,
    val error: String? = null,
    val initSeconds: Double = 0.0,
    val ttftSeconds: Double = 0.0,
    val prefillTokensPerSecond: Double = 0.0,
    val decodeTokensPerSecond: Double = 0.0,
    /** Пик памяти процесса теста (VmHWM). */
    val peakRssBytes: Long? = null,
    val prefillTokens: Int = 0,
    val decodeTokens: Int = 0,
) {
    val comfortable: Boolean get() = ok && decodeTokensPerSecond >= BenchVerdict.COMFORT_DECODE_TPS && ttftSeconds <= BenchVerdict.COMFORT_TTFT_S
}

enum class Verdict(val label: String) {
    COMFORT("Комфортно"),
    SLOW("Медленно"),
    UNSUITABLE("Не подходит"),
}

/**
 * Вердикт по замерам (план 3.10): «Комфортно» — ≥ 8 ток/с генерации и первый токен ≤ 3 с (на промпте теста),
 * «Медленно» — работает, но хуже, «Не подходит» — ни один бэкенд не заработал (OOM/ошибка).
 */
object BenchVerdict {
    const val COMFORT_DECODE_TPS = 8.0
    const val COMFORT_TTFT_S = 3.0

    /** Лучший бэкенд: сначала «комфортный», затем самая быстрая генерация, при равенстве — меньший TTFT. */
    fun best(runs: List<BackendRun>): BackendRun? =
        runs.filter { it.ok }.sortedWith(
            compareByDescending<BackendRun> { it.comfortable }
                .thenByDescending { it.decodeTokensPerSecond }
                .thenBy { it.ttftSeconds },
        ).firstOrNull()

    fun verdict(runs: List<BackendRun>): Verdict {
        val best = best(runs) ?: return Verdict.UNSUITABLE
        return if (best.comfortable) Verdict.COMFORT else Verdict.SLOW
    }
}

/** Итог теста модели: хранится и показывается в мастере. */
@Serializable
data class BenchReport(
    val modelId: String,
    val runs: List<BackendRun>,
    val finishedAtMillis: Long,
) {
    val best: BackendRun? get() = BenchVerdict.best(runs)
    val verdict: Verdict get() = BenchVerdict.verdict(runs)
}
