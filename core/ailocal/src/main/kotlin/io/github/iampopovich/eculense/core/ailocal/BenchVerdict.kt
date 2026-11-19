package io.github.iampopovich.eculense.core.ailocal

import kotlinx.serialization.Serializable

/** LiteRT-LM backend. */
@Serializable
enum class BackendKind(val label: String) { CPU("CPU"), GPU("GPU"), NPU("NPU") }

/**
 * Measurement of one backend (LiteRT-LM `benchmark()` in a separate `:bench` process). ok = false means the backend failed:
 * init error, out of memory (process killed) or a native crash; [error] explains which.
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
    /** Peak memory of the test process (VmHWM). */
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
 * Verdict from measurements (plan 3.10): «Комфортно» = ≥ 8 tok/s generation and first token ≤ 3 s (on the test prompt),
 * «Медленно» = works but worse, «Не подходит» = no backend worked (OOM/error).
 */
object BenchVerdict {
    const val COMFORT_DECODE_TPS = 8.0
    const val COMFORT_TTFT_S = 3.0

    /** Best backend: "comfortable" first, then fastest generation, ties broken by lower TTFT. */
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

/** Model test result: stored and shown in the wizard. */
@Serializable
data class BenchReport(
    val modelId: String,
    val runs: List<BackendRun>,
    val finishedAtMillis: Long,
) {
    val best: BackendRun? get() = BenchVerdict.best(runs)
    val verdict: Verdict get() = BenchVerdict.verdict(runs)
}
