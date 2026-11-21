package io.github.iampopovich.eculense.core.aitools

import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.ModuleScanRepository
import io.github.iampopovich.eculense.core.data.StoredModule
import io.github.iampopovich.eculense.core.data.StoredModuleScan
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.dtcdb.DtcInfo
import io.github.iampopovich.eculense.core.dtcdb.DtcSource
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.obd.ModuleDtc
import io.github.iampopovich.eculense.core.obd.ModuleDtcRead
import io.github.iampopovich.eculense.core.obd.ModuleDtcService
import io.github.iampopovich.eculense.core.obd.ModuleDtcStatus
import io.github.iampopovich.eculense.core.obd.ModuleKind
import io.github.iampopovich.eculense.core.obd.ModuleScanResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** R-D: the moduleDtcs tool (English, compact, stored real-car scans only, no car name). */
class ModuleDtcsToolTest {
    private val car = CarProfile(id = 1, name = "Тестовая", brand = "Kia")
    private val cars = object : CarRepository {
        override fun observeCars(): Flow<List<CarProfile>> = flowOf(listOf(car))
        override fun observeActiveCar(): Flow<CarProfile?> = flowOf(car)
        override suspend fun activeCarId() = car.id
        override suspend fun cars() = listOf(car)
        override suspend fun byExternalId(externalId: String): CarProfile? = null
        override suspend fun save(car: CarProfile) = car.id
        override suspend fun delete(id: Long) = false
        override suspend fun setActive(id: Long) = Unit
    }
    private val trips = object : TripRepository {
        override fun observeTrips(carId: Long): Flow<List<Trip>> = flowOf(emptyList())
        override suspend fun addAll(trips: List<Trip>) = 0
    }

    private class Scans(val scan: StoredModuleScan?) : ModuleScanRepository {
        override suspend fun save(result: ModuleScanResult): Boolean = error("tool must not write")
        override suspend fun latest(carId: Long?) = scan
    }

    private fun ctx(scans: ModuleScanRepository?) = ToolContext(
        noAnalytics(), cars, trips, clock = { 0L }, zone = { ZoneOffset.UTC }, log = { _, _, _ -> },
        describeDtc = { codes, _ -> codes.associateWith { DtcInfo(it, if (it == "C0035") "Left front wheel speed sensor" else null, DtcSource.UNKNOWN) } },
        moduleScans = scans,
    )

    @Test
    fun `lists modules with codes, flags and reasons`() = runBlocking {
        val scan = StoredModuleScan(
            86_400_000L, '6',
            listOf(
                StoredModule("7D1/7D9", ModuleKind.ABS_ESC, ModuleDtcRead.Codes(ModuleDtcService.UDS_19_02, listOf(ModuleDtc("C0035", setOf(ModuleDtcStatus.TEST_FAILED, ModuleDtcStatus.CONFIRMED))))),
                StoredModule("7A0/7A8", ModuleKind.BODY, ModuleDtcRead.NotSupported),
                StoredModule("7C6/7CE", ModuleKind.CLUSTER, ModuleDtcRead.Refused(0x22)),
            ),
        )
        val json = ModuleDtcsTool(ctx(Scans(scan))).execute(NoArgs())
        assertFalse(json, json.contains("Тестовая"))
        val modules = Json.parseToJsonElement(json).jsonObject.getValue("modules").jsonArray.map { it.jsonObject }
        assertEquals(listOf("abs_esc", "body", "cluster"), modules.map { it.getValue("module").jsonPrimitive.content })
        assertEquals(
            "C0035 · test_failed,confirmed · Left front wheel speed sensor",
            modules[0].getValue("codes").jsonArray.single().jsonPrimitive.content,
        )
        assertEquals("not_supported", modules[1].getValue("status").jsonPrimitive.content)
        assertEquals("refused 7F 22", modules[2].getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `no scan or no repository is an explicit no_data`() = runBlocking {
        assertTrue(Json.parseToJsonElement(ModuleDtcsTool(ctx(Scans(null))).execute(NoArgs())).jsonObject.containsKey("no_data"))
        assertTrue(Json.parseToJsonElement(ModuleDtcsTool(ctx(null)).execute(NoArgs())).jsonObject.containsKey("no_data"))
    }
}
