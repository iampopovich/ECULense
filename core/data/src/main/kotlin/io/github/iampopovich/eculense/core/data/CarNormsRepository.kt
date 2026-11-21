package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.analytics.CarNorms
import io.github.iampopovich.eculense.core.analytics.EngineTraits
import io.github.iampopovich.eculense.core.analytics.FuelKind
import io.github.iampopovich.eculense.core.analytics.Norm
import io.github.iampopovich.eculense.core.analytics.NormKey
import io.github.iampopovich.eculense.core.analytics.NormSource
import io.github.iampopovich.eculense.core.analytics.O2Kind
import io.github.iampopovich.eculense.core.analytics.Trait
import io.github.iampopovich.eculense.core.database.CarNormDao
import io.github.iampopovich.eculense.core.database.CarNormEntity
import io.github.iampopovich.eculense.core.model.CarProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** What «Car norms» shows: every norm key with what is stored for it, and the effective norm (incl. generic). */
data class NormRow(val key: NormKey, val effective: Norm?, val baseline: Norm?, val user: Norm?)

data class CarNormsView(val car: CarProfile?, val norms: CarNorms, val rows: List<NormRow>)

/** R-H: per-car norms (Room table car_norms). GENERIC fallbacks are computed, never stored. */
interface CarNormsRepository {
    /** Stored norms + traits of [carId] with the profile applied (USER fuel type wins). */
    suspend fun norms(carId: Long): CarNorms
    fun observe(carId: Long): Flow<CarNormsView>
    suspend fun setUser(carId: Long, key: NormKey, low: Double?, high: Double?)
    suspend fun resetUser(carId: Long, key: NormKey)
    /** Replaces the car's BASELINE rows with [norms] (the learner's output). */
    suspend fun saveBaselines(carId: Long, norms: List<Norm>)
    /** Stores ECU-detected traits (only the non-null ones; a real car's link only — callers check [LiveWriteGate]). */
    suspend fun saveTraits(carId: Long, traits: EngineTraits)
}

internal object NormCodec {
    const val TRAIT_FUEL = "trait.fuel"
    const val TRAIT_O2 = "trait.o2"
    const val TRAIT_BOOST = "trait.forcedInduction"
    const val TRAIT_OBD = "trait.obdStandard"
    const val TRAIT_MAF = "trait.hasMaf"

    fun toEntity(carId: Long, n: Norm) = CarNormEntity(
        carId, n.key.name, n.source.name, n.low, n.high, n.center, n.mad, n.sampleCount, n.confidence, n.updatedAtMs,
    )

    fun norm(e: CarNormEntity): Norm? {
        val key = NormKey.entries.firstOrNull { it.name == e.key } ?: return null
        val source = NormSource.entries.firstOrNull { it.name == e.source } ?: return null
        return Norm(key, e.low, e.high, source, e.center, e.mad, e.sampleCount, e.confidence, e.updatedAtMs)
    }

    fun traits(rows: List<CarNormEntity>): EngineTraits {
        fun <T> trait(key: String, map: (Double) -> T?): Trait<T>? {
            val r = rows.filter { it.key == key && it.center != null }.maxByOrNull { NormSource.entries.firstOrNull { s -> s.name == it.source }?.priority ?: 0 }
                ?: return null
            val v = map(r.center!!) ?: return null
            return Trait(v, NormSource.entries.firstOrNull { it.name == r.source } ?: NormSource.ECU, r.updatedAtMs)
        }
        return EngineTraits(
            fuel = trait(TRAIT_FUEL) { FuelKind.entries.getOrNull(it.toInt()) },
            o2 = trait(TRAIT_O2) { O2Kind.entries.getOrNull(it.toInt()) },
            forcedInduction = trait(TRAIT_BOOST) { it > 0.5 },
            obdStandard = trait(TRAIT_OBD) { it.toInt() },
            hasMaf = trait(TRAIT_MAF) { it > 0.5 },
        )
    }

    fun traitRows(carId: Long, t: EngineTraits): List<CarNormEntity> {
        fun row(key: String, trait: Trait<*>?, value: Double?) = if (trait == null || value == null) null
        else CarNormEntity(carId, key, trait.source.name, null, null, value, null, 0, 1.0, trait.updatedAtMs)
        return listOfNotNull(
            row(TRAIT_FUEL, t.fuel, t.fuel?.value?.ordinal?.toDouble()),
            row(TRAIT_O2, t.o2, t.o2?.value?.ordinal?.toDouble()),
            row(TRAIT_BOOST, t.forcedInduction, t.forcedInduction?.value?.let { if (it) 1.0 else 0.0 }),
            row(TRAIT_OBD, t.obdStandard, t.obdStandard?.value?.toDouble()),
            row(TRAIT_MAF, t.hasMaf, t.hasMaf?.value?.let { if (it) 1.0 else 0.0 }),
        )
    }
}

internal class RoomCarNormsRepository(
    private val dao: CarNormDao,
    private val cars: CarRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : CarNormsRepository {
    private fun build(car: CarProfile?, rows: List<CarNormEntity>): CarNorms =
        CarNorms(rows.mapNotNull(NormCodec::norm), car, NormCodec.traits(rows).withProfile(car))

    override suspend fun norms(carId: Long): CarNorms = build(cars.cars().firstOrNull { it.id == carId }, dao.forCar(carId))

    override fun observe(carId: Long): Flow<CarNormsView> =
        combine(cars.observeCars().map { l -> l.firstOrNull { it.id == carId } }, dao.observeForCar(carId)) { car, rows ->
            val norms = build(car, rows)
            CarNormsView(
                car, norms,
                NormKey.entries.map { k ->
                    NormRow(k, norms.effective(k), norms.baseline(k), norms.stored.firstOrNull { it.key == k && it.source == NormSource.USER })
                },
            )
        }

    override suspend fun setUser(carId: Long, key: NormKey, low: Double?, high: Double?) {
        dao.put(listOf(NormCodec.toEntity(carId, Norm(key, low, high, NormSource.USER, updatedAtMs = clock()))))
    }

    override suspend fun resetUser(carId: Long, key: NormKey) = dao.delete(carId, key.name, NormSource.USER.name)

    override suspend fun saveBaselines(carId: Long, norms: List<Norm>) =
        dao.replaceBaselines(carId, norms.filter { it.source == NormSource.BASELINE }.map { NormCodec.toEntity(carId, it) })

    override suspend fun saveTraits(carId: Long, traits: EngineTraits) {
        val rows = NormCodec.traitRows(carId, traits).filter { it.source == NormSource.ECU.name || it.source == NormSource.BASELINE.name }
        if (rows.isNotEmpty()) dao.put(rows)
    }
}
