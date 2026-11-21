package io.github.iampopovich.eculense.core.database

import org.junit.Assert.assertEquals
import org.junit.Test

class DecimatorTest {
    @Test
    fun `keeps every step-th value by position`() {
        assertEquals(listOf(0.0, 3.0, 6.0, 9.0), Decimator.of((0..10).map { it.toDouble() }, 3))
    }

    @Test
    fun `step 1 keeps everything`() {
        assertEquals(listOf(1.0, 2.0), Decimator.of(listOf(1.0, 2.0), 1))
    }

    @Test
    fun `interleaved sensors each keep count over step values`() {
        // 6 sensors round-robin, 12k rows, step 6: per-sensor decimation keeps ~2000 / 6 ≈ 334 values each, never 0.
        val rows = (0 until 12_000).map { i -> (i % 6) to i.toDouble() }
        val perSensor = rows.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> Decimator.of(v, 6) }
        perSensor.values.forEach { assertEquals(334, it.size) }
        // The old global-id filter (id % 6 == 0) kept only sensor 0.
        val old = rows.withIndex().filter { (it.index + 1) % 6 == 0 }.map { it.value.first }.distinct()
        assertEquals(1, old.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `step 0 is rejected`() {
        Decimator(0)
    }
}
