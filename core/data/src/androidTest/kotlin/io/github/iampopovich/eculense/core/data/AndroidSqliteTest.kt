package io.github.iampopovich.eculense.core.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iampopovich.eculense.core.carscanner.CbzSettingsReader
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidSqliteTest {
    @Test
    fun readsCarsAndTripsWithCarScannerSchema() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "cs-test.db").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE cars(id INTEGER PRIMARY KEY, name TEXT NOT NULL, year INTEGER NOT NULL, settings_string TEXT)")
            db.execSQL(
                "CREATE TABLE trips (id integer primary key autoincrement not null, car_id integer, started_utc_ms integer, finished_utc_ms integer, " +
                    "distance_total_mm integer, fuel_used_ul integer, fuel_price_per_l_real float, driving_ticks integer, standing_ticks integer, total_ticks integer)",
            )
            db.execSQL("INSERT INTO cars VALUES (638000000000000001, 'Тестовая', 2010, 'b3BhcXVl')")
            db.execSQL("INSERT INTO trips (car_id, started_utc_ms, finished_utc_ms, distance_total_mm, fuel_used_ul, fuel_price_per_l_real, driving_ticks, standing_ticks, total_ticks) VALUES (638000000000000001, 1000, 61000, 5000000, 400000, 59.9, 500000000, 100000000, 600000000)")
        }
        AndroidSqlite.open(file).use { db ->
            val car = CbzSettingsReader.readCars(db).single()
            assertEquals(638_000_000_000_000_001L, car.id)
            assertEquals("Тестовая", car.name)
            val trip = CbzSettingsReader.readTrips(db).single()
            assertEquals(5.0, trip.distanceKm, 1e-9)
            assertEquals(59.9, trip.fuelPricePerL!!, 1e-9)
            assertEquals(60_000L, trip.totalMs)
        }
        file.delete()
    }
}
