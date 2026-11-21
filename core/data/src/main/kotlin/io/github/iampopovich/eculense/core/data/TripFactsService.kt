package io.github.iampopovich.eculense.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.iampopovich.eculense.core.analytics.AmbientSource
import io.github.iampopovich.eculense.core.analytics.StyleThresholds
import io.github.iampopovich.eculense.core.analytics.TripFacts
import io.github.iampopovich.eculense.core.analytics.TripFactsRules
import io.github.iampopovich.eculense.core.model.Trip
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cache of per-trip [TripFacts] (trip id → facts). */
interface TripFactsStore {
    suspend fun all(): Map<Long, TripFacts>
    suspend fun putAll(facts: Map<Long, TripFacts>)
}

class InMemoryTripFactsStore : TripFactsStore {
    private val map = HashMap<Long, TripFacts>()
    override suspend fun all(): Map<Long, TripFacts> = HashMap(map)
    override suspend fun putAll(facts: Map<Long, TripFacts>) {
        map += facts
    }
}

/**
 * DataStore cache (file trip_facts) instead of a column on the trips table: the facts are derived and recomputable,
 * so they need no Room migration on the users' databases and are not part of the .eculense backup; one compact
 * string of ~20 bytes per trip («id;score;ambient;source» lines), a few hundred trips = a few KB. Entries of deleted
 * trips are harmless (looked up by id, never shown). [VERSION] in the key: changing the rules recomputes everything.
 */
internal class DataStoreTripFactsStore(private val store: DataStore<Preferences>) : TripFactsStore {
    override suspend fun all(): Map<Long, TripFacts> = decode(store.data.first()[KEY].orEmpty())

    override suspend fun putAll(facts: Map<Long, TripFacts>) {
        store.edit { p -> p[KEY] = encode(decode(p[KEY].orEmpty()) + facts) }
    }

    internal companion object {
        const val VERSION = 2 // 2: speed spikes filtered before the style score
        private val KEY = stringPreferencesKey("facts_v$VERSION")

        fun encode(m: Map<Long, TripFacts>): String = m.entries.sortedBy { it.key }.joinToString("\n") { (id, f) ->
            "$id;${f.styleScore ?: ""};${f.ambientC ?: ""};${f.ambientSource?.name ?: ""}"
        }

        fun decode(text: String): Map<Long, TripFacts> = text.lineSequence().mapNotNull { line ->
            val p = line.split(';')
            if (p.size != 4) return@mapNotNull null
            val id = p[0].toLongOrNull() ?: return@mapNotNull null
            id to TripFacts(p[1].toIntOrNull(), p[2].toDoubleOrNull(), AmbientSource.entries.firstOrNull { it.name == p[3] })
        }.toMap()
    }
}

/**
 * R2.8: computes [TripFacts] once per trip from its recording (style score, ambient temperature) and caches them,
 * so the Stats trends never re-read recordings. Trips without a recording get [TripFacts.NONE] and are not retried.
 */
class TripFactsService internal constructor(
    private val trips: TripRepository,
    private val points: suspend (sessionId: Long, sensors: Collection<String>) -> List<Triple<String, Double, Long>>,
    private val store: TripFactsStore,
    private val harshMs2: suspend (carId: Long) -> Double? = { null },
) {
    private val mutex = Mutex()

    /** Cached facts, without computing anything. */
    suspend fun cached(): Map<Long, TripFacts> = store.all()

    /**
     * Facts for [list]: missing ones are computed newest first and saved every [batch] trips; [onBatch] gets the
     * whole known map after each save so the screen fills in progressively.
     */
    suspend fun ensure(list: List<Trip>, batch: Int = 20, onBatch: suspend (Map<Long, TripFacts>) -> Unit = {}): Map<Long, TripFacts> = mutex.withLock {
        val known = HashMap(store.all())
        val missing = list.filter { it.id != 0L && it.id !in known }.sortedByDescending { it.startedAtMs }
        if (missing.isEmpty()) return known
        val pending = HashMap<Long, TripFacts>()
        var lastSession: Long? = null
        var lastPoints: List<Triple<String, Double, Long>> = emptyList()
        for (trip in missing) {
            val session = runCatching { trips.sessionOf(trip) }.getOrNull()
            val facts = if (session == null) {
                TripFacts.NONE
            } else {
                if (session != lastSession) {
                    lastPoints = runCatching { points(session, TripFactsRules.SENSORS) }.getOrDefault(emptyList())
                    lastSession = session
                }
                val span = lastPoints.filter { it.third in trip.startedAtMs..trip.endedAtMs }.ifEmpty { lastPoints }
                val harsh = runCatching { harshMs2(trip.carId) }.getOrNull() ?: StyleThresholds.HARSH_ACCEL_MS2
                TripFactsRules.compute(span, harsh)
            }
            pending[trip.id] = facts
            if (pending.size >= batch) {
                store.putAll(pending)
                known += pending
                pending.clear()
                onBatch(HashMap(known))
            }
        }
        if (pending.isNotEmpty()) {
            store.putAll(pending)
            known += pending
            onBatch(HashMap(known))
        }
        known
    }
}
