package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.database.ReadingPoint
import io.github.iampopovich.eculense.core.database.TripCandidateRow
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.FuelMethod
import io.github.iampopovich.eculense.core.model.FuelType
import io.github.iampopovich.eculense.core.model.TripSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTripsTest {
    private val cars = FakeCars().apply { all.value = listOf(CarProfile(id = 1, name = "Тестовая", fuelPrice = 50.0)) }
    private val trips = FakeTrips()
    private val points = mutableMapOf<Long, List<ReadingPoint>>()
    private val candidates = mutableListOf<TripCandidateRow>()
    private val builder = RecordingTrips(candidates = { candidates.toList() }, points = { id, _ -> points[id].orEmpty() }, cars = cars, trips = trips)

    private fun speed(fromSec: Int, toSec: Int, kmh: Double) = (fromSec..toSec).map { ReadingPoint("01.0D", kmh, it * 1000L) }
    private fun maf(fromSec: Int, toSec: Int, gs: Double) = (fromSec..toSec).map { ReadingPoint("01.10", gs, it * 1000L) }

    @Test
    fun `live recording becomes a trip with distance fuel and times`() = runTest {
        candidates += TripCandidateRow(5, 0, 100_000, "LIVE", 1)
        points[5] = (speed(0, 100, 36.0) + maf(0, 100, 14.7 * 0.745)).sortedBy { it.timestampMs }

        assertEquals(1, builder.backfill())

        val t = trips.all.value.single()
        assertEquals("session:5", t.externalId)
        assertEquals(TripSource.LIVE, t.source)
        assertEquals(1.0, t.distanceKm, 1e-9)
        // 14.7·0.745 g/s of air = 1 ml/s of gasoline → 100 s = 0.1 L
        assertEquals(0.1, t.fuelL, 1e-9)
        assertEquals(FuelMethod.MAF_ESTIMATE, t.fuelMethod)
        assertEquals(50.0, t.fuelPricePerL!!, 0.0)
        assertEquals(100_000L, t.drivingMs)
        assertEquals(1L, t.carId)
    }

    @Test
    fun `repeated backfill does not duplicate trips`() = runTest {
        candidates += TripCandidateRow(5, 0, 100_000, "LIVE", 1)
        points[5] = speed(0, 100, 36.0)
        builder.backfill()
        assertEquals(0, builder.backfill())
        assertEquals(1, trips.all.value.size)
    }

    @Test
    fun `recordings without movement or without speed are not trips`() = runTest {
        candidates += TripCandidateRow(1, 0, 100_000, "LIVE", 1)
        candidates += TripCandidateRow(2, 0, 100_000, "IMPORT_CARSCANNER", 1)
        points[1] = speed(0, 100, 0.0)
        points[2] = maf(0, 100, 5.0)
        assertEquals(0, builder.backfill())
        assertTrue(trips.all.value.isEmpty())
    }

    @Test
    fun `imported recording without a car scanner trip gets a computed trip`() = runTest {
        candidates += TripCandidateRow(9, 0, 100_000, "IMPORT_CARSCANNER", 1)
        points[9] = speed(0, 100, 36.0)
        builder.backfill()
        assertEquals(TripSource.LIVE, trips.all.value.single().source)
        assertEquals(FuelMethod.NOT_AVAILABLE, trips.all.value.single().fuelMethod)
    }

    @Test
    fun `diesel car gets no maf fuel estimate`() = runTest {
        cars.all.value = listOf(CarProfile(id = 1, name = "Тестовая", fuelType = FuelType.DIESEL))
        candidates += TripCandidateRow(5, 0, 100_000, "LIVE", 1)
        points[5] = (speed(0, 100, 36.0) + maf(0, 100, 20.0)).sortedBy { it.timestampMs }
        builder.backfill()
        val t = trips.all.value.single()
        assertEquals(FuelMethod.NOT_AVAILABLE, t.fuelMethod)
        assertEquals(0.0, t.fuelL, 0.0)
    }
}
