package io.github.iampopovich.eculense.feature.datapacks

import io.github.iampopovich.eculense.core.datapacks.DataPackService
import io.github.iampopovich.eculense.core.datapacks.PackCatalog
import io.github.iampopovich.eculense.core.datapacks.PackState
import io.github.iampopovich.eculense.core.datapacks.PackStore
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import io.github.iampopovich.eculense.core.ui.UiText
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

private class FakePacks(var metered: Boolean = false) : DataPackService {
    val states = MutableStateFlow(PackCatalog.all.map { PackState(it) })
    val calls = mutableListOf<String>()
    override fun observe() = states
    override fun download(id: String, version: String?) { calls += "download $id ${version ?: "-"}" }
    override fun cancel(id: String) { calls += "cancel $id" }
    override fun delete(id: String) { calls += "delete $id" }
    override fun isMeteredNetwork() = metered
    override suspend fun checkUpdates(): Map<String, String> = mapOf("vehicles" to "v1.0.4")
}

@OptIn(ExperimentalCoroutinesApi::class)
class DataPacksViewModelTest {
    // Sizes are formatted with the app locale; these texts are Russian until the module is migrated (B3).
    private val savedLocale = Locale.getDefault()

    @Before fun setUp() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        Locale.setDefault(savedLocale)
    }

    private val vehicles get() = PackState(PackCatalog.vehicles)

    @Test
    fun `unmetered network downloads at once`() = runTest {
        val packs = FakePacks()
        DataPacksViewModel(packs).download(vehicles)
        assertEquals(listOf("download vehicles -"), packs.calls)
    }

    @Test
    fun `metered network asks first, confirm downloads, dismiss does not`() = runTest {
        val packs = FakePacks(metered = true)
        val vm = DataPacksViewModel(packs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.download(vehicles)
        assertTrue(vm.uiState.value.confirm is PackConfirm.Metered)
        assertEquals(emptyList<String>(), packs.calls)
        vm.dismiss()
        assertNull(vm.uiState.value.confirm)
        vm.download(vehicles)
        vm.confirm()
        assertEquals(listOf("download vehicles -"), packs.calls)
    }

    @Test
    fun `update downloads the found version, delete needs confirmation`() = runTest {
        val packs = FakePacks()
        val vm = DataPacksViewModel(packs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val installed = PackState(PackCatalog.vehicles, PackStore.Installed("vehicles", "v1.0.3", File("x"), 60_080_128), updateVersion = "v1.0.4")
        vm.update(installed)
        vm.askDelete(installed)
        assertEquals(listOf("download vehicles v1.0.4"), packs.calls)
        vm.confirm()
        assertEquals(listOf("download vehicles v1.0.4", "delete vehicles"), packs.calls)
        vm.checkUpdates()
        assertEquals(UiText.Res(R.string.datapacks_updates_available, listOf(1)), vm.uiState.value.checkMessage)
    }

    @Test
    fun `status line shows size, progress, version and update`() {
        val size = UiText.Res(R.string.datapacks_size_mb, listOf(60_080_128 / 1024.0 / 1024.0))
        assertEquals(UiText.Res(R.string.datapacks_status_not_downloaded_size, listOf(size)), statusText(vehicles))
        assertEquals(UiText.Res(R.string.datapacks_status_downloading_pct, listOf(42)), statusText(vehicles.copy(progress = 42)))
        assertEquals(UiText.Res(R.string.datapacks_status_downloading), statusText(vehicles.copy(progress = -1)))
        val installed = vehicles.copy(installed = PackStore.Installed("vehicles", "v1.0.3", File("x"), 60_080_128))
        assertEquals(UiText.Res(R.string.datapacks_status_installed, listOf("v1.0.3", size)), statusText(installed))
        assertEquals(
            UiText.Res(R.string.datapacks_status_installed_update, listOf("v1.0.3", size, "v1.0.4")),
            statusText(installed.copy(updateVersion = "v1.0.4")),
        )
        assertEquals(UiText.Res(R.string.datapacks_size_kb, listOf(512L)), formatSize(524_288))
    }

    @Test
    fun `about lists every data source with its license`() {
        val licenses = ATTRIBUTIONS.associate { it.url to it.license }
        assertEquals(UiText.Res(PackCatalog.vehicles.licenseRes), licenses.getValue("https://github.com/gor3a/vehicle-makes-models"))
        assertTrue(PackCatalog.vehicles.license.startsWith("ODbL"))
        assertEquals(UiText.Res(PackCatalog.obdex.licenseRes), licenses.getValue("https://github.com/foerbsnavi/OBDex"))
        assertTrue(PackCatalog.obdex.license.startsWith("CC0"))
        assertEquals(UiText.Raw("MIT"), licenses.getValue("https://github.com/Wal33D/dtc-database"))
        assertEquals(UiText.Raw("CC BY-SA 4.0"), licenses.getValue("https://github.com/OBDb"))
        assertEquals(UiText.Raw("MIT"), licenses.getValue("https://github.com/lennykean/OBDII.DTC"))
    }

    @Test
    fun `car title uses make, model and year, else the name, else the localized default name`() {
        assertEquals(UiText.Raw("Kia Sorento 2005"), carTitle(CarProfile(id = 1, name = "x", brand = "Kia", model = "Sorento", year = 2005)))
        assertEquals(UiText.Raw("Test"), carTitle(CarProfile(id = 1, name = "Test")))
        // a fresh install has a blank name
        assertEquals(UiText.Res(CoreUiR.string.common_car_default_name), carTitle(CarProfile(id = 1, name = "")))
    }
}
