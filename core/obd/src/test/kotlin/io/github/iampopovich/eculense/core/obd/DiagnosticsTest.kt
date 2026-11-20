package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.SensorReading
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B1-B4, B7, B8: pure decoders with own vectors written from the SAE J1979 byte layouts. */
class DiagnosticsTest {
    private fun bytes(hex: String) = hex.replace(" ", "").hexToBytes()
    private fun ints(hex: String) = bytes(hex).toUnsigned().toIntArray()
    private fun Readiness.of(m: Monitor) = monitors.first { it.monitor == m }.status

    // ---- B1 readiness ----

    @Test
    fun `spark engine with MIL on, two incomplete monitors`() {
        // A=83: MIL + 3 DTCs. B=07: continuous supported and complete. C=65: catalyst, EVAP, O2, O2 heater.
        // D=21: catalyst and O2 sensor not complete.
        val r = ReadinessDecoder.status(ints("83 07 65 21"))
        assertTrue(r.milOn)
        assertEquals(3, r.dtcCount)
        assertEquals(Ignition.SPARK, r.ignition)
        assertEquals(MonitorStatus.COMPLETE, r.of(Monitor.MISFIRE))
        assertEquals(MonitorStatus.INCOMPLETE, r.of(Monitor.CATALYST))
        assertEquals(MonitorStatus.COMPLETE, r.of(Monitor.EVAPORATIVE_SYSTEM))
        assertEquals(MonitorStatus.INCOMPLETE, r.of(Monitor.OXYGEN_SENSOR))
        assertEquals(MonitorStatus.COMPLETE, r.of(Monitor.OXYGEN_SENSOR_HEATER))
        assertEquals(MonitorStatus.NOT_SUPPORTED, r.of(Monitor.HEATED_CATALYST))
        assertEquals(MonitorStatus.NOT_SUPPORTED, r.of(Monitor.EGR))
        assertEquals(2, r.incompleteCount)
        assertEquals(7, r.supported.size)
        assertFalse(r.allComplete)
    }

    @Test
    fun `incomplete continuous monitor uses the high nibble of B`() {
        val r = ReadinessDecoder.status(ints("00 17 01 00"))
        assertFalse(r.milOn)
        assertEquals(0, r.dtcCount)
        assertEquals(MonitorStatus.INCOMPLETE, r.of(Monitor.MISFIRE))
        assertEquals(MonitorStatus.COMPLETE, r.of(Monitor.FUEL_SYSTEM))
        assertEquals(MonitorStatus.COMPLETE, r.of(Monitor.CATALYST))
    }

    @Test
    fun `all supported monitors complete reads as ready`() {
        val r = ReadinessDecoder.status(ints("00 07 65 00"))
        assertTrue(r.allComplete)
        assertEquals(0, r.incompleteCount)
    }

    @Test
    fun `compression ignition uses the diesel table`() {
        // B bit 3 set; C=EB: NMHC, NOx/SCR, boost, exhaust sensor, PM filter, EGR/VVT; D=40: PM filter incomplete.
        val r = ReadinessDecoder.status(ints("00 0F EB 40"))
        assertEquals(Ignition.COMPRESSION, r.ignition)
        assertEquals(9, r.monitors.size)
        assertTrue(r.monitors.none { it.monitor == Monitor.CATALYST })
        assertEquals(MonitorStatus.INCOMPLETE, r.of(Monitor.PM_FILTER))
        assertEquals(MonitorStatus.COMPLETE, r.of(Monitor.BOOST_PRESSURE))
        assertEquals(1, r.incompleteCount)
    }

    @Test
    fun `this drive cycle keeps the ignition from PID 01`() {
        val cycle = ReadinessDecoder.thisDriveCycle(ints("00 07 01 01"), Ignition.SPARK)
        assertEquals(MonitorStatus.INCOMPLETE, cycle.first { it.monitor == Monitor.CATALYST }.status)
        assertEquals(MonitorStatus.NOT_SUPPORTED, cycle.first { it.monitor == Monitor.EGR }.status)
    }

    // ---- B2 recently cleared ----

    private val notReady = ReadinessDecoder.status(ints("00 07 65 21"))
    private val ready = ReadinessDecoder.status(ints("00 07 65 00"))

