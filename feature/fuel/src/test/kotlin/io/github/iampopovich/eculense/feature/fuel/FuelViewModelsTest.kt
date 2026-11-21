package io.github.iampopovich.eculense.feature.fuel

import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.ExpenseRepository
import io.github.iampopovich.eculense.core.data.FuelImportResult
import io.github.iampopovich.eculense.core.data.FuelPrefs
import io.github.iampopovich.eculense.core.data.FuelRepository
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.Expense
import io.github.iampopovich.eculense.core.model.ExpenseCategory
import io.github.iampopovich.eculense.core.model.FuelFillUp
import io.github.iampopovich.eculense.core.model.StatsPeriod
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.ui.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import java.time.LocalDate
import java.time.ZoneOffset

private class Cars(car: CarProfile?) : CarRepository {
    val active = MutableStateFlow(car)
    override fun observeCars() = active.map { listOfNotNull(it) }
    override fun observeActiveCar(): Flow<CarProfile?> = active
    override suspend fun activeCarId() = active.value?.id
    override suspend fun cars() = listOfNotNull(active.value)
    override suspend fun byExternalId(externalId: String) = null
    override suspend fun save(car: CarProfile) = car.id
    override suspend fun delete(id: Long) = false
    override suspend fun setActive(id: Long) = Unit
}

