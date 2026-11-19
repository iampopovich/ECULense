package io.github.iampopovich.eculense.core.obdb

import io.github.iampopovich.eculense.core.datapacks.HttpResponse
import io.github.iampopovich.eculense.core.datapacks.PackHttp
import io.github.iampopovich.eculense.core.datapacks.PackStore
import io.github.iampopovich.eculense.core.datapacks.UrlConnectionHttp
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import kotlin.io.path.createTempDirectory

/** Synthetic signalset in v3 format (structure like OBDb, our own values). */
private val SIGNALSET = """
{ "ecu": [ { "hdr": "7E0", "type": "engine" }, { "hdr": "7E1", "type": "transmission" } ],
  "commands": [
{ "hdr": "7E0", "rax": "7E8", "cmd": {"22": "0101"}, "freq": 1,
  "signals": [ {"id": "TEST_OIL_T", "path": "Engine", "fmt": {"bix": 8, "len": 8, "max": 215, "add": -40, "unit": "celsius"}, "name": "Oil temperature"} ]},
{ "hdr": "7E1", "rax": "7E9", "cmd": {"21": "05"}, "freq": 0.5, "filter": { "to": 2009, "from": 2014 },
  "signals": [ {"id": "TEST_ATF_T", "path": "Transmission", "fmt": {"len": 8, "max": 215, "add": -40, "unit": "celsius"}, "name": "ATF temperature"},
               {"id": "TEST_GEAR", "path": "Transmission", "fmt": {"bix": 8, "len": 4, "max": 8, "unit": "gear"}, "name": "Gear"} ]},
{ "hdr": "7D4", "rax": "7DC", "cmd": {"22": "F010"}, "freq": 0.25, "filter": { "years": [2012] }, "dbgfilter": { "to": 2023 },
  "signals": [ {"id": "TEST_STEER", "path": "Control", "fmt": {"bix": 96, "len": 16, "div": 10, "sign": true, "unit": "degrees"}, "name": "Steering angle"} ]}
  ]
}
"""

private val GENERATIONS = """
references:
  - "https://example.org"

generations:
  - name: "First Generation (AA)"
    start_year: 2002
    end_year: 2009
    description: "Body-on-frame."

  - name: "Second Generation (BB)"
    start_year: 2010
    end_year: 2015

  - name: "Third Generation (CC)"
    start_year: 2016
    end_year: null
"""

/** Responds from a url → body map; everything else is 404. */
private class MapHttp(private val files: Map<String, String>) : PackHttp {
    val requested = mutableListOf<String>()
    override fun get(url: String): HttpResponse {
        requested += url
        val body = files[url] ?: throw IOException("HTTP 404: $url")
        return HttpResponse(200, body.length.toLong(), body.byteInputStream())
    }
    override fun redirectLocation(url: String): String? = null
    override fun status(url: String): Int {
        requested += url
        return if (url in files) 200 else 404
    }
}

class ObdbSignalsetsTest {
    private val root = createTempDirectory("obdb").toFile()
    private val store = PackStore(root)
    private val base = "https://raw.example"
    private fun url(path: String) = "$base/OBDb/Test-Model/main/$path"

    private val repoFiles = mapOf(
        url("generations.yaml") to GENERATIONS,
        url("signalsets/v3/default.json") to SIGNALSET,
        url("tests/test_cases/2012/command_support.yaml") to "model_year: 2012",
        url("tests/test_cases/2017/command_support.yaml") to "model_year: 2017",
    )

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun `year filter is a union of to, from and years`() {
        val f = YearFilter(from = 2014, to = 2009)
        assertTrue(f.matches(2005))
        assertFalse(f.matches(2012))
        assertTrue(f.matches(2014))
        assertTrue(YearFilter(years = setOf(2012)).matches(2012))
        assertFalse(YearFilter(years = setOf(2012)).matches(2013))
    }