    @Test
    fun `low counters and incomplete monitors look like a recent reset`() {
        val check = UsedCarCheck.evaluate(ClearedCounters(warmUpsSinceClear = 3, kmSinceClear = 50), notReady)
        assertEquals(RecentClearVerdict.LIKELY_CLEARED, check.verdict)
        assertEquals(2, check.incompleteMonitors)
    }

    @Test
    fun `high counters and complete monitors show no signs`() {
        val check = UsedCarCheck.evaluate(ClearedCounters(warmUpsSinceClear = 250, kmSinceClear = 30_000, minutesSinceClear = 9_000), ready)
        assertEquals(RecentClearVerdict.NO_SIGNS, check.verdict)
    }

    @Test
    fun `one sign alone is only a possibility`() {
        assertEquals(RecentClearVerdict.POSSIBLY_CLEARED, UsedCarCheck.evaluate(ClearedCounters(kmSinceClear = 120), ready).verdict)
        assertEquals(RecentClearVerdict.POSSIBLY_CLEARED, UsedCarCheck.evaluate(ClearedCounters(warmUpsSinceClear = 80), notReady).verdict)
    }

    @Test
    fun `thresholds are data`() {
        val counters = ClearedCounters(warmUpsSinceClear = 15, kmSinceClear = 400)
        assertEquals(RecentClearVerdict.NO_SIGNS, UsedCarCheck.evaluate(counters, ready).verdict)
        assertEquals(RecentClearVerdict.POSSIBLY_CLEARED, UsedCarCheck.evaluate(counters, ready, RecentClearThresholds(warmUps = 20)).verdict)
    }

    @Test
    fun `nothing known is unknown`() {
        assertEquals(RecentClearVerdict.UNKNOWN, UsedCarCheck.evaluate(ClearedCounters(), null).verdict)
    }

    @Test
    fun `counters decode bytes and words`() {
        val c = ClearedCounters.decode(mapOf(0x30 to intArrayOf(5), 0x31 to intArrayOf(0x01, 0x2C), 0x4E to intArrayOf(0x00, 0x3C), 0x21 to intArrayOf(0, 0)))
        assertEquals(ClearedCounters(5, 300, 60, null, 0), c)
    }

    // ---- B7 VIN / CALID / CVN ----

    private val vin = "1ECULENSE0TEST001"
    private val vinHex = vin.encodeToByteArray().joinToString(" ") { "%02X".format(it) }

    @Test
    fun `CAN VIN with the count byte`() {
        assertEquals(vin, VehicleInfoDecoder.vin(listOf(bytes("49 02 01 $vinHex")), isCan = true))
    }

    @Test
    fun `CAN VIN without the count byte`() {
        assertEquals(vin, VehicleInfoDecoder.vin(listOf(bytes("49 02 $vinHex")), isCan = true))
    }

    /** "49 02 SEQ d1 d2 d3 d4": 3 bytes of zero padding + 17 characters over 5 lines. */
    private fun kLineVin(order: List<Int> = listOf(1, 2, 3, 4, 5)): List<ByteArray> {
        val data = listOf(0, 0, 0) + vin.encodeToByteArray().map { it.toInt() }
        return order.map { seq ->
            bytes("49 02 %02X ".format(seq) + data.subList((seq - 1) * 4, seq * 4).joinToString(" ") { "%02X".format(it) })
        }
    }

    @Test
    fun `K-Line VIN lines are sorted by the sequence byte and the padding is dropped`() {
        assertEquals(vin, VehicleInfoDecoder.vin(kLineVin(listOf(3, 1, 5, 2, 4)), isCan = false))
    }

    @Test
    fun `K-Line VIN with a missing line is rejected`() {
        assertNull(VehicleInfoDecoder.vin(kLineVin(listOf(1, 2, 4, 5)), isCan = false))
    }

    @Test
    fun `no VIN reply is null`() {
        assertNull(VehicleInfoDecoder.vin(emptyList(), isCan = false))
        assertNull(VehicleInfoDecoder.vin(listOf(bytes("49 02 01 00 00 00 00")), isCan = false))
    }

