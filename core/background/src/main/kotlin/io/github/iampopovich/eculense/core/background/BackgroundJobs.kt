package io.github.iampopovich.eculense.core.background

import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Ключи уже показанных уведомлений (переживают перезапуск). */
interface AlertLedger {
    suspend fun load(): Set<String>
    suspend fun save(keys: Set<String>)
}

/** Поездки по записям без поездки (обёртка над RecordingTrips — для подмены в тестах воркеров). */
fun interface TripBackfill {
    suspend fun run(): Int
}

interface Notifier {
    fun findings(carName: String, items: List<Finding>)
    fun maintenance(carName: String, items: List<MaintenanceDue>)
}

/**
 * Фоновый анализ после поездки/импорта: поездки по новым записям → пересчёт находок каждой машины → уведомление
 * о новых WARNING/CRITICAL. Без сети и без ИИ. Зависимости — функциями, чтобы тестироваться на JVM.
 */
class PostTripAnalysis(
    private val backfill: suspend () -> Int,
    private val cars: suspend () -> List<Pair<Long, String>>,
    private val findings: suspend (carId: Long) -> List<Finding>,
    private val recompute: suspend (carId: Long) -> List<Finding>,
    private val ledger: AlertLedger,
    private val notifier: Notifier,
) {
    data class Result(val tripsAdded: Int, val notified: Int)

    suspend fun run(): Result = lock.withLock {
        val added = runCatching { backfill() }.getOrDefault(0)
        var notified = ledger.load()
        var count = 0
        for ((carId, name) in cars()) {
            val before = findings(carId)
            val after = recompute(carId)
            val d = FindingAlerts.decide(carId, before, after, notified)
            if (d.toNotify.isNotEmpty()) notifier.findings(name, d.toNotify)
            count += d.toNotify.size
            notified = d.notified
        }
        ledger.save(notified)
        Result(added, count)
    }

    private companion object {
        val lock = Mutex()
    }
}

/** Ежедневная проверка ТО: «пора ТО» по каждой машине один раз на состояние работы. */
class MaintenanceCheck(
    private val cars: suspend () -> List<Pair<Long, String>>,
    private val due: suspend (carId: Long) -> List<MaintenanceDue>,
    private val ledger: AlertLedger,
    private val notifier: Notifier,
) {
    /** Сколько работ попало в уведомления. */
    suspend fun run(): Int {
        var notified = ledger.load()
        var count = 0
        for ((carId, name) in cars()) {
            val d = MaintenanceAlerts.decide(carId, due(carId), notified)
            if (d.toNotify.isNotEmpty()) notifier.maintenance(name, d.toNotify)
            count += d.toNotify.size
            notified = d.notified
        }
        ledger.save(notified)
        return count
    }
}
