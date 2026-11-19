package io.github.iampopovich.eculense.core.vehicledb

import io.github.iampopovich.eculense.core.datapacks.PackDb
import io.github.iampopovich.eculense.core.datapacks.PackRow
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** PackDb on top of sqlite-jdbc: the same SQL as on the phone, on a synthetic database in JVM tests. */
internal class JdbcPackDb(file: File, readOnly: Boolean = true) : PackDb {
    private val conn: Connection = DriverManager.getConnection(
        "jdbc:sqlite:${file.path}",
        java.util.Properties().apply { if (readOnly) setProperty("open_mode", "1") },
    )

    override fun <T> query(sql: String, args: List<Any?>, map: (PackRow) -> T): List<T> =
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a?.toString()) }
            st.executeQuery().use { rs ->
                val row = RsRow(rs)
                buildList { while (rs.next()) add(map(row)) }
            }
        }

    override fun close() = conn.close()

    private class RsRow(private val rs: ResultSet) : PackRow {
        override fun string(i: Int): String? = rs.getString(i + 1)
        override fun long(i: Int): Long? = rs.getLong(i + 1).takeUnless { rs.wasNull() }
        override fun double(i: Int): Double? = rs.getDouble(i + 1).takeUnless { rs.wasNull() }
    }
}

/** Synthetic vehicles.sqlite with the gor3a v1.0.3 release schema and a reference record (data typed by hand, not from the release). */
internal object SyntheticVehicles {
    private val SCHEMA = listOf(
        "CREATE TABLE makes (id INTEGER PRIMARY KEY, \"group\" TEXT NOT NULL, name TEXT NOT NULL, country TEXT)",
        "CREATE TABLE models (id INTEGER PRIMARY KEY, make_id INTEGER NOT NULL REFERENCES makes(id), name TEXT NOT NULL, year_start INTEGER, year_end INTEGER)",
        "CREATE TABLE generations (id INTEGER PRIMARY KEY, model_id INTEGER NOT NULL REFERENCES models(id), name TEXT NOT NULL, year_start INTEGER, year_end INTEGER, body_type TEXT)",
        "CREATE TABLE engines (id INTEGER PRIMARY KEY, generation_id INTEGER NOT NULL REFERENCES generations(id), label TEXT NOT NULL, " +
            "fuel_type TEXT, cylinders INTEGER, displacement_cc INTEGER, power_hp REAL, torque_nm REAL, transmission TEXT, drivetrain TEXT, " +
            "zero_to_100_s REAL, top_speed_kmh REAL, fuel_economy_combined_l100 REAL, length_mm INTEGER, width_mm INTEGER, height_mm INTEGER, " +
            "wheelbase_mm INTEGER, curb_weight_kg INTEGER)",
        "CREATE TABLE engine_specs (id INTEGER PRIMARY KEY, engine_id INTEGER NOT NULL REFERENCES engines(id), key TEXT NOT NULL, value TEXT NOT NULL)",
        "CREATE INDEX idx_models_make ON models(make_id)",
        "CREATE INDEX idx_generations_model ON generations(model_id)",
        "CREATE INDEX idx_engines_generation ON engines(generation_id)",
        "CREATE INDEX idx_engine_specs_engine ON engine_specs(engine_id)",
    )

    private val DATA = listOf(
        "INSERT INTO makes VALUES (1, 'kia', 'Kia', NULL), (2, 'acura', 'Acura', NULL)",
        "INSERT INTO models VALUES (10, 1, 'Sorento', 2002, NULL), (11, 1, 'Rio', 2000, NULL), (12, 2, 'MDX', 2001, NULL)",
        "INSERT INTO generations VALUES (100, 10, 'Sorento (2006)', 2006, 2009, NULL), (101, 10, 'Sorento (2002)', 2002, 2006, NULL), (102, 10, 'Sorento (2020)', 2020, NULL, NULL)",
        "INSERT INTO engines (id, generation_id, label, fuel_type, cylinders, displacement_cc, power_hp, torque_nm, transmission, drivetrain, " +
            "fuel_economy_combined_l100, curb_weight_kg) VALUES " +
            "(1000, 101, '2.4L 16V 5MT AWD (139 HP)', 'Gasoline', 4, 2351, 139.0, 196.0, '5-Speed manual', 'All Wheel Drive', 10.9, 1838), " +
            "(1001, 101, '3.5L V6 4AT AWD (195 HP)', 'Gasoline', 6, 3497, 194.0, 294.0, '4-Speed automatic', 'All Wheel Drive', 11.9, 1917), " +
            "(1002, 101, '2.5L CRDi 4AT AWD (140 HP)', 'Diesel', 4, 2497, 140.0, 343.0, '4-Speed automatic', 'All Wheel Drive', 8.9, 1956)",
        "INSERT INTO engine_specs (engine_id, key, value) VALUES " +
            "(1001, 'ENGINE SPECS / Cylinders', 'V6'), (1001, 'ENGINE SPECS / Fuel capacity', '21.1 gallons (79.9 L)'), " +
            "(1001, 'WEIGHT SPECS / Unladen Weight', '4226.3 lbs (1917 kg)')",
    )

    fun create(file: File): File {
        file.delete()
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().use { st -> (SCHEMA + DATA).forEach(st::execute) }
        }
        return file
    }
}
