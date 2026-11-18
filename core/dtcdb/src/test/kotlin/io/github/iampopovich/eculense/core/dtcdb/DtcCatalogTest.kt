package io.github.iampopovich.eculense.core.dtcdb

import io.github.iampopovich.eculense.core.datapacks.PackDb
import io.github.iampopovich.eculense.core.datapacks.PackRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.sql.ResultSet
import kotlin.io.path.createTempDirectory

private class JdbcPackDb(file: File) : PackDb {
    private val conn = DriverManager.getConnection("jdbc:sqlite:${file.path}")
    override fun <T> query(sql: String, args: List<Any?>, map: (PackRow) -> T): List<T> =
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a?.toString()) }
            st.executeQuery().use { rs ->
                val row = object : PackRow {
                    override fun string(i: Int): String? = rs.getString(i + 1)
                    override fun long(i: Int): Long? = rs.getLong(i + 1).takeUnless { rs.wasNull() }
                    override fun double(i: Int): Double? = rs.getDouble(i + 1).takeUnless { rs.wasNull() }
                }
                buildList { while (rs.next()) add(map(row)) }
            }
        }
    override fun close() = conn.close()
}

class DtcCatalogTest {
    private val dir = createTempDirectory("dtcdb").toFile()

    /** Синтетический фрагмент OBDex (структура как в generic.min.json, тексты свои). */
    private val obdexJson = """
        [{"code":"P0171","category":"powertrain","title":{"en":"System Too Lean Bank 1","de":"x"},
          "description":{"en":"Long-term trim at its upper limit.\tTab inside","de":"x"},
          "common_causes":[{"id":"a","likelihood":"high","label":{"en":"Air leak after the MAF","de":"x"}},{"id":"b","label":{"en":"Weak fuel pump"}}],
          "symptoms":[{"en":"Check engine light on","de":"x"},{"en":"Rough idle"}],
          "sources":[{"url":"https://example.org"}],"reviewed":true},
         {"code":"P0420","title":{"en":"Catalyst System Efficiency Below Threshold Bank 1"},"description":{"en":"Cat."}}]
    """.trimIndent()

