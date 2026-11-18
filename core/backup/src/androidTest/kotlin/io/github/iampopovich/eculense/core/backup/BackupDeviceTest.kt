package io.github.iampopovich.eculense.core.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.github.iampopovich.eculense.core.data.ImportProgress
import io.github.iampopovich.eculense.core.data.ImportReport
import io.github.iampopovich.eculense.core.data.ImportStatus
import io.github.iampopovich.eculense.core.data.InMemoryImportLedger
import io.github.iampopovich.eculense.core.database.EcuDatabase
import io.github.iampopovich.eculense.core.database.ReadingEntity
import io.github.iampopovich.eculense.core.database.SessionEntity
import io.github.iampopovich.eculense.core.database.TripEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Воркер импорта через work-testing (импортёр — фейк) и копия ECULense на настоящей Room-базе устройства. */
@RunWith(AndroidJUnit4::class)
class BackupDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val history = InMemoryImportLedger()

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun koin(runner: ImportRunner) {
        startKoin { modules(module { single { runner } }) }
    }

    @Test
    fun workerRunsTheImportAndRecordsHistory() = runBlocking {
        koin(ImportRunner({ _, _, p -> p(ImportProgress(1, 2)); p(ImportProgress(2, 2)); ImportReport(1, 1, emptyList(), tripsImported = 3) }, history))
        val worker = TestListenableWorkerBuilder<CarScannerImportWorker>(context)
            .setInputData(workDataOf("uri" to "content://test/b.cbz", "name" to "Тестовая.cbz"))
            .build()
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        val row = history.history.value.single()
        assertEquals(ImportStatus.DONE, row.status)
        assertEquals(3, row.tripsAdded)
    }

    @Test
    fun failedImportIsAFailureWithMessage() = runBlocking {
        koin(ImportRunner({ _, _, _ -> error("Не удалось открыть файл") }, history))
        val worker = TestListenableWorkerBuilder<CarScannerImportWorker>(context)
            .setInputData(workDataOf("uri" to "content://test/b.cbz", "name" to "b.cbz"))
            .build()
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals("Не удалось открыть файл", result.outputData.getString("error"))
        assertEquals(ImportStatus.FAILED, history.history.value.single().status)
    }

    @Test
    fun roomDatabaseRoundTripKeepsCounts() = runBlocking {
        val srcName = "backup-src-test.db"
        val dstName = "backup-dst-test.db"
        listOf(srcName, dstName).forEach { context.deleteDatabase(it) }
        val src = Room.databaseBuilder(context, EcuDatabase::class.java, srcName).addCallback(EcuDatabase.defaultCar).build()
        val dir = File(context.cacheDir, "backup-device-test").apply { deleteRecursively(); mkdirs() }
        try {
            val s = src.sessionDao().insertSession(SessionEntity(name = "Тестовая", startedAtMs = 1, endedAtMs = 2, source = "LIVE", vin = null, carId = 1))
            src.sessionDao().insertReadings(List(500) { ReadingEntity(sessionId = s, sensorId = "01.0C", value = 800.0 + it, unit = "rpm", timestampMs = it.toLong()) })
            src.tripDao().insertAll(listOf(TripEntity(carId = 1, startedAtMs = 1, endedAtMs = 2, distanceMm = 3, fuelUl = 4, fuelPricePerL = null, drivingMs = 1, standingMs = 0, source = "LIVE", externalId = "session:1")))
            val out = ByteArrayOutputStream()
            val export = EcuBackup(listOf(RoomDbSource { src }), emptyMap(), File(dir, "ds"), dir, EcuDatabase.VERSION, "test", {})
            val manifest = export.export(out, includeAiChat = false)
            assertEquals(EcuDatabase.VERSION, manifest.schemaVersion)

            val target = context.getDatabasePath(dstName)
            EcuBackup(emptyList(), mapOf("ecu.db" to target), File(dir, "ds2"), dir, EcuDatabase.VERSION, "test", {})
                .restore(ByteArrayInputStream(out.toByteArray()))
            val dst = Room.databaseBuilder(context, EcuDatabase::class.java, dstName).addMigrations(*EcuDatabase.MIGRATIONS).build()
            try {
                assertEquals(500, dst.sessionDao().readings(s).size)
                assertEquals(1, dst.tripDao().forCar(1).size)
                assertEquals(listOf(EcuDatabase.DEFAULT_CAR_ID), dst.carDao().all().map { it.id })
            } finally {
                dst.close()
            }
        } finally {
            src.close()
            listOf(srcName, dstName).forEach { context.deleteDatabase(it) }
            dir.deleteRecursively()
        }
    }
}
