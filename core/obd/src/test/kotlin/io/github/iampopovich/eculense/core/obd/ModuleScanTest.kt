package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-D: discovery, module codes and the guaranteed restore of the normal (ATH0) mode. */
class ModuleScanTest {
    private val rpm = StandardPids.byCode(0x0C)!!

    /** CAN 11/500 car: engine 7E8 + TCM 7E9 answer functionally, ABS behind 7D1/7D9, an SRS that must never be asked. */
    private val can = mapOf(
        "7DF|0100" to "7E8064100BE3FA813\r7E906410080000001",
        "7E0|19020D" to "7E8037F1911",
        "7E0|03" to "7E80643020133 0300",
        "7E0|07" to "7E8024700",
        "7E1|19020D" to "7E9075902FF07150009",
        "7D1|3E00" to "7D9027E00",
        "7D1|19020D" to "7D9100B5902FF403500\r7D9210952000008AAAA",
        "7D2|3E00" to "7DA027E00",
        "7D2|19020D" to "7DA075902FF9001000F",
    )

    private suspend fun connected(adapter: MultiEcuFakeAdapter): Elm327Session =
        Elm327Session(ElmLink(adapter), clock = { 1_000L }).also { it.initialize() }

    @Test
    fun `CAN scan finds engine, transmission and ABS, reads their codes and never addresses the airbags`() = runTest {
        val adapter = MultiEcuFakeAdapter(headered = can)
        val session = connected(adapter)
        val progress = mutableListOf<ScanProgress>()
        val result = ModuleScanner().scan(session) { progress += it }

        assertEquals(listOf(ModuleKind.ENGINE, ModuleKind.TRANSMISSION, ModuleKind.ABS_ESC), result.modules.map { it.module.kind })
        assertEquals(listOf("7E8", "7E9", "7D9"), result.modules.map { it.module.address.rx })
        val engine = result.modules[0].read as ModuleDtcRead.Codes
        assertEquals(ModuleDtcService.MODE_03, engine.service) // 7F 19 11 -> emission fallback
        assertEquals(listOf("P0133", "P0300"), engine.dtcs.map { it.code })
        val tcm = result.modules[1].read as ModuleDtcRead.Codes
        assertEquals(ModuleDtcService.UDS_19_02, tcm.service)
        assertEquals(listOf(ModuleDtc("P0715", setOf(ModuleDtcStatus.TEST_FAILED, ModuleDtcStatus.CONFIRMED), ftb = 0)), tcm.dtcs)
        val abs = result.modules[2].read as ModuleDtcRead.Codes
        assertEquals(listOf("C0035", "C1200"), abs.dtcs.map { it.code })
        assertEquals(setOf(ModuleDtcStatus.CONFIRMED), abs.dtcs[1].status)
        assertTrue(result.modules.none { it.module.kind == ModuleKind.SRS })
        assertEquals(listOf("7D4/7DC", "7D2/7DA"), result.skipped)

        // Nothing reached the airbag or the conflicting steering/SRS address: not even a header command.
        assertTrue(adapter.writes.none { it.first in setOf("7D2", "7D4") })
        assertTrue(adapter.sent.none { it.startsWith("ATSH7D2") || it.startsWith("ATSH7D4") || it.startsWith("ATCRA7DA") || it.startsWith("ATCRA7DC") })
        // Only read services were written while the headers were on.
        assertTrue(adapter.writes.filter { it.first != "7DF" }.all { it.second.take(2).toInt(16) in EcuSafetyPolicy.READ_SERVICES })
        assertTrue(progress.first() is ScanProgress.Discovering)
        assertTrue(progress.any { it is ScanProgress.Probing && it.address.rx == "7D9" })

        // Restored: headers off, default header, automatic receive filter; a normal read works again.
        assertFalse(adapter.headers)
        assertEquals("7DF", adapter.header)
        assertNull(adapter.receiveFilter)
        assertEquals(listOf("ATH0", "ATSH7DF", "ATCRA"), adapter.sent.takeLast(3))
        assertEquals(1726.0, session.read(rpm)!!.value, 0.001)
    }

