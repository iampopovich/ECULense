package io.github.iampopovich.eculense.feature.car

import io.github.iampopovich.eculense.core.analytics.CarNorms
import io.github.iampopovich.eculense.core.analytics.EngineTraits
import io.github.iampopovich.eculense.core.analytics.Norm
import io.github.iampopovich.eculense.core.analytics.NormKey
import io.github.iampopovich.eculense.core.analytics.NormSource
import io.github.iampopovich.eculense.core.data.CarNormsRepository
import io.github.iampopovich.eculense.core.data.CarNormsView
import io.github.iampopovich.eculense.core.model.CarProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** In-memory user norms per car (the repository's priority rules are tested in :core:data). */
private class MemNorms : CarNormsRepository {
    val user = MutableStateFlow<Map<Long, List<Norm>>>(emptyMap())
    override suspend fun norms(carId: Long) = CarNorms(user.value[carId].orEmpty())
    override fun observe(carId: Long): Flow<CarNormsView> = user.map { CarNormsView(null, CarNorms(it[carId].orEmpty()), emptyList()) }
    override suspend fun setUser(carId: Long, key: NormKey, low: Double?, high: Double?) {
        user.value = user.value + (carId to (user.value[carId].orEmpty().filterNot { it.key == key } + Norm(key, low, high, NormSource.USER)))
    }
    override suspend fun resetUser(carId: Long, key: NormKey) {
        user.value = user.value + (carId to user.value[carId].orEmpty().filterNot { it.key == key })
    }
    override suspend fun saveBaselines(carId: Long, norms: List<Norm>) = Unit
    override suspend fun saveTraits(carId: Long, traits: EngineTraits) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class CarNormsViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `valid input is saved as the user's norm of the active car, invalid input returns its error and saves nothing`() = runTest {
        val cars = MemCars()
        val norms = MemNorms()
        val vm = CarNormsViewModel(cars, norms)
        backgroundScope.launch(UnconfinedTestDispatcher()) { vm.uiState.collect {} }
        assertFalse(vm.uiState.value.loading)
        assertEquals(1L, vm.uiState.value.carId)

        assertEquals(NormInputError.LOW_ABOVE_HIGH, vm.save(NormKey.COOLANT_PLATEAU, "99", "85"))
        assertEquals(NormInputError.OUT_OF_RANGE, vm.save(NormKey.COOLANT_PLATEAU, "300", ""))
        assertEquals(NormInputError.NOT_A_NUMBER, vm.save(NormKey.COOLANT_PLATEAU, "abc", ""))
        assertEquals(NormInputError.EMPTY, vm.save(NormKey.COOLANT_PLATEAU, " ", ""))
        assertTrue(norms.user.value.isEmpty())

        assertNull(vm.save(NormKey.COOLANT_PLATEAU, "85,5", "97"))
        val saved = vm.uiState.value.view!!.norms.effective(NormKey.COOLANT_PLATEAU)!!
        assertEquals(NormSource.USER, saved.source)
        assertEquals(85.5, saved.low!!, 1e-9)

        vm.reset(NormKey.COOLANT_PLATEAU)
        assertTrue(norms.user.value.getValue(1L).isEmpty())
    }

    @Test
    fun `switching the active car shows that car's norms, no car means nothing to edit`() = runTest {
        val cars = MemCars()
        val norms = MemNorms()
        val vm = CarNormsViewModel(cars, norms)
        backgroundScope.launch(UnconfinedTestDispatcher()) { vm.uiState.collect {} }
        vm.save(NormKey.COOLANT_PLATEAU, "85", "97")
        val second = cars.save(CarProfile(name = "Test car"))
        cars.active.value = second
        assertEquals(second, vm.uiState.value.carId)
        assertNull(vm.uiState.value.view!!.norms.stored.firstOrNull { it.source == NormSource.USER })

        cars.all.value = emptyList()
        assertNull(vm.uiState.value.carId)
        assertNull(vm.save(NormKey.COOLANT_PLATEAU, "85", "97")) // nothing to save into, no error shown
        vm.reset(NormKey.COOLANT_PLATEAU)
        assertEquals(setOf(1L), norms.user.value.keys)
    }

    @Test
    fun `the harsh-acceleration threshold is not editable here`() {
        assertFalse(CarNormsViewModel.editable(NormKey.HARSH_ACCEL))
        assertTrue(CarNormsViewModel.editable(NormKey.COOLANT_PLATEAU))
    }
}
