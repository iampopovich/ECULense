package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.analytics.SensorIds.LTFT1
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.FuelMethod
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Синтетика: «сейчас» = 2026-07-02 12:00 UTC, поездки и записи на неделях/месяцах до этого. */
class TrendsTest {
    private val zone = ZoneOffset.UTC
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, m, d, h, 0).toInstant(zone).toEpochMilli()
    private val now = at(2026, 7, 2)

    private fun trip(start: Long, km: Double, fuelL: Double, drivingMin: Long = 20, standingMin: Long = 5, fuel: FuelMethod? = null) =
        Trip(carId = 1, startedAtMs = start, endedAtMs = start + (drivingMin + standingMin) * 60_000, distanceKm = km, fuelL = fuelL,
            fuelPricePerL = null, drivingMs = drivingMin * 60_000, standingMs = standingMin * 60_000, source = TripSource.IMPORT_CARSCANNER,
            fuelMethod = fuel)

    private fun hist(vararg v: Double) = v.fold(Histogram.EMPTY) { h, x -> h.add(x) }
    private fun agg(vararg v: Double) = v.fold(Agg.EMPTY) { a, x -> a.add(x) }
    private fun many(v: Double, n: Int) = DoubleArray(n) { v }

    private fun digest(id: Long, start: Long, coolant: Double, voltage: Double, ltft: Double, idle: Double, n: Int = 40) = SessionDigest(
        sessionId = id, startedAtMs = start,
        coolant = agg(*many(coolant, n), coolant + 6), coolantHist = hist(*many(coolant, n), coolant + 6),
        voltageSensor = SensorIds.ELM_VOLTAGE,
        runningVoltage = agg(*many(voltage, n), voltage - 1.0), runningVoltageHist = hist(*many(voltage, n), voltage - 1.0),
        warmTrims = mapOf(LTFT1 to agg(*many(ltft, n))), warmTrimHist = mapOf(LTFT1 to hist(*many(ltft, n))),
        warmIdleRpm = agg(*many(idle, n)), warmIdleRpmHist = hist(*many(idle, n)),
    )

    @Test
    fun `half a year is six month buckets plus the current one, oldest first`() {
        val r = Trends.build(days = 180, bucket = null, nowMs = now, zone = zone, trips = emptyList(), digests = emptyList(), dtc = emptyList())
        assertEquals(TrendBucket.MONTH, r.bucket)
        assertEquals(listOf("2026-01", "2026-02", "2026-03", "2026-04", "2026-05", "2026-06", "2026-07"), r.buckets.map { it.label })
        assertTrue(r.buckets.all { it.empty })
        // первая корзина обрезана началом периода
        assertEquals(now - 180L * 86_400_000, r.buckets.first().fromMs)
        assertEquals(now, r.buckets.last().toMs)
    }

    @Test
    fun `short period uses weeks starting on monday`() {
        val r = Trends.build(days = 30, bucket = null, nowMs = now, zone = zone, trips = emptyList(), digests = emptyList(), dtc = emptyList())
        assertEquals(TrendBucket.WEEK, r.bucket)
        // 2026-06-02 (вт) … 2026-07-02 (чт): недели с понедельников 06-01 … 06-29
        assertEquals(listOf("2026-06-02", "2026-06-08", "2026-06-15", "2026-06-22", "2026-06-29"), r.buckets.map { it.label })
        assertEquals(TrendBucket.MONTH, Trends.build(30, TrendBucket.MONTH, now, zone, emptyList(), emptyList(), emptyList()).bucket)
    }

    @Test
    fun `trip metrics per bucket`() {
        val trips = listOf(
            trip(at(2026, 6, 3), km = 10.0, fuelL = 1.0, drivingMin = 15, standingMin = 5),
            trip(at(2026, 6, 10), km = 30.0, fuelL = 3.0, drivingMin = 45, standingMin = 15),
            // без оценки топлива — в расход не входит, но км и поездка считаются
            trip(at(2026, 6, 20), km = 20.0, fuelL = 0.0, fuel = FuelMethod.NOT_AVAILABLE),
            trip(at(2025, 1, 1), km = 99.0, fuelL = 9.0), // вне периода
        )
        val r = Trends.build(180, null, now, zone, trips, emptyList(), emptyList())
        val june = r.buckets.single { it.label == "2026-06" }
        assertEquals(3, june.trips.trips)
        assertEquals(60.0, june.trips.distanceKm, 1e-9)
        assertEquals(10.0, june.trips.avgLPer100Km!!, 1e-9)
        assertEquals(20.0, june.avgTripKm!!, 1e-9)
        // стоянка 5 + 15 + 5 = 25 мин из 105
        assertEquals(25.0 / 105.0, june.idleShare!!, 1e-9)
        assertNull(r.buckets.single { it.label == "2026-05" }.avgTripKm)
        assertEquals(3, r.totals.trips)
    }

    @Test
    fun `sensor medians, max coolant and minimum running voltage per bucket`() {
        val digests = listOf(
            digest(1, at(2026, 2, 10), coolant = 88.0, voltage = 14.2, ltft = 3.0, idle = 720.0),
            digest(2, at(2026, 2, 20), coolant = 90.0, voltage = 14.0, ltft = 5.0, idle = 740.0, n = 60),
            digest(3, at(2026, 6, 15), coolant = 92.0, voltage = 13.6, ltft = 9.0, idle = 800.0),
        )
        val r = Trends.build(180, null, now, zone, emptyList(), digests, emptyList())
        val feb = r.buckets.single { it.label == "2026-02" }
        assertEquals(2, feb.sessions)
        assertEquals(96.0, feb.coolantMax!!, 1e-9)
        assertEquals(90.0, feb.coolantMedian!!, 1e-9)
        assertEquals(13.0, feb.voltageMinRunning!!, 1e-9)
        assertEquals(14.0, feb.voltageMedian!!, 1e-9)
        assertEquals(5.0, feb.trimMedians.getValue(LTFT1), 1e-9)
        assertEquals(740.0, feb.idleRpmMedian!!, 1e-9)
        assertEquals(102, feb.coolantN)
        // пустая корзина явно пустая и без чисел
        val apr = r.buckets.single { it.label == "2026-04" }
        assertTrue(apr.empty)
        assertNull(apr.coolantMedian)
    }

    @Test
    fun `change compares the first and the last bucket with enough samples`() {
        val digests = listOf(
            digest(1, at(2026, 2, 10), coolant = 88.0, voltage = 14.2, ltft = 3.0, idle = 720.0),
            digest(2, at(2026, 6, 15), coolant = 92.0, voltage = 13.6, ltft = 9.0, idle = 800.0),
            // в июле мало замеров — для изменения не годится
            digest(3, at(2026, 7, 1), coolant = 120.0, voltage = 11.0, ltft = 25.0, idle = 1000.0, n = 3),
        )
        val r = Trends.build(180, null, now, zone, emptyList(), digests, emptyList())
        val ltft = r.changes.single { it.metric == "ltft1_median" }
        assertEquals("2026-02", ltft.fromLabel)
        assertEquals("2026-06", ltft.toLabel)
        assertEquals(6.0, ltft.delta, 1e-9)
        assertEquals(-0.6, r.changes.single { it.metric == "voltage_median" }.delta, 1e-9)
        // расход: поездок нет ни в одной корзине → «мало данных»
        assertTrue("l_per_100km" in r.insufficient)
    }

    @Test
    fun `dtc events and codes first seen in a bucket`() {
        val dtc = listOf(
            DtcEvent("P0171", DtcKind.STORED, at(2025, 12, 1)), // впервые — до периода
            DtcEvent("P0171", DtcKind.STORED, at(2026, 3, 5)),
            DtcEvent("P0300", DtcKind.PENDING, at(2026, 3, 5)),
            DtcEvent("P0300", DtcKind.STORED, at(2026, 5, 9)),
        )
        val r = Trends.build(180, null, now, zone, emptyList(), emptyList(), dtc)
        val mar = r.buckets.single { it.label == "2026-03" }
        assertEquals(2, mar.dtcEvents)
        assertEquals(listOf("P0300"), mar.newCodes)
        val may = r.buckets.single { it.label == "2026-05" }
        assertEquals(1, may.dtcEvents)
        assertEquals(emptyList<String>(), may.newCodes)
    }
}
