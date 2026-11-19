package io.github.iampopovich.eculense.feature.datapacks

import io.github.iampopovich.eculense.core.datapacks.DataPackService
import io.github.iampopovich.eculense.core.datapacks.PackCatalog
import io.github.iampopovich.eculense.core.datapacks.PackState
import io.github.iampopovich.eculense.core.datapacks.PackStore
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
import java.io.File
import java.util.Locale

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
        assertEquals("Доступны обновления: 1", vm.uiState.value.checkMessage)
    }

    @Test
    fun `status line shows size, progress, version and update`() {
        assertEquals("Не скачан · 57,3 МБ", statusText(vehicles))
        assertEquals("Скачивается 42 %", statusText(vehicles.copy(progress = 42)))
        assertEquals("Скачивается…", statusText(vehicles.copy(progress = -1)))
        val installed = vehicles.copy(installed = PackStore.Installed("vehicles", "v1.0.3", File("x"), 60_080_128))
        assertEquals("Версия v1.0.3 · 57,3 МБ", statusText(installed))
        assertEquals("Версия v1.0.3 · 57,3 МБ · доступно обновление v1.0.4", statusText(installed.copy(updateVersion = "v1.0.4")))
        assertEquals("3,1 МБ", formatSize(3_256_320))
    }

    @Test
    fun `about lists every data source with its license`() {
        val licenses = ATTRIBUTIONS.associate { it.url to it.license }
        assertTrue(licenses.getValue("https://github.com/gor3a/vehicle-makes-models").startsWith("ODbL"))
        assertTrue(licenses.getValue("https://github.com/foerbsnavi/OBDex").startsWith("CC0"))
        assertEquals("MIT", licenses.getValue("https://github.com/Wal33D/dtc-database"))
        assertEquals("CC BY-SA 4.0", licenses.getValue("https://github.com/OBDb"))
        assertEquals("MIT", licenses.getValue("https://github.com/lennykean/OBDII.DTC"))
    }
}