    /** Синтетическая dtc_codes.db со схемой Wal33D: P1847 есть у GM-марок и в OTHER, у KIA — другой код. */
    private fun oemDb(): File = File(dir, "dtc_codes.db").also { f ->
        if (f.exists()) return@also
        DriverManager.getConnection("jdbc:sqlite:${f.path}").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE dtc_definitions (code TEXT NOT NULL, manufacturer TEXT NOT NULL, description TEXT NOT NULL, type TEXT NOT NULL, " +
                        "locale TEXT NOT NULL DEFAULT 'en', is_generic BOOLEAN DEFAULT 0, source_file TEXT, PRIMARY KEY (code, manufacturer, locale))",
                )
                st.execute(
                    "INSERT INTO dtc_definitions (code, manufacturer, description, type) VALUES " +
                        "('P1847','GM','2 3 Shift Solenoid Circuit High Voltage','P'), " +
                        "('P1847','BUICK','2-3 Shift Solenoid Circuit High Voltage','P'), " +
                        "('P1847','OTHER','Transmission Transfer Case Contact Plate ''A'' Open Circuit','P'), " +
                        "('P1121','KIA','Throttle Position Sensor Test Code','P'), " +
                        "('P0171','GENERIC','System Too Lean Bank 1','P')",
                )
            }
        }
    }

    private lateinit var oem: Wal33dOemDtc

    private fun catalog(withObdex: Boolean = true): DtcCatalog {
        val generic = if (withObdex) {
            File(dir, ObdexIndex.SOURCE).writeText(obdexJson)
            ObdexIndex.process(dir)
            ObdexReader(File(dir, ObdexIndex.INDEX))
        } else null
        oem = Wal33dOemDtc(JdbcPackDb(oemDb()))
        val builtin = mapOf("P0171" to "System Too Lean (Bank 1)", "P0300" to "Random/Multiple Cylinder Misfire Detected")
        return DtcCatalog(generic, builtin::get, oem, oem.manufacturers)
    }

    @After
    fun cleanup() {
        if (::oem.isInitialized) oem.close()
        dir.deleteRecursively()
    }

    @Test
    fun `P1847 on a Kia is not described with the GM shift-solenoid text`() {
        val info = catalog().describe("P1847", "Kia")
        assertEquals(DtcSource.OEM_UNKNOWN, info.source)
        assertEquals("Заводской код производителя, расшифровка неизвестна", info.text)
        assertFalse(info.text.contains("Shift Solenoid"))
        assertFalse(info.text.contains("Transfer Case"))
    }

    @Test
    fun `manufacturer text is shown only when the make matches, case-insensitively`() {
        val c = catalog()
        assertEquals("Throttle Position Sensor Test Code", c.describe("P1121", "Kia").title)
        assertEquals("KIA", c.describe("P1121", "киа").manufacturer)
        assertEquals("2-3 Shift Solenoid Circuit High Voltage", c.describe("P1847", "Buick").title)
        assertEquals(DtcSource.OEM_UNKNOWN, c.describe("P1121", null).source)
        assertEquals(DtcSource.OEM_UNKNOWN, c.describe("P1121", "Тестовая марка").source)
        assertEquals(DtcSource.OEM_UNKNOWN, c.describe("P1847", "Other").source)
    }

    @Test
    fun `generic code comes from OBDex with causes and symptoms`() {
        val info = catalog().describe("p0171", "Kia")
        assertEquals(DtcSource.OBDEX, info.source)
        assertEquals("System Too Lean Bank 1", info.title)
        assertEquals("Long-term trim at its upper limit. Tab inside", info.description)
        assertEquals(listOf("Air leak after the MAF", "Weak fuel pump"), info.causes)
        assertEquals(listOf("Check engine light on", "Rough idle"), info.symptoms)
        assertEquals("Catalyst System Efficiency Below Threshold Bank 1", catalog().describe("P0420", null).title)
    }

    @Test
    fun `without the OBDex pack the bundled csv is the offline fallback`() {
        val c = catalog(withObdex = false)
        assertEquals(DtcInfo("P0171", "System Too Lean (Bank 1)", DtcSource.BUILTIN), c.describe("P0171", null))
        assertEquals(DtcSource.UNKNOWN, c.describe("P0999", null).source)
        assertEquals(DtcSource.BUILTIN, catalog().describe("P0300", null).source)
    }

    @Test
    fun `sae ranges decide generic vs manufacturer-specific`() {
        listOf("P1847", "P3000", "P3399", "B1001", "B2AAA", "C1234", "U1000", "U2100").forEach { assertTrue(it, DtcCodes.isManufacturerSpecific(it)) }
        listOf("P0171", "P2096", "P3400", "B0001", "B3000", "C0035", "U0100", "U3000").forEach { assertFalse(it, DtcCodes.isManufacturerSpecific(it)) }
        assertFalse(DtcCodes.isValid("X0100"))
    }

    @Test
    fun `oem brands map from profile names`() {
        val known = setOf("KIA", "CHEVY", "MERCEDES", "VOLKSWAGEN", "OTHER")
        assertEquals("KIA", OemBrands.manufacturer("Kia", known))
        assertEquals("CHEVY", OemBrands.manufacturer("Chevrolet", known))
        assertEquals("MERCEDES", OemBrands.manufacturer("Mercedes-Benz", known))
        assertEquals("VOLKSWAGEN", OemBrands.manufacturer("VW", known))
        assertNull(OemBrands.manufacturer("Other", known))
        assertNull(OemBrands.manufacturer("Hyundai", known))
        assertNull(OemBrands.manufacturer(null, known))
    }

    @Test
    fun `parses quoted csv`() {
        val map = parseDtcCsv(sequenceOf("\"P0100\",\"Mass or Volume Air Flow Circuit Malfunction\"", "garbage"))
        assertEquals(mapOf("P0100" to "Mass or Volume Air Flow Circuit Malfunction"), map)
    }

    /** OBDEX_JSON=…/generic.min.json OEM_DTC_DB=…/dtc_codes.db — настоящие пакеты (в git не кладём). */
    @Test
    fun `real packs`() {
        val json = System.getenv("OBDEX_JSON")
        val db = System.getenv("OEM_DTC_DB")
        assumeTrue("задайте OBDEX_JSON и OEM_DTC_DB", json != null && db != null)
        File(json!!).copyTo(File(dir, ObdexIndex.SOURCE))
        val started = System.currentTimeMillis()
        ObdexIndex.process(dir)
        val index = File(dir, ObdexIndex.INDEX)
        println("obdex index: ${index.length()} bytes in ${System.currentTimeMillis() - started} ms")
        Wal33dOemDtc(JdbcPackDb(File(db!!))).use { real ->
            val c = DtcCatalog(ObdexReader(index), { null }, real, real.manufacturers)
            assertEquals("System Too Lean Bank 1", c.describe("P0171", "Kia").title)
            assertTrue(c.describe("P0171", "Kia").causes.isNotEmpty())
            assertEquals(DtcSource.OEM_UNKNOWN, c.describe("P1847", "Kia").source)
            assertEquals(DtcSource.OEM, c.describe("P1847", "Chevrolet").source)
            println("P0107: " + c.describe("P0107", "Kia").title + "; Kia OEM codes known: ${"KIA" in real.manufacturers}")
        }
    }
}