    @Test
    fun `signalset v3 json is parsed with ecus, commands, formulas and filters`() {
        val s = SignalsetParser.parse(SIGNALSET)
        assertEquals(listOf("7E0", "7E1"), s.ecus.map { it.hdr })
        assertEquals(3, s.commands.size)
        val atf = s.commands[1]
        assertEquals("21 05 → 7E1", atf.label)
        assertEquals("7E9", atf.rax)
        assertEquals(listOf("TEST_ATF_T", "TEST_GEAR"), atf.signals.map { it.id })
        val oil = s.commands[0].signals.single().fmt!!
        assertEquals(8, oil.bix); assertEquals(-40.0, oil.add, 0.0); assertEquals("celsius", oil.unit)
        assertTrue(s.commands[2].signals.single().fmt!!.signed)
        assertEquals(listOf("22 0101 → 7E0", "21 05 → 7E1"), s.forYear(2005).map { it.label })
        assertEquals(listOf("22 0101 → 7E0", "22 F010 → 7D4"), s.forYear(2012).map { it.label })
    }

    @Test
    fun `generations yaml gives year ranges`() {
        val g = GenerationsParser.parse(GENERATIONS)
        assertEquals(listOf("First Generation (AA)", "Second Generation (BB)", "Third Generation (CC)"), g.map { it.name })
        assertEquals(Generation("First Generation (AA)", 2002, 2009), g[0])
        assertEquals(null, g[2].endYear)
        assertTrue(g[2].contains(2030))
        assertEquals(Generation(g[0].name, 2002, 2009), g[0])
    }

    @Test
    fun `old generation without test data is reported as not covered and nothing is stored`() = runTest {
        val http = MapHttp(repoFiles)
        val r = ObdbSignalsets(http, store, base) { 2026 }.check("Test", "Model", 2005)
        r as SignalsetResult.NotCovered
        assertEquals("First Generation (AA)", r.generation?.name)
        assertEquals("OBDb/Test-Model", r.repo)
        assertFalse(http.requested.any { it.endsWith("default.json") })
        assertEquals(null, store.installed("obdb-Test-Model"))
    }

    @Test
    fun `covered year downloads, stores and filters the signalset`() = runTest {
        val obdb = ObdbSignalsets(MapHttp(repoFiles), store, base) { 2026 }
        val r = obdb.check("test", "model", 2013) as SignalsetResult.Covered
        assertEquals("Second Generation (BB)", r.generation?.name)
        assertEquals(listOf("22 0101 → 7E0"), r.commands.map { it.label })
        assertEquals(1, r.signalCount)
        assertNotNull(store.installed("obdb-Test-Model"))
        val offline = obdb.stored("Test", "Model", 2012)!!
        assertEquals(listOf("22 0101 → 7E0", "22 F010 → 7D4"), offline.commands.map { it.label })
        assertEquals(r.version, offline.version)
    }

    @Test
    fun `missing repository and incomplete profile`() = runTest {
        val obdb = ObdbSignalsets(MapHttp(emptyMap()), store, base) { 2026 }
        assertEquals(SignalsetResult.NoRepository("OBDb/Lada-Niva-Legend"), obdb.check("Lada", "Niva Legend", 2020))
        assertEquals(SignalsetResult.NeedsProfile(listOf(ProfileField.MODEL, ProfileField.YEAR)), obdb.check("Lada", " ", null))
    }

    /** OBDB_LIVE=1 — real OBDb/Kia-Sorento over the network: BL (2005) is not covered, 2012 is. */
    @Test
    fun `live kia sorento`() = runTest {
        assumeTrue("задайте OBDB_LIVE=1", System.getenv("OBDB_LIVE") == "1")
        val obdb = ObdbSignalsets(UrlConnectionHttp(), store)
        val bl = obdb.check("Kia", "Sorento", 2005)
        assertTrue(bl.toString(), bl is SignalsetResult.NotCovered)
        val xm = obdb.check("Kia", "Sorento", 2012) as SignalsetResult.Covered
        println("live: BL=$bl; 2012 commands=${xm.commands.size} signals=${xm.signalCount} ecus=${xm.ecus.size}")
        assertTrue(xm.signalCount > 0)
    }
}