    @Test
    fun `a failure inside the block restores the headers and the next 010C works`() = runTest {
        val adapter = MultiEcuFakeAdapter(headered = can)
        val session = connected(adapter)
        val e = runCatching {
            session.withHeaders { ch ->
                ch.physical(EcuTarget(EcuAddress.can11(0x7D1), false), "3E00")
                error("boom")
            }
        }.exceptionOrNull()
        assertEquals("boom", e?.message)
        assertFalse(adapter.headers)
        assertEquals("7DF", adapter.header)
        assertNull(adapter.receiveFilter)
        assertEquals(1726.0, session.read(rpm)!!.value, 0.001)
    }

    @Test
    fun `cancellation inside the block still restores (NonCancellable)`() = runTest {
        val adapter = MultiEcuFakeAdapter(headered = can)
        val session = connected(adapter)
        val inside = CompletableDeferred<Unit>()
        val job = launch {
            session.withHeaders { ch ->
                ch.physical(EcuTarget(EcuAddress.can11(0x7E1), false), "3E00")
                inside.complete(Unit)
                awaitCancellation()
            }
        }
        inside.await()
        assertTrue(adapter.headers)
        job.cancel()
        job.join()
        assertFalse(adapter.headers)
        assertEquals("7DF", adapter.header)
        assertNull(adapter.receiveFilter)
        assertEquals(1726.0, session.read(rpm)!!.value, 0.001)
    }

    @Test
    fun `a denied request writes nothing at all`() = runTest {
        val adapter = MultiEcuFakeAdapter(headered = can)
        val session = connected(adapter)
        val before = adapter.sent.size
        for ((target, command) in listOf(
            EcuTarget(EcuAddress.can11(0x7D2), false) to "1902FF",
            EcuTarget(EcuAddress.can11(0x7D4), false) to "3E00",
            EcuTarget(EcuAddress.can11(0x7D1), false) to "2EF19000",
            EcuTarget(EcuAddress.can11(0x7D1), false) to "1103",
            EcuTarget(EcuAddress.can11(0x7D1), false) to "14FFFFFF",
            EcuTarget(EcuAddress.can11(0x7E0), true) to "04",
        )) {
            val error = runCatching { session.withHeaders { ch -> ch.physical(target, command) } }.exceptionOrNull()
            assertTrue(command, (error as ObdException).error is ObdError.RequestDenied)
        }
        val functional = runCatching { session.withHeaders { ch -> ch.functional("3101FF00") } }.exceptionOrNull()
        assertTrue((functional as ObdException).error is ObdError.RequestDenied)
        // Per attempt only ATH1 + the restore commands went out; no OBD request at all.
        assertTrue(adapter.sent.drop(before).all { it.startsWith("AT") })
        assertTrue(adapter.writes.none { it.second in setOf("1902FF", "3E00", "2EF19000", "1103", "14FFFFFF", "04", "3101FF00") })
    }

    @Test
    fun `a clone without ATCRA lists only the functional responders and says so`() = runTest {
        val adapter = MultiEcuFakeAdapter(headered = can, questionMark = setOf("ATCRA7"))
        val session = connected(adapter)
        val result = ModuleScanner().scan(session)
        assertTrue(result.limitedByAdapter)
        assertEquals(listOf("7E8", "7E9"), result.modules.map { it.module.address.rx })
        assertTrue("ATCRA" in session.capabilities.unsupported)
        assertFalse(adapter.headers)
        assertEquals(1726.0, session.read(rpm)!!.value, 0.001)
    }

