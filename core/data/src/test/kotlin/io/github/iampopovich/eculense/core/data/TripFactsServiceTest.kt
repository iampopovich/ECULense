package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.analytics.AmbientSource
import io.github.iampopovich.eculense.core.analytics.SensorIds
import io.github.iampopovich.eculense.core.analytics.TripFacts
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripFactsServiceTest {
    private fun trip(id: Long, session: Long?) = Trip(
        id = id, carId = 1, startedAtMs = 0, endedAtMs = 600_000, distanceKm = 10.0, fuelL = 1.0, fuelPricePerL = null,
        drivingMs = 600_000, standingMs = 0, source = TripSource.LIVE, externalId = session?.let { "session:$it" },
    )

    /** 10 min at 60 km/h, warm, IAT 12 °C. */
    private val cruise = (0 until 600).flatMap { i ->
        val t = i * 1_000L
        listOf(Triple(SensorIds.COOLANT, 90.0, t), Triple(SensorIds.SPEED, 60.0, t + 1), Triple(SensorIds.IAT, 12.0, t + 2))
    }

    @Test
    fun `facts are computed once per trip and cached, trips without a recording get none`() = runTest {
        var reads = 0
        val store = InMemoryTripFactsStore()
        val service = TripFactsService(FakeTrips(), { _, _ -> reads++; cruise }, store)
        val first = service.ensure(listOf(trip(1, 5), trip(2, 5), trip(3, null)))
        assertEquals(1, reads) // both trips share recording 5
        assertEquals(100, first[1]!!.styleScore)
        assertEquals(12.0, first[2]!!.ambientC!!, 0.0)
        assertEquals(AmbientSource.INTAKE_AIR, first[2]!!.ambientSource)
        assertEquals(TripFacts.NONE, first[3])
        service.ensure(listOf(trip(1, 5), trip(2, 5), trip(3, null)))
        assertEquals(1, reads)
        assertEquals(3, service.cached().size)
    }

    @Test
    fun `datastore encoding round-trips and skips broken lines`() {
        val m = mapOf(1L to TripFacts(87, -3.5, AmbientSource.AMBIENT_PID), 2L to TripFacts.NONE, 3L to TripFacts(null, 12.0, AmbientSource.INTAKE_AIR))
        assertEquals(m, DataStoreTripFactsStore.decode(DataStoreTripFactsStore.encode(m)))
        val broken = DataStoreTripFactsStore.decode("x;1;2;3\n4;90;;\n")
        assertEquals(setOf(4L), broken.keys)
        assertNull(broken[4]!!.ambientC)
    }
}
