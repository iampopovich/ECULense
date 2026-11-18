package io.github.iampopovich.eculense.core.ailocal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchVerdictTest {
    private fun ok(b: BackendKind, decode: Double, ttft: Double, prefill: Double = 100.0) =
        BackendRun(b, ok = true, initSeconds = 2.0, ttftSeconds = ttft, prefillTokensPerSecond = prefill, decodeTokensPerSecond = decode, peakRssBytes = 3L shl 30)

    private fun failed(b: BackendKind, why: String) = BackendRun(b, ok = false, error = why)

    @Test
    fun `fast decode and quick first token is comfortable`() {
        val runs = listOf(ok(BackendKind.CPU, decode = 9.0, ttft = 2.5))
        assertEquals(Verdict.COMFORT, BenchVerdict.verdict(runs))
    }

    @Test
    fun `thresholds are inclusive - 8 tok per s and 3 s`() {
        assertEquals(Verdict.COMFORT, BenchVerdict.verdict(listOf(ok(BackendKind.GPU, decode = 8.0, ttft = 3.0))))
        assertEquals(Verdict.SLOW, BenchVerdict.verdict(listOf(ok(BackendKind.GPU, decode = 7.9, ttft = 1.0))))
        assertEquals(Verdict.SLOW, BenchVerdict.verdict(listOf(ok(BackendKind.GPU, decode = 20.0, ttft = 3.1))))
    }

    @Test
    fun `all backends failed is unsuitable`() {
        assertEquals(Verdict.UNSUITABLE, BenchVerdict.verdict(listOf(failed(BackendKind.CPU, "OOM"), failed(BackendKind.GPU, "crash"))))
        assertEquals(Verdict.UNSUITABLE, BenchVerdict.verdict(emptyList()))
    }

    @Test
    fun `best backend is the comfortable one, then the fastest decode`() {
        val cpu = ok(BackendKind.CPU, decode = 12.0, ttft = 6.0)
        val gpu = ok(BackendKind.GPU, decode = 10.0, ttft = 1.0)
        assertEquals(BackendKind.GPU, BenchVerdict.best(listOf(cpu, gpu, failed(BackendKind.NPU, "нет")))?.backend)
        val slowGpu = ok(BackendKind.GPU, decode = 5.0, ttft = 4.0)
        assertEquals(BackendKind.CPU, BenchVerdict.best(listOf(cpu, slowGpu))?.backend)
    }

    @Test
    fun `a failed backend never wins`() {
        assertNull(BenchVerdict.best(listOf(failed(BackendKind.GPU, "x"))))
        assertEquals(Verdict.SLOW, BenchVerdict.verdict(listOf(failed(BackendKind.GPU, "x"), ok(BackendKind.CPU, 3.0, 9.0))))
    }
}
