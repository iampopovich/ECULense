package io.github.iampopovich.eculense.feature.car

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.analytics.NormKey
import io.github.iampopovich.eculense.core.analytics.NormUnit
import io.github.iampopovich.eculense.core.data.CarNormsRepository
import io.github.iampopovich.eculense.core.data.CarNormsView
import io.github.iampopovich.eculense.core.data.CarRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class CarNormsUiState(val loading: Boolean = true, val carId: Long? = null, val view: CarNormsView? = null)

/** Why an entered norm was refused (typo guard: the number is outside what any engine reports). */
internal enum class NormInputError { NOT_A_NUMBER, EMPTY, OUT_OF_RANGE, LOW_ABOVE_HIGH }

/** R-H «Car norms» of the active car: rows from [CarNormsRepository], user overrides and their reset. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CarNormsViewModel(cars: CarRepository, private val norms: CarNormsRepository) : ViewModel() {
    val uiState: StateFlow<CarNormsUiState> = cars.observeActiveCar()
        .flatMapLatest { car -> if (car == null) flowOf(CarNormsUiState(loading = false)) else norms.observe(car.id).map { CarNormsUiState(false, car.id, it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CarNormsUiState())

    /** Saves a user norm; returns the input error or null when saved. */
    fun save(key: NormKey, lowText: String, highText: String): NormInputError? {
        val carId = uiState.value.carId ?: return null
        val result = parse(key, lowText, highText)
        if (result.isFailure) return (result.exceptionOrNull() as InputException).error
        val (low, high) = result.getOrThrow()
        viewModelScope.launch { norms.setUser(carId, key, low, high) }
        return null
    }

    fun reset(key: NormKey) {
        val carId = uiState.value.carId ?: return
        viewModelScope.launch { norms.resetUser(carId, key) }
    }

    private class InputException(val error: NormInputError) : Exception()

    companion object {
        /** Keys managed elsewhere (the harsh threshold comes from the accelerometer calibration). */
        fun editable(key: NormKey) = key != NormKey.HARSH_ACCEL

        /** Plausible input per unit: guards against typos, not an engine judgement. */
        fun validRange(unit: NormUnit): ClosedFloatingPointRange<Double> = when (unit) {
            NormUnit.CELSIUS -> 40.0..130.0
            NormUnit.RPM -> 0.0..3_000.0
            NormUnit.GRAMS_PER_S -> 0.1..200.0
            NormUnit.VOLTS -> 0.0..16.0
            NormUnit.PERCENT -> -50.0..50.0
            NormUnit.MINUTES -> 0.0..180.0
            NormUnit.G -> 0.05..1.5
        }

        internal fun parse(key: NormKey, lowText: String, highText: String): Result<Pair<Double?, Double?>> {
            fun num(t: String): Result<Double?> {
                val s = t.trim().replace(',', '.')
                if (s.isEmpty()) return Result.success(null)
                val v = s.toDoubleOrNull() ?: return Result.failure(InputException(NormInputError.NOT_A_NUMBER))
                return if (v in validRange(key.unit)) Result.success(v) else Result.failure(InputException(NormInputError.OUT_OF_RANGE))
            }
            val low = num(lowText).getOrElse { return Result.failure(it) }
            val high = num(highText).getOrElse { return Result.failure(it) }
            if (low == null && high == null) return Result.failure(InputException(NormInputError.EMPTY))
            if (low != null && high != null && low > high) return Result.failure(InputException(NormInputError.LOW_ABOVE_HIGH))
            return Result.success(low to high)
        }
    }
}
