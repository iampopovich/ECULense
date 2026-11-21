package io.github.iampopovich.eculense.feature.datapacks

import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.datapacks.HttpResponse
import io.github.iampopovich.eculense.core.datapacks.PackHttp
import io.github.iampopovich.eculense.core.datapacks.PackStore
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.obdb.ObdbSignalsets
import io.github.iampopovich.eculense.core.obdb.ProfileField
import io.github.iampopovich.eculense.core.obdb.SignalsetResult
import io.github.iampopovich.eculense.core.ui.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** Synthetic signalset (OBDb v3 structure, own values). */
private const val SIGNALSET = """{ "commands": [ { "hdr": "7E0", "rax": "7E8", "cmd": {"22": "0101"}, "freq": 1,
  "signals": [ {"id": "TEST_OIL_T", "path": "Engine", "fmt": {"bix": 8, "len": 8, "max": 215, "add": -40, "unit": "celsius"}, "name": "Oil temperature"} ]} ] }"""

private class OneCar(private val car: CarProfile?) : CarRepository {
    override fun observeCars(): Flow<List<CarProfile>> = flowOf(listOfNotNull(car))
    override fun observeActiveCar(): Flow<CarProfile?> = flowOf(car)
    override suspend fun activeCarId() = car?.id
    override suspend fun cars() = listOfNotNull(car)
    override suspend fun byExternalId(externalId: String) = null
    override suspend fun save(car: CarProfile) = car.id
    override suspend fun delete(id: Long) = false
    override suspend fun setActive(id: Long) = Unit
}

/** Serves url → body; [offline] = every request fails like a phone without network. */
private class MapHttp(private val files: Map<String, String>) : PackHttp {
    @Volatile var offline = false
    override fun get(url: String): HttpResponse {
        if (offline) throw IOException("Unable to resolve host")
        val body = files[url] ?: throw IOException("HTTP 404: $url")
        return HttpResponse(200, body.length.toLong(), body.byteInputStream())
    }
    override fun redirectLocation(url: String): String? = null
    override fun status(url: String): Int {
        if (offline) throw IOException("Unable to resolve host")
        return if (url in files) 200 else 404
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SignalsetViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val base = "http://obdb.test"
    private val files = mapOf(
        "$base/OBDb/Kia-Ceed/main/signalsets/v3/default.json" to SIGNALSET,
        "$base/OBDb/Kia-Ceed/main/tests/test_cases/2019/command_support.yaml" to "supported: []",
    )
    private val car = CarProfile(id = 1, name = "Тестовая", brand = "Kia", model = "Ceed", year = 2019)

    private fun await(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(what, condition())
    }

    private fun obdb(http: PackHttp) = ObdbSignalsets(http, PackStore(tmp.root), rawBase = base, currentYear = { 2026 })

    @Test
    fun `a car without make, model or year asks for the profile`() {
        val vm = SignalsetViewModel(OneCar(CarProfile(id = 2, name = "Test car", brand = "Kia")), obdb(MapHttp(files)))
        await("loaded") { vm.state.value.carTitle != null }
        assertEquals(UiText.Raw("Kia"), vm.state.value.carTitle)
        vm.check()
        await("checked") { vm.state.value.result != null }
        assertEquals(SignalsetResult.NeedsProfile(listOf(ProfileField.MODEL, ProfileField.YEAR)), vm.state.value.result)
    }

    @Test
    fun `offline check keeps the previous result and shows the error, a later check succeeds and is stored`() {
        val http = MapHttp(files).apply { offline = true }
        val signalsets = obdb(http)
        val vm = SignalsetViewModel(OneCar(car), signalsets)
        await("loaded") { vm.state.value.year == "2019" }
        assertNull(vm.state.value.result) // nothing downloaded yet
        vm.check()
        await("error") { vm.state.value.error != null && !vm.state.value.loading }
        assertEquals(UiText.Res(R.string.datapacks_obdb_offline, listOf("Unable to resolve host")), vm.state.value.error)
        assertNull(vm.state.value.result)

        http.offline = false
        vm.check()
        await("covered") { vm.state.value.result is SignalsetResult.Covered }
        assertNull(vm.state.value.error)
        assertEquals(1, (vm.state.value.result as SignalsetResult.Covered).signalCount)

        // a new screen shows the stored download right away, offline
        http.offline = true
        val again = SignalsetViewModel(OneCar(car), signalsets)
        await("stored") { again.state.value.result is SignalsetResult.Covered }
    }

    @Test
    fun `the year can be edited (digits only) and a model without an OBDb repository is reported`() {
        val vm = SignalsetViewModel(OneCar(car.copy(model = "Unknown Model")), obdb(MapHttp(files)))
        await("loaded") { vm.state.value.year == "2019" }
        vm.setYear("20a1-75")
        assertEquals("2017", vm.state.value.year)
        vm.check()
        await("no repo") { vm.state.value.result != null }
        assertEquals(SignalsetResult.NoRepository("OBDb/Kia-Unknown-Model"), vm.state.value.result)
    }

    @Test
    fun `a year OBDb has no test data for is not covered`() {
        val vm = SignalsetViewModel(OneCar(car), obdb(MapHttp(files)))
        await("loaded") { vm.state.value.year == "2019" }
        vm.setYear("2012")
        vm.check()
        await("checked") { vm.state.value.result != null }
        assertTrue(vm.state.value.result is SignalsetResult.NotCovered)
    }
}