private class MemFuel : FuelRepository {
    val rows = MutableStateFlow<List<FuelFillUp>>(emptyList())
    val imported = mutableListOf<List<FuelFillUp>>()
    private var next = 1L
    override fun observeFillUps(carId: Long) = rows.map { l -> l.filter { it.carId == carId } }
    override suspend fun fillUps(carId: Long) = rows.value.filter { it.carId == carId }
    override suspend fun save(fillUp: FuelFillUp): Long = next++.also { rows.value = rows.value + fillUp.copy(id = it) }
    override suspend fun delete(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
    override suspend fun import(rows: List<FuelFillUp>): FuelImportResult {
        imported += rows
        val known = this.rows.value.mapNotNull { it.externalId }.toSet()
        val fresh = rows.filter { it.externalId !in known }
        fresh.forEach { save(it) }
        return FuelImportResult(fresh.size, rows.size - fresh.size)
    }
}

private class MemTrips(vararg trips: Trip) : TripRepository {
    val all = MutableStateFlow(trips.toList())
    override fun observeTrips(carId: Long) = all.map { l -> l.filter { it.carId == carId } }
    override suspend fun addAll(trips: List<Trip>) = 0
}

private class MemPrefs : FuelPrefs {
    val on = MutableStateFlow(emptyMap<Long, Boolean>())
    override fun mafCorrection(carId: Long) = on.map { it[carId] ?: false }
    override suspend fun setMafCorrection(carId: Long, on: Boolean) { this.on.value = this.on.value + (carId to on) }
}

private class MemExpenses : ExpenseRepository {
    val rows = MutableStateFlow<List<Expense>>(emptyList())
    private var next = 1L
    override fun observeExpenses(carId: Long) = rows.map { l -> l.filter { it.carId == carId } }
    override suspend fun expenses(carId: Long) = rows.value.filter { it.carId == carId }
    override suspend fun save(expense: Expense): Long = next++.also { rows.value = rows.value + expense.copy(id = it) }
    override suspend fun delete(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FuelViewModelsTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val zone = ZoneOffset.UTC
    private val now = LocalDate.parse("2026-03-10").atTime(15, 0).toInstant(zone).toEpochMilli()
    private val day = 86_400_000L
    private val car = CarProfile(id = 1, name = "Тестовая", brand = "Kia", model = "Ceed", year = 2019, fuelPrice = 55.5)

    private fun CoroutineScope.keep(flow: StateFlow<*>) = launch(UnconfinedTestDispatcher()) { flow.collect {} }

    private fun fuelVm(cars: Cars = Cars(car), fuel: MemFuel = MemFuel(), prefs: MemPrefs = MemPrefs()) =
        FuelLogViewModel(cars, fuel, MemTrips(), prefs, maintenance = null, clock = { now }, zone = { zone })

    @Test
    fun `fuel log shows the active car's fill-ups, saves valid forms only, deletes and toggles the correction per car`() = runTest {
        val fuel = MemFuel()
        val prefs = MemPrefs()
        val vm = fuelVm(fuel = fuel, prefs = prefs)
        backgroundScope.keep(vm.state)
        assertFalse(vm.state.value.loading)
        assertEquals(car, vm.state.value.car)
        assertTrue(vm.state.value.fillUps.isEmpty())

        val form = vm.newForm()
        assertEquals("10.03.2026", form.date)
        assertEquals("55.5", form.price) // the car's fuel price prefills the form
        assertFalse(vm.save(form)) // only one of the three numbers
        assertTrue(vm.save(form.copy(litres = "40")))
        assertEquals(1, vm.state.value.fillUps.size)
        assertEquals(2_220.0, vm.state.value.fillUps.single().total!!, 1e-9)

        vm.setCorrection(true)
        assertEquals(mapOf(1L to true), prefs.on.value)
        assertTrue(vm.state.value.insights!!.correctionOn)

        vm.delete(vm.state.value.fillUps.single().id)
        assertTrue(vm.state.value.fillUps.isEmpty())
    }

    @Test
    fun `without an active car nothing is saved and an import fails with a message`() = runTest {
        val fuel = MemFuel()
        val vm = fuelVm(cars = Cars(null), fuel = fuel)
        backgroundScope.keep(vm.state)
        assertNull(vm.state.value.insights)
        assertFalse(vm.save(FillUpForm(date = "10.03.2026", litres = "40", price = "50")))
        vm.importCsv("Date;Odometer;Litres;Price;Full\n2026-02-01;20 000;35,5;52,0;yes\n")
        assertEquals(UiText.Res(R.string.fuel_import_failed), vm.state.value.message)
        assertTrue(fuel.imported.isEmpty())
    }

    @Test
    fun `CSV import reports unreadable files, unknown layouts, the result counts and a too large file`() = runTest {
        val fuel = MemFuel()
        val vm = fuelVm(fuel = fuel)
        backgroundScope.keep(vm.state)
        vm.importCsv(null) // the picker could not read the file
        assertEquals(UiText.Res(R.string.fuel_import_failed), vm.state.value.message)
        vm.messageShown()
        assertNull(vm.state.value.message)

        vm.importCsv("a,b\n1,2")
        assertEquals(UiText.Res(R.string.fuel_import_unknown), vm.state.value.message)
        assertTrue(fuel.imported.isEmpty())

        val csv = "Date;Odometer;Litres;Price;Full\n2026-02-01;20 000;35,5;52,0;yes\n02.02.2026;20 400;10;;no\nbad;row;x;;\n"
        vm.importCsv(csv)
        assertEquals(UiText.Res(R.string.fuel_import_done, listOf(2, 0, 1)), vm.state.value.message)
        vm.importCsv(csv) // same file again: nothing new
        assertEquals(UiText.Res(R.string.fuel_import_done, listOf(0, 2, 1)), vm.state.value.message)
        assertEquals(2, vm.state.value.fillUps.size)

        vm.importTooLarge()
        assertEquals(UiText.Res(R.string.fuel_import_too_large, listOf(CsvImportLimit.MAX_MB)), vm.state.value.message)
    }

    @Test
    fun `expenses filter by period, sum with the fuel log and save valid forms only`() = runTest {
        val expenses = MemExpenses()
        val fuel = MemFuel()
        fuel.save(FuelFillUp(carId = 1, atMs = now - 5 * day, odometerKm = 20_000, litres = 40.0, pricePerL = 50.0, total = 2_000.0))
        fuel.save(FuelFillUp(carId = 1, atMs = now - 2 * day, odometerKm = 20_500, litres = 30.0, pricePerL = 50.0, total = 1_500.0))
        expenses.save(Expense(carId = 1, atMs = now - 3 * day, category = ExpenseCategory.WASH, amount = 500.0))
        expenses.save(Expense(carId = 1, atMs = now - 100 * day, category = ExpenseCategory.REPAIR, amount = 10_000.0))
        expenses.save(Expense(carId = 2, atMs = now - 1 * day, category = ExpenseCategory.WASH, amount = 999.0)) // other car
        val vm = ExpensesViewModel(Cars(car), expenses, fuel, MemTrips(), maintenance = null, clock = { now }, zone = { zone })
        backgroundScope.keep(vm.state)

        assertEquals(StatsPeriod.ALL, vm.state.value.period)
        assertEquals(2, vm.state.value.expenses.size)
        vm.selectPeriod(StatsPeriod.MONTH)
        assertEquals(listOf(500.0), vm.state.value.expenses.map { it.amount })
        assertTrue(vm.state.value.summary != null)
        assertTrue(vm.state.value.works.isEmpty()) // no maintenance module

        val form = vm.newForm()
        assertFalse(vm.save(form)) // no amount
        assertTrue(vm.save(form.copy(amount = "1 200")))
        assertEquals(2, vm.state.value.expenses.size)
        vm.delete(vm.state.value.expenses.first { it.amount == 1_200.0 }.id)
        assertEquals(listOf(500.0), vm.state.value.expenses.map { it.amount })
    }

    @Test
    fun `expenses without an active car stay empty and refuse to save`() = runTest {
        val vm = ExpensesViewModel(Cars(null), MemExpenses(), MemFuel(), MemTrips(), maintenance = null, clock = { now }, zone = { zone })
        backgroundScope.keep(vm.state)
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.expenses.isEmpty())
        assertFalse(vm.save(vm.newForm().copy(amount = "100")))
    }
}
