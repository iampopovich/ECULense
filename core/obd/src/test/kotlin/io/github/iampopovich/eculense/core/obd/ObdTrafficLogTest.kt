package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.DtcKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ObdTrafficLogTest {
    private val testVin = "1D4GP00R55B123456" // synthetic, from FakeCar

    @Test
    fun `escape shows control and non-ASCII bytes and round-trips`() {
        val raw = "ELM\\v<1>\r\n".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0xFC.toByte(), 0x00)
        val escaped = TrafficText.escape(String(raw, Charsets.ISO_8859_1))
        assertEquals("ELM\\\\v<3C>1>\\r\\n<FC><00>", escaped)
        assertTrue(raw.contentEquals(TrafficText.unescape(escaped)))
    }

    @Test
    fun `ring keeps only the newest entries`() {
        val log = RingTrafficLog(capacity = 3, clock = { 0L })
        repeat(5) { log.note("n$it") }
        assertEquals(listOf("n2", "n3", "n4"), log.snapshot().map { it.text })
    }

    @Test
    fun `disabled log records nothing`() {
        val log = RingTrafficLog(clock = { 0L }, enabled = false)
        log.record(TrafficDirection.TX, "ATZ\r".encodeToByteArray())
        assertTrue(log.snapshot().isEmpty())
        log.setEnabled(true)
        log.record(TrafficDirection.TX, "ATZ\r".encodeToByteArray())
        assertEquals(1, log.snapshot().size)
    }

    @Test
    fun `link records TX and RX with duration and escaped text`() = runTest {
        val log = RingTrafficLog(clock = { testScheduler.currentTime })
        val link = ElmLink(ScriptedTransport(FakeCar.replies), log, clock = { testScheduler.currentTime })
        link.send("010C")
        val entries = log.snapshot()
        assertEquals(listOf(TrafficDirection.TX, TrafficDirection.RX), entries.map { it.direction })
        assertEquals("010C\\r", entries[0].text)
        assertEquals("410C1AF8\\r\\r>", entries[1].text)
        assertEquals(0L, entries[1].durationMs)
    }

    @Test
    fun `duration counts from the command, including a slow write`() = runTest {
        val log = RingTrafficLog(clock = { testScheduler.currentTime })
        val inner = ScriptedTransport(FakeCar.replies)
        val slowWrite = object : ObdTransport by inner {
            override suspend fun write(data: ByteArray) {
                kotlinx.coroutines.delay(120) // adapters like the demo answer inside write()
                inner.write(data)
            }
        }
        ElmLink(slowWrite, log, clock = { testScheduler.currentTime }).send("010D")
        assertEquals(120L, log.snapshot().last().durationMs)
    }

    @Test
    fun `timeout and dropped late reply are visible in the log`() = runTest {
        val log = RingTrafficLog(clock = { testScheduler.currentTime })
        val link = ElmLink(CloneScenarios.lateReplyAfterTimeout(backgroundScope), log)
        runCatching { link.send("010C") }
        val directions = log.snapshot().map { it.direction }
        assertEquals(listOf(TrafficDirection.TX, TrafficDirection.NOTE, TrafficDirection.DROP), directions)
    }

    @Test
    fun `VIN in a 0902 reply is masked, frame structure stays`() = runTest {
        val log = RingTrafficLog(clock = { 0L })
        val session = Elm327Session(ElmLink(ScriptedTransport(FakeCar.replies), log))
        session.initialize()
        assertEquals(testVin, session.readVin())
        val export = log.export(ZoneOffset.UTC)
        assertFalse(export, export.contains("31443447")) // "1D4G" as hex
        assertFalse(export, export.contains(testVin))
        assertTrue(export, export.contains("014\\r0:490201******\\r1:**************\\r2:**************"))
        assertNull("masked VIN does not replay", Elm327Session(ElmLink(ReplayTransport(export))).readVin())
    }

    @Test
    fun `VIN-like ASCII anywhere is masked, hex is not`() {
        val log = RingTrafficLog(clock = { 0L })
        log.record(TrafficDirection.RX, "VIN $testVin\r7E8064100BE3FA813\r>".encodeToByteArray())
        val text = log.snapshot().single().text
        assertEquals("VIN *****************\\r7E8064100BE3FA813\\r>", text)
    }

    @Test
    fun `export parses back`() {
        val log = RingTrafficLog(clock = { 1_000L })
        log.record(TrafficDirection.TX, "010D\r".encodeToByteArray())
        log.record(TrafficDirection.RX, "410D20\r\r>".encodeToByteArray(), durationMs = 42)
        val export = log.export(ZoneOffset.UTC)
        assertTrue(export, export.startsWith(TrafficText.HEADER))
        assertTrue(export, export.contains("1970-01-01T00:00:01.000Z\tRX\t42\t410D20\\r\\r>"))
        val parsed = TrafficText.parse(export)
        assertEquals(listOf(TrafficDirection.TX, TrafficDirection.RX), parsed.map { it.direction })
        assertEquals(42L, parsed[1].durationMs)
    }

    @Test
    fun `recorded session replays with the same answers`() = runTest {
        val log = RingTrafficLog(clock = { 0L })
        val recorded = Elm327Session(ElmLink(ScriptedTransport(FakeCar.replies), log))
        val info = recorded.initialize()
        val pids = recorded.supportedPids()
        val rpm = StandardPids.byCode(0x0C)!!
        val values = List(3) { recorded.read(rpm)?.value }
        val dtcs = recorded.readDtcs(DtcKind.STORED)

        val replay = ReplayTransport(log.export(ZoneOffset.UTC))
        val replayed = Elm327Session(ElmLink(replay))
        assertEquals(info, replayed.initialize())
        assertEquals(pids, replayed.supportedPids())
        assertEquals(values, List(3) { replayed.read(rpm)?.value })
        assertEquals(dtcs, replayed.readDtcs(DtcKind.STORED))
    }

    @Test
    fun `replay scans for a command out of order and answers unknown with a question mark`() = runTest {
        val log = RingTrafficLog(clock = { 0L })
        val link = ElmLink(ScriptedTransport(FakeCar.replies), log)
        link.send("010C")
        link.send("010D")
        val replay = ElmLink(ReplayTransport(log.export(ZoneOffset.UTC)))
        assertEquals("410D20\r\r", replay.send("010D"))
        assertEquals("410C1AF8\r\r", replay.send("010C"))
        assertEquals("?\r\r", replay.send("0105"))
    }
}
