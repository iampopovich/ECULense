package io.github.iampopovich.eculense.core.data

import android.util.Log
import io.github.iampopovich.eculense.core.analytics.SensorIds
import io.github.iampopovich.eculense.core.analytics.TripCalculator
import io.github.iampopovich.eculense.core.database.ReadingPoint
import io.github.iampopovich.eculense.core.database.TripCandidateRow
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Trips from our own recordings (3.9 item 1): for each finished recording without a trip, Trip(source = LIVE,
 * externalId = "session:<id>"). Imported recordings covered by a Car Scanner trip are filtered out in SQL
 * ([io.github.iampopovich.eculense.core.database.TripDao.sessionsWithoutTrip]). Re-running is safe:
 * the unique externalId prevents duplicates. A recording without movement (distance < [MIN_DISTANCE_KM]) is not a trip.
 */
class RecordingTrips internal constructor(
    private val candidates: suspend () -> List<TripCandidateRow>,
    private val points: suspend (sessionId: Long, sensors: Collection<String>) -> List<ReadingPoint>,
    private val cars: CarRepository,
    private val trips: TripRepository,
    private val log: (String) -> Unit = { msg -> runCatching { Log.i(TAG, msg) } },
) {
    private val mutex = Mutex()

    /** Number of trips added. */
    suspend fun backfill(): Int = mutex.withLock {
        val started = System.nanoTime()
        val list = candidates()
        if (list.isEmpty()) return 0
        val byId = cars.cars().associateBy { it.id }
        val built = list.mapNotNull { s ->
            val car = byId[s.carId]
            val metrics = TripCalculator.compute(points(s.id, SENSORS).map { Triple(it.sensorId, it.value, it.timestampMs) }, car?.fuelType)
                ?.takeIf { it.distanceKm >= MIN_DISTANCE_KM } ?: return@mapNotNull null
            Trip(
                carId = s.carId, startedAtMs = metrics.startedAtMs, endedAtMs = metrics.endedAtMs, distanceKm = metrics.distanceKm,
                fuelL = metrics.fuelL, fuelPricePerL = car?.fuelPrice, drivingMs = metrics.drivingMs, standingMs = metrics.standingMs,
                source = TripSource.LIVE, externalId = externalId(s.id), fuelMethod = metrics.fuelMethod,
            )
        }
        val added = if (built.isEmpty()) 0 else trips.addAll(built)
        log("backfill candidates=${list.size} added=$added ms=${(System.nanoTime() - started) / 1_000_000}")
        added
    }

    companion object {
        const val TAG = "EcuTrips"
        /** 10 m: anything less is parking/warm-up, not a trip. */
        const val MIN_DISTANCE_KM = 0.01
        val SENSORS = listOf(SensorIds.SPEED, SensorIds.MAF, SensorIds.FUEL_RATE)
        fun externalId(sessionId: Long) = "session:$sessionId"
    }
}
