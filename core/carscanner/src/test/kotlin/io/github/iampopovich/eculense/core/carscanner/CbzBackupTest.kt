package io.github.iampopovich.eculense.core.carscanner

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CbzBackupTest {
    @Test
    fun `visits only brc files`() {
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
                put("Use_km", byteArrayOf(1))
                put("storage/emulated/0/Android/data/com.ovz.carscanner/files/records/2026-01-02 12-00-00.brc", byteArrayOf(1, 2))
                put("storage/emulated/0/Android/data/com.ovz.carscanner/files/records/2026-05-09 10-00-00.brc", byteArrayOf(3))
            }
        }.toByteArray()
        val seen = mutableListOf<Pair<String, Int>>()
        CbzBackup.forEachRecording(ByteArrayInputStream(zip)) { name, bytes -> seen += name to bytes.size }
        assertEquals(listOf("2026-01-02 12-00-00.brc" to 2, "2026-05-09 10-00-00.brc" to 1), seen)
    }
}
