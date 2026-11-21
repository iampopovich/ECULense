package io.github.iampopovich.eculense.core.backup

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `database size counts the wal and shm files, folders are summed recursively`() {
        val db = File(tmp.root, "ecu.db").apply { writeBytes(ByteArray(1_000)) }
        File(tmp.root, "ecu.db-wal").writeBytes(ByteArray(300))
        File(tmp.root, "ecu.db-shm").writeBytes(ByteArray(32))
        File(tmp.root, "other.db").writeBytes(ByteArray(5_000))
        assertEquals(1_332L, StorageFiles.databaseBytes(db))
        val accel = File(tmp.root, "accel").apply { mkdirs() }
        File(accel, "1.csv").writeBytes(ByteArray(10))
        File(accel, "sub").mkdirs()
        File(accel, "sub/2.csv").writeBytes(ByteArray(20))
        assertEquals(30L, StorageFiles.dirBytes(accel))
        assertEquals(0L, StorageFiles.dirBytes(File(tmp.root, "missing")))
        assertEquals(0L, StorageFiles.databaseBytes(File(tmp.root, "none.db")))
    }
}