    @Test
    fun `calibration ids and CVNs on CAN and K-Line`() {
        val cal = "ECUTESTCAL000001".encodeToByteArray().joinToString(" ") { "%02X".format(it) }
        assertEquals(listOf("ECUTESTCAL000001"), VehicleInfoDecoder.calibrationIds(listOf(bytes("49 04 01 $cal")), isCan = true))
        val calBytes = "ECUTESTCAL0001".encodeToByteArray().map { it.toInt() } + listOf(0, 0)
        val lines = (1..4).map { seq -> bytes("49 04 %02X ".format(seq) + calBytes.subList((seq - 1) * 4, seq * 4).joinToString(" ") { "%02X".format(it) }) }
        assertEquals(listOf("ECUTESTCAL0001"), VehicleInfoDecoder.calibrationIds(lines.reversed(), isCan = false))
        assertEquals(listOf("A1B2C3D4", "00FF1020"), VehicleInfoDecoder.cvns(listOf(bytes("49 06 02 A1 B2 C3 D4 00 FF 10 20")), isCan = true))
        assertEquals(listOf("A1B2C3D4"), VehicleInfoDecoder.cvns(listOf(bytes("49 06 01 A1 B2 C3 D4")), isCan = false))
    }

    // ---- B8 mode 06 ----

    @Test
    fun `mode 06 mask lists MIDs and the next mask`() {
        assertEquals(setOf(0x01, 0x02, 0x20), Mode06.supported(bytes("46 00 C0 00 00 01"), 0x00))
        assertNull(Mode06.supported(bytes("46 20 C0 00 00 01"), 0x00))
    }

    @Test
    fun `mode 06 blocks are scaled by UAS with pass and fail`() {
        // MID 01 TID 01: 0.450 V within 0.200..1.000 V; TID 02: 30 counts above the 20 limit; unknown UAS 55 stays raw.
        val r = Mode06.results(bytes("46 01 01 0B 01 C2 00 C8 03 E8 01 02 24 00 1E 00 00 00 14 01 03 55 00 05 00 00 00 0A"), 0x01)
        assertEquals(3, r.size)
        assertEquals(0.45, r[0].value, 1e-9)
        assertEquals(0.2, r[0].min, 1e-9)
        assertEquals(1.0, r[0].max, 1e-9)
        assertEquals(UasUnit.VOLT, r[0].unit)
        assertTrue(r[0].passed)
        assertFalse(r[1].passed)
        assertNull(r[2].unit)
        assertEquals(5.0, r[2].value, 1e-9)
        assertEquals(MidGroup.OXYGEN_SENSOR, r[0].group)
    }

    @Test
    fun `signed UAS and MID groups`() {
        assertEquals(-0.01, UasTable.of(0xFD)!!.apply(0xFFF6), 1e-9)
        assertEquals(-40.0, UasTable.of(0x16)!!.apply(0), 1e-9)
        assertEquals(MidGroup.CATALYST, Mode06.groupOf(0x21))
        assertEquals(MidGroup.MISFIRE, Mode06.groupOf(0xA2))
        assertEquals(MidGroup.EGR_VVT, Mode06.groupOf(0x31))
    }

    // ---- reads over a session ----

    private class ScriptSession(private val replies: Map<String, String>, override val isCanBus: Boolean = true) : ObdSession {
        val sent = mutableListOf<String>()
        override suspend fun initialize() = AdapterInfo("fake", '6', isCanBus, 12.5)
        override suspend fun supportedPids() = emptySet<Int>()
        override suspend fun read(pid: Pid): SensorReading? = null
        override suspend fun readDtcs(kind: DtcKind) = emptyList<Dtc>()
        override suspend fun clearDtcs() = true
        override suspend fun readVin(): String? = null
        override suspend fun request(command: String, timeoutMs: Long): List<ByteArray>? {
            sent += command
            val reply = replies[command] ?: return null
            if (reply == "?") throw ObdException(ObdError.CommandFailed(command, "?"))
            return reply.split("|").map { it.replace(" ", "").hexToBytes() }
        }
    }

    // Freeze frame PIDs 02, 04, 05, 0C, 0D: mask bytes 58 18 00 00.
    private val frameReplies = mapOf(
        "020000" to "42 00 00 58 18 00 00",
        "020200" to "42 02 00 01 33",
        "020400" to "42 04 00 80",
        "020500" to "42 05 00 7B",
        "020C00" to "42 0C 00 0C 80",
        "020D00" to "42 0D 00 00",
    )

