package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.FuelType
import io.github.iampopovich.eculense.core.model.Gearbox
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import io.github.iampopovich.eculense.core.model.OdometerEntry
import io.github.iampopovich.eculense.core.model.OdometerSource
import io.github.iampopovich.eculense.core.model.Trip
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Текущий пробег. Настоящий одометр по OBD-II стандартно не читается (на K-Line машине пользователя — точно),
 * поэтому пробег = последний ручной ввод + путь поездок, начавшихся после него. С добавкой — это оценка («≈»).
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
 * Состояние одной работы ТО. kmLeft/daysLeft < 0 — на столько просрочено. null — это измерение не считается
 * (интервал не задан, неизвестно, когда делали, или пробег не введён).
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
 * «Просрочено / скоро / в норме» (3.9 п.2). Скоро — осталось ≤ 10 % интервала по пробегу, или по времени
 * ≤ 2 недель (или ≤ 10 % интервала, если это больше). Срабатывает то, что наступит раньше: пробег или дата.
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

    /** Все работы: сначала просроченные, потом «скоро», «в норме», «неизвестно»; внутри — в порядке добавления (шаблон: масло первым). */
    fun evaluateAll(items: List<MaintenanceItem>, odometerKm: Double?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<MaintenanceDue> =
        items.map { evaluate(it, odometerKm, nowMs, zone) }.sortedWith(compareByDescending<MaintenanceDue> { it.status.rank }.thenBy { it.item.id })

    /** Что пора делать: просроченное и «скоро». */
    fun due(items: List<MaintenanceItem>, odometerKm: Double?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<MaintenanceDue> =
        evaluateAll(items, odometerKm, nowMs, zone).filter { it.status == MaintenanceStatus.OVERDUE || it.status == MaintenanceStatus.SOON }
}

/**
 * Типовой список работ для бензиновой машины (3.9 п.2). Пользователь правит его на экране «Обслуживание»;
 * интервалы из профиля машины (импорт Car Scanner/ручной ввод) важнее шаблона. Источник ориентиров — типовые
 * регламенты массовых бензиновых моделей; точные значения — в сервисной книжке.
 */
object MaintenanceTemplate {
    fun forCar(car: CarProfile): List<MaintenanceItem> {
        val s = car.service
        val diesel = car.fuelType == FuelType.DIESEL
        fun item(title: String, km: Int?, months: Int?, note: String? = null) =
            MaintenanceItem(carId = car.id, title = title, intervalKm = km, intervalMonths = months, note = note)
        return buildList {
            add(item("Масло и масляный фильтр двигателя", s.oilKm ?: 10_000, 12, "В тяжёлых условиях (пробки, короткие поездки) — чаще"))
            add(item("Воздушный фильтр", s.airFilterKm ?: 20_000, 24))
            if (!diesel) add(item("Свечи зажигания", s.sparkPlugsKm ?: 30_000, null, "30 000–60 000 км в зависимости от типа свечей (никель/иридий)"))
            s.fuelFilterKm?.let { add(item("Топливный фильтр", it, null)) }
            add(item("Охлаждающая жидкость", null, 48))
            add(item("Тормозная жидкость", null, 24))
            add(item("Ремень/цепь ГРМ", null, null, "Интервал — по регламенту двигателя: укажите его"))
            when (car.gearbox) {
                Gearbox.MANUAL -> Unit
                Gearbox.CVT -> add(item("Масло вариатора", 60_000, null))
                else -> add(item("Масло АКПП", 60_000, null))
            }
        }
    }
}
