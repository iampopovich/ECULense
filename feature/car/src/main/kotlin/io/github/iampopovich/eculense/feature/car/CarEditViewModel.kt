package io.github.iampopovich.eculense.feature.car

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.model.CarProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

internal data class CarEditUiState(
    val loading: Boolean = true,
    val form: CarForm = CarForm(),
    val errors: Map<CarField, String> = emptyMap(),
    /** Удалять можно, только если машина не единственная. */
    val canDelete: Boolean = false,
    val isNew: Boolean = true,
    /** Сохранено/удалено — экран закрывается. */
    val done: Boolean = false,
)

internal class CarEditViewModel(
    private val carId: Long,
    private val cars: CarRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(CarEditUiState())
    val state: StateFlow<CarEditUiState> = _state.asStateFlow()
    private var original: CarProfile? = null

    private val currentYear get() = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).year

    init {
        viewModelScope.launch {
            val all = cars.cars()
            original = all.firstOrNull { it.id == carId }
            val form = original?.let(CarForm::from) ?: CarForm()
            _state.value = CarEditUiState(
                loading = false,
                form = form,
                errors = form.errors(currentYear),
                canDelete = original != null && all.size > 1,
                isNew = original == null,
            )
        }
    }

    fun edit(transform: (CarForm) -> CarForm) {
        _state.update { s ->
            val form = transform(s.form)
            s.copy(form = form, errors = form.errors(currentYear))
        }
    }

    fun save() {
        val profile = _state.value.form.toProfile(original, clock(), currentYear) ?: return
        viewModelScope.launch {
            val id = cars.save(profile)
            // новая машина становится активной: пользователь создаёт её, чтобы ездить на ней
            if (original == null) cars.setActive(id)
            _state.update { it.copy(done = true) }
        }
    }

    fun delete() {
        val car = original ?: return
        viewModelScope.launch {
            if (cars.delete(car.id)) _state.update { it.copy(done = true) }
        }
    }
}