    @Test
    fun `freeze frame with frame numbers is decoded with the service 01 formulas`() = runTest {
        val ff = ObdDiagnostics(ScriptSession(frameReplies), clock = { 7L }).freezeFrame()!!
        assertEquals("P0133", ff.dtcCode)
        assertEquals(listOf("01.04", "01.05", "01.0C", "01.0D"), ff.readings.map { it.sensorId })
        assertEquals(50.2, ff.value("01.04")!!, 0.1)
        assertEquals(83.0, ff.value("01.05")!!, 1e-9)
        assertEquals(800.0, ff.value("01.0C")!!, 1e-9)
        assertEquals(0.0, ff.value("01.0D")!!, 1e-9)
    }

    @Test
    fun `freeze frame falls back to the short request form`() = runTest {
        val short = mapOf(
            "020000" to "?",
            "0200" to "42 00 58 18 00 00",
            "0202" to "42 02 01 33",
            "0204" to "42 04 80",
            "0205" to "42 05 00 7B", // this ECU still sends the frame byte
            "020C" to "42 0C 0C 80",
            "020D" to "42 0D 00",
        )
        val session = ScriptSession(short)
        val ff = ObdDiagnostics(session).freezeFrame()!!
        assertEquals("P0133", ff.dtcCode)
        assertEquals(83.0, ff.value("01.05")!!, 1e-9)
        assertEquals(800.0, ff.value("01.0C")!!, 1e-9)
        assertTrue(session.sent.none { it.length == 6 && it != "020000" })
    }

    @Test
    fun `no freeze frame stored`() = runTest {
        assertNull(ObdDiagnostics(ScriptSession(emptyMap())).freezeFrame())
        assertNull(ObdDiagnostics(ScriptSession(mapOf("020000" to "42 00 00 40 00 00 00", "020200" to "42 02 00 00 00"))).freezeFrame())
    }

    @Test
    fun `readiness, counters and negative replies over a session`() = runTest {
        val d = ObdDiagnostics(ScriptSession(mapOf("0101" to "41 01 83 07 65 21", "0130" to "41 30 03", "0131" to "41 31 00 32", "014E" to "7F 01 12")))
        assertTrue(d.readiness()!!.milOn)
        assertEquals(ClearedCounters(warmUpsSinceClear = 3, kmSinceClear = 50), d.counters())
        assertEquals(ClearedCounters(warmUpsSinceClear = 3), d.counters(setOf(0x30)))
    }

    @Test
    fun `mode 06 over CAN reads the masks then each MID, K-Line gives null`() = runTest {
        val session = ScriptSession(mapOf(
            "0600" to "46 00 C0 00 00 00",
            "0601" to "46 01 01 0B 01 C2 00 C8 03 E8",
            "0602" to "46 02 01 0B 00 64 00 C8 03 E8",
        ))
        val results = ObdDiagnostics(session).mode06()!!
        assertEquals(listOf(0x01, 0x02), results.map { it.mid })
        assertFalse(results[1].passed)
        assertNull(ObdDiagnostics(ScriptSession(emptyMap(), isCanBus = false)).mode06())
    }

    @Test
    fun `K-Line vehicle info over a session`() = runTest {
        val lines = kLineVin().joinToString("|") { it.toUnsigned().joinToString(" ") { b -> "%02X".format(b) } }
        val info = ObdDiagnostics(ScriptSession(mapOf("0902" to lines, "0906" to "49 06 01 A1 B2 C3 D4"), isCanBus = false)).vehicleInfo()
        assertEquals(vin, info.vin)
        assertEquals(listOf("A1B2C3D4"), info.cvns)
        assertEquals(emptyList<String>(), info.calibrationIds)
    }

    // ---- B4 clear refused, through the ELM327 session ----

    @Test
    fun `negative reply 7F 04 22 to a clear is a typed error`() = runTest {
        val session = Elm327Session(ElmLink(ScriptedTransport(FakeCar.replies + ("04" to "7F 04 22"))), clock = { 1_000L })
        session.initialize()
        val e = runCatching { session.clearDtcs() }.exceptionOrNull()
        assertEquals(ObdError.ClearRefused(0x22), (e as ObdException).error)
    }

    @Test
    fun `K-Line VIN through the ELM327 session`() = runTest {
        val lines = kLineVin().joinToString("\r") { it.toUnsigned().joinToString(" ") { b -> "%02X".format(b) } }
        val session = Elm327Session(ElmLink(ScriptedTransport(FakeCar.replies + ("ATDPN" to "A3") + ("0902" to lines))), clock = { 1_000L })
        session.initialize()
        assertFalse(session.isCanBus)
        assertEquals(vin, session.readVin())
    }
}
