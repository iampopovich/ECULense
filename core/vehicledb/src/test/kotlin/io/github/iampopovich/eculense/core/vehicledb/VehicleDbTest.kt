package io.github.iampopovich.eculense.core.vehicledb

import io.github.iampopovich.eculense.core.model.DriveType
import io.github.iampopovich.eculense.core.model.FuelType
import io.github.iampopovich.eculense.core.model.Gearbox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempFile

class VehicleDbTest {
    private val file = SyntheticVehicles.create(createTempFile("vehicles", ".sqlite").toFile())
    private val db = VehicleDb(JdbcPackDb(file))

    @After
    fun close() {
        db.close()
        file.delete()
    }

    @Test
    fun `schema of the release matches what the code reads`() {
        assertEquals(emptyList<String>(), db.schemaProblems())
    }

    @Test
    fun `missing column is reported, not crashed on`() {
        val other = createTempFile("broken", ".sqlite").toFile()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${other.path}").use { c ->
            c.createStatement().execute("CREATE TABLE makes (id INTEGER PRIMARY KEY, name TEXT)")
        }
        VehicleDb(JdbcPackDb(other)).use { broken ->
            val problems = broken.schemaProblems()
            assertTrue(problems.toString(), "нет колонки makes.group" in problems && "нет таблицы engines" in problems)
        }
        other.delete()
    }

    @Test
    fun `step picker walks make, model, generation, engine with search`() {
        assertEquals(listOf("Acura", "Kia"), db.makes().map { it.name })
        assertEquals(listOf("Kia"), db.makes("ki").map { it.name })
        assertEquals("Kia", db.makeByName("KIA")?.name)
        val kia = db.makeByName("kia")!!
        assertEquals(listOf("Sorento"), db.models(kia.id, "sor").map { it.name })
        val gens = db.generations(db.models(kia.id, "Sorento").single().id)
        assertEquals(listOf("2002–2006", "2006–2009", "2020–н. в."), gens.map { it.years })
        assertEquals(listOf("3.5L V6 4AT AWD (195 HP)"), db.engines(gens.first().id, "V6").map { it.label })
    }

    @Test
    fun `reference record Kia Sorento 2002-2006 3_5 V6 gives the G6CU figures`() {
        val gen = db.generations(10).first { it.name == "Sorento (2002)" }
        val e = db.engine(db.engines(gen.id).single { it.label == "3.5L V6 4AT AWD (195 HP)" }.id)!!
        assertEquals(3497, e.displacementCc)
        assertEquals(3.5, e.engineVolumeL!!, 0.0)
        assertEquals(6, e.cylinders)
        assertEquals(194.0, e.powerHp!!, 0.0)
        assertEquals(294.0, e.torqueNm!!, 0.0)
        assertEquals(79.9, e.tankL!!, 0.0)
        assertEquals(1917, e.curbWeightKg)
        assertEquals(FuelType.PETROL, e.fuel)
        assertEquals(Gearbox.AUTOMATIC, e.gearbox)
        assertEquals(DriveType.ALL, e.drive)
    }

    @Test
    fun `spec strings map to profile enums`() {
        assertEquals(79.9, VehicleSpecs.tankLitres("21.1 gallons (79.9 L)")!!, 0.0)
        assertEquals(55.0, VehicleSpecs.tankLitres("14.5 gallons (55 L)")!!, 0.0)
        assertEquals(Gearbox.MANUAL, VehicleSpecs.gearbox("6-speed manual"))
        assertEquals(Gearbox.CVT, VehicleSpecs.gearbox("CVT"))
        assertEquals(Gearbox.ROBOT, VehicleSpecs.gearbox("7-speed dual-clutch automatic"))
        assertEquals(Gearbox.AUTOMATIC, VehicleSpecs.gearbox("8-speed automatic (Steptronic)"))
        assertEquals(FuelType.DIESEL, VehicleSpecs.fuel("Diesel"))
        assertEquals(FuelType.HYBRID, VehicleSpecs.fuel("Mild Hybrid Diesel"))
        assertEquals(FuelType.LPG, VehicleSpecs.fuel("Liquefied Petroleum Gas (LPG)"))
        assertEquals(FuelType.PETROL, VehicleSpecs.fuel("Premium Gasoline"))
        assertEquals(DriveType.FRONT, VehicleSpecs.drive("Front Wheel Drive"))
        assertEquals(DriveType.REAR, VehicleSpecs.drive("Rear Wheel Drive"))
    }

    /** Real release: VEHICLES_DB=/path/to/vehicles.sqlite ./gradlew :core:vehicledb:testDebugUnitTest. Not committed to git. */
    @Test
    fun `real release db contains the reference record`() {
        val path = System.getenv("VEHICLES_DB")
        assumeTrue("задайте VEHICLES_DB", path != null)
        VehicleDb(JdbcPackDb(File(path!!))).use { real ->
            assertEquals(emptyList<String>(), real.schemaProblems())
            val kia = assertNotNullAnd(real.makeByName("Kia"))
            val sorento = real.models(kia.id, "Sorento").first { it.name == "Sorento" }
            val gen = real.generations(sorento.id).first { it.yearStart == 2002 }
            assertEquals("2002–2006", gen.years)
            val e = real.engine(real.engines(gen.id).single { it.label == "3.5L V6 4AT AWD (195 HP)" }.id)!!
            assertEquals(3497, e.displacementCc)
            assertEquals(194.0, e.powerHp!!, 0.0)
            assertEquals(294.0, e.torqueNm!!, 0.0)
            assertEquals(79.9, e.tankL!!, 0.0)
            assertEquals(1917, e.curbWeightKg)
            println("real vehicles db: makes=${real.makes().size}")
        }
    }

    private fun <T> assertNotNullAnd(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
