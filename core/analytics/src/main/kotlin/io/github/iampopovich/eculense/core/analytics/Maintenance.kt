package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.FuelType
import io.github.iampopovich.eculense.core.model.Gearbox
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import io.github.iampopovich.eculense.core.model.MaintenanceKind
import io.github.iampopovich.eculense.core.model.MaintenanceNote
import io.github.iampopovich.eculense.core.model.OdometerEntry
import io.github.iampopovich.eculense.core.model.OdometerSource
import io.github.iampopovich.eculense.core.model.Trip
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Current mileage. The real odometer can't be read via standard OBD-II (certainly not on the user's K-Line car),
 * so mileage = last manual entry + distance of trips started after it. With the added part it is an estimate («≈»).
 */
data class OdometerEstimate(val km: Double, val baseKm: Long, val baseAtMs: Long, val addedKm: Double) {
    val estimated: Boolean get() = addedKm > 0.0
}

object OdometerEstimator {
    fun estimate(entries: List<OdometerEntry>, trips: List<Trip>): OdometerEstimate? {
        val base = entries.filter { it.source == OdometerSource.MANUAL }.maxByOrNull { it.atMs } ?: return null
        val added = trips.filter { it.startedAtMs >= base.atMs }.sumOf { it.distanceKm }
        return OdometerEstimate(base.km + added, base.km, base.atMs, added)
    }
}

enum class MaintenanceStatus(val rank: Int) { OVERDUE(3), SOON(2), OK(1), UNKNOWN(0) }

/**
 * State of one maintenance task. kmLeft/daysLeft < 0 means overdue by that much. null means this dimension is not computed
 * (interval not set, unknown when last done, or mileage not entered).
 */
data class MaintenanceDue(
    val item: MaintenanceItem,
    val status: MaintenanceStatus,
    val dueKm: Long? = null,
    val kmLeft: Double? = null,
    val dueAtMs: Long? = null,
    val daysLeft: Long? = null,
)

/**
 * «Overdue / soon / OK» (3.9 item 2). Soon means ≤ 10 % of the mileage interval left, or by time
 * ≤ 2 weeks (or ≤ 10 % of the interval if that is larger). Whichever comes first triggers: mileage or date.
 */
object MaintenanceRule {
    const val SOON_FRACTION = 0.10
    const val SOON_MIN_DAYS = 14L
    private const val DAY_MS = 86_400_000L

    fun evaluate(item: MaintenanceItem, odometerKm: Double?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): MaintenanceDue {
        var dueKm: Long? = null
        var kmLeft: Double? = null
        var kmStatus: MaintenanceStatus? = null
        val intervalKm = item.intervalKm
        val lastKm = item.lastDoneKm
        if (intervalKm != null && lastKm != null) {
            dueKm = lastKm + intervalKm
            if (odometerKm != null) {
                kmLeft = dueKm - odometerKm
                kmStatus = when {
                    kmLeft <= 0.0 -> MaintenanceStatus.OVERDUE
                    kmLeft <= intervalKm * SOON_FRACTION -> MaintenanceStatus.SOON
                    else -> MaintenanceStatus.OK
                }
            }
        }
        var dueAt: Long? = null
        var daysLeft: Long? = null
        var timeStatus: MaintenanceStatus? = null
        val months = item.intervalMonths
        val lastAt = item.lastDoneAtMs
        if (months != null && lastAt != null) {
            val due = Instant.ofEpochMilli(lastAt).atZone(zone).plusMonths(months.toLong())
            dueAt = due.toInstant().toEpochMilli()
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            daysLeft = ChronoUnit.DAYS.between(today, due.toLocalDate())
            val intervalDays = (dueAt - lastAt) / DAY_MS
            val soonDays = maxOf(SOON_MIN_DAYS, (intervalDays * SOON_FRACTION).toLong())
            timeStatus = when {
                daysLeft <= 0 -> MaintenanceStatus.OVERDUE
                daysLeft <= soonDays -> MaintenanceStatus.SOON
                else -> MaintenanceStatus.OK
            }
        }
        val status = listOfNotNull(kmStatus, timeStatus).maxByOrNull { it.rank } ?: MaintenanceStatus.UNKNOWN
        return MaintenanceDue(item, status, dueKm, kmLeft, dueAt, daysLeft)
    }

    /** All tasks: overdue first, then «soon», «OK», «unknown»; within each, in insertion order (template: oil first). */
    fun evaluateAll(items: List<MaintenanceItem>, odometerKm: Double?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<MaintenanceDue> =
        items.map { evaluate(it, odometerKm, nowMs, zone) }.sortedWith(compareByDescending<MaintenanceDue> { it.status.rank }.thenBy { it.item.id })

    /** What is due: overdue and «soon». */
    fun due(items: List<MaintenanceItem>, odometerKm: Double?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<MaintenanceDue> =
        evaluateAll(items, odometerKm, nowMs, zone).filter { it.status == MaintenanceStatus.OVERDUE || it.status == MaintenanceStatus.SOON }
}

/**
 * Typical task list for a petrol car (3.9 item 2). The user edits it on the «Maintenance» screen;
 * intervals from the car profile (Car Scanner import/manual entry) take precedence over the template. Reference values come
 * from typical service schedules of mass-market petrol models; exact values are in the service book.
 * Items carry a [MaintenanceKind] and a blank title: the UI shows the localized name (see [MaintenanceItem]).
 */
object MaintenanceTemplate {
    fun forCar(car: CarProfile): List<MaintenanceItem> {
        val s = car.service
        val diesel = car.fuelType == FuelType.DIESEL
        fun item(kind: MaintenanceKind, km: Int?, months: Int?, note: MaintenanceNote? = null) =
            MaintenanceItem(carId = car.id, title = "", intervalKm = km, intervalMonths = months, kind = kind, noteKind = note)
        return buildList {
            add(item(MaintenanceKind.OIL_AND_FILTER, s.oilKm ?: 10_000, 12, MaintenanceNote.OIL_SEVERE_CONDITIONS))
            add(item(MaintenanceKind.AIR_FILTER, s.airFilterKm ?: 20_000, 24))
            if (!diesel) add(item(MaintenanceKind.SPARK_PLUGS, s.sparkPlugsKm ?: 30_000, null, MaintenanceNote.SPARK_PLUGS_TYPE))
            s.fuelFilterKm?.let { add(item(MaintenanceKind.FUEL_FILTER, it, null)) }
            add(item(MaintenanceKind.COOLANT, null, 48))
            add(item(MaintenanceKind.BRAKE_FLUID, null, 24))
            add(item(MaintenanceKind.TIMING_BELT, null, null, MaintenanceNote.TIMING_INTERVAL))
            when (car.gearbox) {
                Gearbox.MANUAL -> Unit
                Gearbox.CVT -> add(item(MaintenanceKind.CVT_OIL, 60_000, null))
                else -> add(item(MaintenanceKind.AUTOMATIC_TRANSMISSION_OIL, 60_000, null))
            }
        }
    }
}
