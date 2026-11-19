package io.github.iampopovich.eculense.core.carscanner

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** SQLite on the JVM (sqlite-jdbc) for tests: same contract as android.database.sqlite in :core:data. */
object JdbcSqlite : SqliteOpener {
    override fun open(file: File): SqliteSource {
        val connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        return object : SqliteSource {
            override fun rows(sql: String): List<List<Any?>> = connection.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val n = rs.metaData.columnCount
                    buildList { while (rs.next()) add((1..n).map { rs.getObject(it) }) }
                }
            }

            override fun close() = connection.close()
        }
    }
}

/**
 * Synthetic .cbz backup: settings keys in Car Scanner format + drivecyclesv2.db.bak with cars/trips tables + .brc recordings.
 * Only made-up values — real backup data never gets into the repository.
 */
class CbzWriter {
    private val settings = linkedMapOf<String, ByteArray>()
    private val cars = mutableListOf<CarScannerCar>()
    private val trips = mutableListOf<CarScannerTrip>()
    private val recordings = linkedMapOf<String, ByteArray>()
    private var withDb = true

    fun string(key: String, value: String) = apply { settings[key] = value.toByteArray(Charsets.UTF_8) }
    fun bool(key: String, value: Boolean) = apply { settings[key] = byteArrayOf(if (value) 1 else 0) }
    fun int(key: String, value: Int) = apply { settings[key] = ByteBuffer.allocate(4).putInt(value).array() }
    fun car(car: CarScannerCar) = apply { cars += car }
    fun trip(trip: CarScannerTrip) = apply { trips += trip }
    fun recording(name: String, bytes: ByteArray) = apply { recordings[name] = bytes }
    fun withoutDatabase() = apply { withDb = false }

    fun bytes(tempDir: File): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
            settings.forEach { (k, v) -> put(k, v) }
            if (withDb) put("data/user/0/com.ovz.carscanner/cache/drivecyclesv2.db.bak", database(tempDir))
            recordings.forEach { (name, data) -> put("storage/emulated/0/Android/data/com.ovz.carscanner/files/records/$name", data) }
        }
    }.toByteArray()

    private fun database(tempDir: File): ByteArray {
        val file = File.createTempFile("cbz-writer", ".db", tempDir)
        file.delete()
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE cars(id INTEGER PRIMARY KEY, name TEXT NOT NULL, year INTEGER NOT NULL, settings_string TEXT)")
                st.executeUpdate(
                    "CREATE TABLE trips (id integer primary key autoincrement not null, car_id integer, started_utc_ms integer, " +
                        "finished_utc_ms integer, distance_total_mm integer, distance_ice_mm integer, distance_ev_mm integer, " +
                        "distance_combined_mm integer, distance_unknown_mm integer, energy_recup_mwh integer, energy_used_mwh integer, " +
                        "fuel_used_ul integer, fuel_price_per_l_real float, energy_price_per_kwh_real float, driving_ticks integer, " +
                        "standing_ticks integer, total_ticks integer)",
                )
                st.executeUpdate("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            }
            c.prepareStatement("INSERT INTO cars(id, name, year, settings_string) VALUES (?, ?, ?, 'b3BhcXVl')").use { ps ->
                cars.forEach { ps.setLong(1, it.id); ps.setString(2, it.name); ps.setInt(3, it.year ?: 0); ps.executeUpdate() }
            }
            c.prepareStatement(
                "INSERT INTO trips(car_id, started_utc_ms, finished_utc_ms, distance_total_mm, fuel_used_ul, fuel_price_per_l_real, " +
                    "driving_ticks, standing_ticks, total_ticks, id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                trips.forEach { t ->
                    ps.setObject(1, t.carId); ps.setLong(2, t.startedUtcMs); ps.setLong(3, t.finishedUtcMs)
                    ps.setLong(4, t.distanceMm); ps.setLong(5, t.fuelUl); ps.setObject(6, t.fuelPricePerL)
                    ps.setLong(7, t.drivingTicks); ps.setLong(8, t.standingTicks); ps.setLong(9, t.totalTicks); ps.setObject(10, t.id)
                    ps.executeUpdate()
                }
            }
        }
        return file.readBytes().also { file.delete() }
    }
}
