package io.github.iampopovich.eculense.core.carscanner

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RealBackupSmokeTest {
    @Test
    fun `every recording in a real backup parses`() {
        val path = System.getenv("CARSCANNER_BACKUP")
        assumeTrue("задайте CARSCANNER_BACKUP=/путь/к/бэкапу.cbz", path != null)
        var files = 0; var points = 0L; var dtc = 0; var truncated = 0
        val failures = mutableListOf<String>()
        File(path!!).inputStream().buffered().use { input ->
            CbzBackup.forEachRecording(input) { name, bytes ->
                files++
                runCatching {
                    val reader = BrcReader(bytes)
                    reader.records().forEach { r ->
                        if (r is BrcRecord.DataPoint) points++
                        if (r is BrcRecord.DtcEvent) dtc++
                    }
                    if (reader.truncated) truncated++
                }.onFailure { failures += "$name: ${it.message}" }
            }
        }
        println("files=$files points=$points dtcEvents=$dtc truncated=$truncated failures=$failures")
        assertEquals(emptyList<String>(), failures)
    }
}
