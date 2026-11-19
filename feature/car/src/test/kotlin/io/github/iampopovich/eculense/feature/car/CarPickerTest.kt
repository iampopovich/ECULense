package io.github.iampopovich.eculense.feature.car

import io.github.iampopovich.eculense.core.datapacks.DataPackService
import io.github.iampopovich.eculense.core.datapacks.PackCatalog
import io.github.iampopovich.eculense.core.datapacks.PackState
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.DriveType
import io.github.iampopovich.eculense.core.model.FuelType
import io.github.iampopovich.eculense.core.model.Gearbox
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import io.github.iampopovich.eculense.core.vehicledb.VehicleEngine
import io.github.iampopovich.eculense.core.vehicledb.VehicleGeneration
import io.github.iampopovich.eculense.core.vehicledb.VehicleMake
import io.github.iampopovich.eculense.core.vehicledb.VehicleModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.iampopovich.eculense.core.ui.UiText

/** In-memory reference database: reference G6CU variant (values typed by hand). */
private class MemCatalog(var available: Boolean = true) : VehicleCatalog {
    val kia = VehicleMake(1, "Kia")
    val acura = VehicleMake(2, "Acura")
    val sorento = VehicleModel(10, "Sorento", 2002, null)
    val gen2002 = VehicleGeneration(101, "Sorento (2002)", 2002, 2006, null)
    val gen2006 = VehicleGeneration(100, "Sorento (2006)", 2006, 2009, null)
    val v6 = VehicleEngine(1001, "3.5L V6 4AT AWD (195 HP)", "Gasoline", 6, 3497, 194.0, 294.0, "4-Speed automatic", "All Wheel Drive", 11.9, 1917, tankL = 79.9)
    val i4 = VehicleEngine(1000, "2.4L 16V 5MT AWD (139 HP)", "Gasoline", 4, 2351, 139.0, 196.0, "5-Speed manual", "All Wheel Drive", 10.9, 1838)

    override fun isAvailable() = available
    override suspend fun makes(query: String) = if (!available) null else listOf(acura, kia).filter { it.name.contains(query, true) }
    override suspend fun makeByName(name: String) = if (!available) null else listOf(acura, kia).firstOrNull { it.name.equals(name, true) }
    override suspend fun models(makeId: Long, query: String) = if (!available) null else if (makeId == 1L) listOf(sorento).filter { it.name.contains(query, true) } else emptyList()
    override suspend fun generations(modelId: Long) = if (!available) null else listOf(gen2002, gen2006)
    override suspend fun engines(generationId: Long, query: String) = if (!available) null else listOf(i4, v6).filter { it.label.contains(query, true) }
    override suspend fun engine(id: Long) = listOf(i4, v6).firstOrNull { it.id == id }
}

private class MemPacks(var metered: Boolean = false) : DataPackService {
    val states = MutableStateFlow(listOf(PackState(PackCatalog.vehicles)))
    val downloads = mutableListOf<String>()
    override fun observe() = states
    override fun download(id: String, version: String?) { downloads += id }
    override fun cancel(id: String) = Unit
    override fun delete(id: String) = Unit
    override fun isMeteredNetwork() = metered
    override suspend fun checkUpdates() = emptyMap<String, String>()
}

@OptIn(ExperimentalCoroutinesApi::class)
class CarPickerTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val now = 1_800_000_000_000L
    private fun cars() = MemCars().apply {
        all.value = listOf(CarProfile(id = 1, name = "Test car", brand = "Kia", year = 2005, externalId = "carscanner:1"))
    }

    @Test
    fun `brand from the profile opens its models, picking the generation and engine fills specs and keeps name, brand, year`() = runTest {
        val cars = cars()
        val catalog = MemCatalog()
        val vm = CarEditViewModel(1, cars, clock = { now }, catalog = catalog, packs = MemPacks())
        assertTrue(vm.state.value.catalogAvailable)

        vm.openPicker()
        assertEquals(PickerStep.MODEL, vm.state.value.picker!!.step)
        assertEquals(listOf("Sorento"), vm.state.value.picker!!.options.map { it.title })
        vm.choose(vm.state.value.picker!!.options.single())
        val gens = vm.state.value.picker!!.options
        assertEquals(UiText.Res(R.string.car_your_year, listOf(UiText.Res(CoreUiR.string.common_years_range, listOf(2002, 2006)))), gens.first { it.id == 101L }.subtitle)
        vm.choose(gens.first { it.id == 101L })
        vm.search("V6")
        assertEquals(listOf("3.5L V6 4AT AWD (195 HP)"), vm.state.value.picker!!.options.map { it.title })
        vm.choose(vm.state.value.picker!!.options.single())
        assertEquals(79.9, vm.state.value.picker!!.engine!!.tankL!!, 0.0)
        vm.fillFromPicker()
        assertNull(vm.state.value.picker)

        val f = vm.state.value.form
        assertEquals("3.5", f.engineVolumeL)
        assertEquals("6", f.cylinders)
        assertEquals(FuelType.PETROL, f.fuelType)
        assertEquals(Gearbox.AUTOMATIC, f.gearbox)
        assertEquals(DriveType.ALL, f.drive)
        assertEquals("79.9", f.tankVolumeL)
        assertEquals("1917", f.curbWeightKg)
        assertEquals("Sorento", f.model)
        vm.edit { it.copy(engineCode = "G6CU") } // fields remain editable
        vm.save()

        val saved = cars.all.value.single()
        assertEquals("Test car", saved.name)
        assertEquals("Kia", saved.brand)
        assertEquals(2005, saved.year)
        assertEquals(3.5, saved.engineVolumeL!!, 0.0)
        assertEquals(1917, saved.curbWeightKg)
        assertEquals(DriveType.ALL, saved.drive)
        assertEquals("G6CU", saved.engineCode)
        assertEquals("carscanner:1", saved.externalId)
    }

    @Test
    fun `back walks up the steps and closes at the first one`() = runTest {
        val vm = CarEditViewModel(1, MemCars(), clock = { now }, catalog = MemCatalog(), packs = MemPacks())
        vm.openPicker()
        assertEquals(PickerStep.MAKE, vm.state.value.picker!!.step)
        vm.search("ki")
        vm.choose(vm.state.value.picker!!.options.single())
        assertEquals(PickerStep.MODEL, vm.state.value.picker!!.step)
        vm.pickerBack()
        assertEquals(PickerStep.MAKE, vm.state.value.picker!!.step)
        vm.pickerBack()
        assertNull(vm.state.value.picker)
    }

    @Test
    fun `without the pack a download is offered and asks first on mobile data`() = runTest {
        val packs = MemPacks(metered = true)
        val vm = CarEditViewModel(1, cars(), clock = { now }, catalog = MemCatalog(available = false), packs = packs)
        assertEquals(false, vm.state.value.catalogAvailable)
        assertEquals(PackCatalog.VEHICLES, vm.state.value.vehiclePack?.manifest?.id)
        vm.downloadCatalog()
        assertTrue(vm.state.value.confirmMeteredDownload)
        assertEquals(emptyList<String>(), packs.downloads)
        vm.downloadCatalog(confirmed = true)
        assertEquals(listOf(PackCatalog.VEHICLES), packs.downloads)
    }

    @Test
    fun `mass is validated`() {
        assertEquals(UiText.Res(R.string.car_error_range, listOf("300", "10000")), CarForm(name = "Test car", curbWeightKg = "50").errors(2026)[CarField.MASS])
        assertNull(CarForm(name = "Test car", curbWeightKg = "1917").errors(2026)[CarField.MASS])
    }
}
