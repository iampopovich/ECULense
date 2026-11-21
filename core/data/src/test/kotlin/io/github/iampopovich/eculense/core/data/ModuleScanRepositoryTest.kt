package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.database.ModuleScanDao
import io.github.iampopovich.eculense.core.database.ModuleScanEntity
import io.github.iampopovich.eculense.core.obd.EcuAddress
import io.github.iampopovich.eculense.core.obd.FoundModule
import io.github.iampopovich.eculense.core.obd.ModuleDtc
import io.github.iampopovich.eculense.core.obd.ModuleDtcRead
import io.github.iampopovich.eculense.core.obd.ModuleDtcService
import io.github.iampopovich.eculense.core.obd.ModuleDtcStatus
import io.github.iampopovich.eculense.core.obd.ModuleKind
import io.github.iampopovich.eculense.core.obd.ModuleReport
import io.github.iampopovich.eculense.core.obd.ModuleScanResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-D: module scans are stored per car for real links only and read back unchanged. */
class ModuleScanRepositoryTest {
    private class FakeDao : ModuleScanDao {
        val rows = mutableListOf<ModuleScanEntity>()
        override suspend fun insert(rows: List<ModuleScanEntity>) {
            this.rows += rows.mapIndexed { i, r -> r.copy(id = (this.rows.size + i + 1).toLong()) }
        }
        override suspend fun latest(carId: Long): List<ModuleScanEntity> {
            val mine = rows.filter { it.carId == carId }
            val max = mine.maxOfOrNull { it.scanAtMs } ?: return emptyList()
            return mine.filter { it.scanAtMs == max }
        }
        override suspend fun recent(carId: Long, limit: Int) = rows.filter { it.carId == carId }.take(limit)
        override suspend fun count() = rows.size
    }

    private val result = ModuleScanResult(
        '6',
        listOf(
            ModuleReport(FoundModule(EcuAddress.can11(0x7E0), ModuleKind.ENGINE, true), ModuleDtcRead.Codes(ModuleDtcService.MODE_03, listOf(ModuleDtc("P0133", setOf(ModuleDtcStatus.CONFIRMED))))),
            ModuleReport(
                FoundModule(EcuAddress.can11(0x7D1), ModuleKind.ABS_ESC, false),
                ModuleDtcRead.Codes(ModuleDtcService.UDS_19_02, listOf(ModuleDtc("C0035", setOf(ModuleDtcStatus.TEST_FAILED, ModuleDtcStatus.CONFIRMED, ModuleDtcStatus.WARNING_LAMP)))),
            ),
            ModuleReport(FoundModule(EcuAddress.can11(0x7A0), ModuleKind.BODY, false), ModuleDtcRead.NotSupported),
            ModuleReport(FoundModule(EcuAddress.can11(0x7C6), ModuleKind.CLUSTER, false), ModuleDtcRead.Refused(0x22)),
        ),
    )

    @Test
    fun `a real link stores the scan and it reads back`() = runTest {
        val dao = FakeDao()
        val repo = RoomModuleScanRepository(dao, { 1L }, LiveWriteGate.ALWAYS, clock = { 500L })
        assertTrue(repo.save(result))
        assertEquals(4, dao.rows.size)
        val stored = repo.latest()!!
        assertEquals(500L, stored.atMs)
        assertEquals('6', stored.protocol)
        assertEquals(listOf("7E0/7E8", "7D1/7D9", "7A0/7A8", "7C6/7CE"), stored.modules.map { it.address })
        // The failure type byte is not stored; everything else round-trips.
        assertEquals(result.modules.map { it.read }, stored.modules.map { it.read })
        assertEquals(ModuleKind.CLUSTER, stored.modules[3].kind)
        assertNull(repo.latest(carId = 2L))
    }

    @Test
    fun `a simulated link stores nothing`() = runTest {
        val dao = FakeDao()
        val repo = RoomModuleScanRepository(dao, { 1L }, LiveWriteGate { false })
        assertFalse(repo.save(result))
        assertEquals(0, dao.count())
        assertNull(repo.latest())
    }

    @Test
    fun `codes codec`() {
        val dtcs = listOf(ModuleDtc("U0100", setOf(ModuleDtcStatus.PENDING)), ModuleDtc("B1234", emptySet()))
        assertEquals("U0100:P;B1234:", ModuleScanCodec.encodeDtcs(dtcs))
        assertEquals(dtcs, ModuleScanCodec.decodeDtcs("U0100:P;B1234:"))
        assertEquals(emptyList<ModuleDtc>(), ModuleScanCodec.decodeDtcs(""))
    }
}
