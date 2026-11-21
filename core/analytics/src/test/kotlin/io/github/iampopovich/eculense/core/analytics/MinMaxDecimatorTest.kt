package io.github.iampopovich.eculense.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MinMaxDecimatorTest {
    @Test
    fun `117k values become at most maxPoints and keep a one-sample spike`() {
        val n = 117_000
        val d = MinMaxDecimator(0, n - 1L, 800)
        for (i in 0 until n) d.offer(i.toLong(), if (i == 54_321) 6_500.0 else 800.0 + (i % 7))
        val pts = d.points()
        assertTrue("${pts.size}", pts.size <= 800)
        assertTrue(pts.any { it.value == 6_500.0 && it.tMs == 54_321L })
        assertEquals(800.0, pts.minOf { it.value }, 0.0)
        assertEquals(n.toLong(), d.offered)
    }

    @Test
    fun `points stay in time order, min and max of a bucket once each`() {
        val d = MinMaxDecimator(0, 99, 4) // 2 buckets of 50 ms
        listOf(0L to 5.0, 10L to 9.0, 20L to 1.0, 60L to 3.0, 70L to 3.0).forEach { (t, v) -> d.offer(t, v) }
        assertEquals(listOf(ChartPoint(10, 9.0), ChartPoint(20, 1.0), ChartPoint(60, 3.0)), d.points())
    }

    @Test
    fun `values outside the range and NaN are ignored, short input is returned as is`() {
        val d = MinMaxDecimator(100, 200, 10)
        d.offer(50, 1.0); d.offer(250, 1.0); d.offer(150, Double.NaN)
        assertEquals(emptyList<ChartPoint>(), d.points())
        val few = listOf(ChartPoint(2, 1.0), ChartPoint(1, 2.0))
        assertEquals(listOf(ChartPoint(1, 2.0), ChartPoint(2, 1.0)), MinMaxDecimator.of(few, 10))
    }

    @Test
    fun `of keeps extremes of a long list`() {
        val pts = (0 until 10_000).map { ChartPoint(it.toLong(), kotlin.math.sin(it / 50.0)) } + ChartPoint(5_000, -9.0)
        val out = MinMaxDecimator.of(pts, 100)
        assertTrue(out.size <= 100)
        assertEquals(-9.0, out.minOf { it.value }, 0.0)
        assertEquals(out.sortedBy { it.tMs }, out)
    }
}