    @Test
    fun `an adapter without ATH1 reports the scan as unsupported and stays usable`() = runTest {
        val adapter = MultiEcuFakeAdapter(headered = can, questionMark = setOf("ATH1"))
        val session = connected(adapter)
        val e = runCatching { ModuleScanner().scan(session) }.exceptionOrNull() as ObdException
        assertEquals(ObdError.ModuleScanUnsupported("headers"), e.error)
        assertEquals(1726.0, session.read(rpm)!!.value, 0.001)
    }

    @Test
    fun `K-Line KWP scan stays functional (C2 33 F1), splits replies by source and falls back per NRC`() = runTest {
        val kwp = FakeCar.replies + mapOf("ATDPN" to "A5", "0100" to "4100BE3EB011", "010C" to "410C1AF8")
        val adapter = MultiEcuFakeAdapter(
            normal = kwp,
            defaultHeader = "C133F1",
            headered = mapOf(
                "C133F1|0100" to "86F1104100BE3EB01185\r86 F1 18 41 00 80 00 00 00 50",
                "C133F1|1800FF00" to "88F11058020133E00300201A\r83F1187F181235",
                "C133F1|1802FF00" to "83F1187F181134",
                "C133F1|03" to "87F11843070000000000DA",
            ),
        )
        val session = connected(adapter)
        val result = ModuleScanner().scan(session)
        assertEquals(listOf("10", "18"), result.modules.map { it.module.address.rx })
        assertEquals(listOf(ModuleKind.ENGINE, ModuleKind.TRANSMISSION), result.modules.map { it.module.kind })
        val engine = result.modules[0].read as ModuleDtcRead.Codes
        assertEquals(ModuleDtcService.KWP_18_00, engine.service)
        assertEquals(
            listOf(
                ModuleDtc("P0133", setOf(ModuleDtcStatus.TEST_FAILED, ModuleDtcStatus.CONFIRMED, ModuleDtcStatus.WARNING_LAMP)),
                ModuleDtc("P0300", setOf(ModuleDtcStatus.CONFIRMED)),
            ),
            engine.dtcs,
        )
        val tcm = result.modules[1].read as ModuleDtcRead.Codes
        assertEquals(ModuleDtcService.MODE_03, tcm.service)
        assertEquals(listOf("P0700"), tcm.dtcs.map { it.code })
        // No blind physical probing and no header switching on K-Line: only the restore sets the default header.
        assertEquals(listOf("ATSHC133F1"), adapter.sent.filter { it.startsWith("ATSH") })
        assertTrue(adapter.sent.none { it.startsWith("ATCRA") })
        assertTrue(adapter.writes.none { it.second == "3E00" })
        assertFalse(adapter.headers)
        assertEquals(1726.0, session.read(rpm)!!.value, 0.001)
    }

    @Test
    fun `UDS and KWP record decoders`() {
        val uds = ModuleDtcDecoder.uds(byteArrayOf(0x59, 0x02, 0xFF.toByte(), 0x40, 0x35, 0x00, 0x09, 0x52, 0x00, 0x11, 0x40))!!
        // The second record has only bit 6 (not completed this cycle): outside the mask, dropped.
        assertEquals(listOf(ModuleDtc("C0035", setOf(ModuleDtcStatus.TEST_FAILED, ModuleDtcStatus.CONFIRMED), 0)), uds)
        assertNull(ModuleDtcDecoder.uds(byteArrayOf(0x7F, 0x19, 0x12)))
        val kwp = ModuleDtcDecoder.kwp(byteArrayOf(0x58, 0x03, 0x01, 0x33, 0x40, 0xC1.toByte(), 0x00, 0x20, 0x00, 0x00, 0x00))!!
        assertEquals(listOf(ModuleDtc("P0133", setOf(ModuleDtcStatus.PENDING)), ModuleDtc("U0100", setOf(ModuleDtcStatus.CONFIRMED))), kwp)
        assertEquals(emptyList<ModuleDtc>(), ModuleDtcDecoder.kwp(byteArrayOf(0x58, 0x00)))
    }
}
