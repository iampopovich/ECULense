package io.github.iampopovich.eculense.core.datapacks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackCatalogTest {
    @Test
    fun `every pinned pack has https urls, size, sha256, license and attribution`() {
        assertEquals(listOf("vehicles", "obdex", "oem-dtc"), PackCatalog.all.map { it.id })
        PackCatalog.all.forEach { m ->
            assertTrue(m.id, m.files.isNotEmpty())
            m.files.forEach { f ->
                assertTrue(f.url, f.url.startsWith("https://"))
                assertNotNull(f.name, f.sizeBytes)
                assertEquals(f.name, 64, f.sha256!!.length)
            }
            assertTrue(m.id, m.license.isNotBlank() && m.attribution.isNotBlank() && m.sourceUrl.startsWith("https://github.com/"))
        }
    }

    @Test
    fun `github release packs resolve file urls for another tag without a pinned hash`() {
        val v = PackCatalog.vehicles.filesFor("v1.0.9").single()
        assertEquals("https://github.com/gor3a/vehicle-makes-models/releases/download/v1.0.9/vehicles.sqlite", v.url)
        assertNull(v.sha256)
        assertEquals(
            "https://raw.githubusercontent.com/Wal33D/dtc-database/v0.2.0/data/dtc_codes.db",
            PackCatalog.oemDtc.filesFor("v0.2.0").single().url,
        )
        assertEquals(PackCatalog.vehicles.files, PackCatalog.vehicles.filesFor("v1.0.3"))
    }

    @Test
    fun `json field version is read from meta json`() {
        val meta = """{ "generated_at": "2026-11-07T13:44:03.324Z", "license": "CC0-1.0" }"""
        assertEquals("2026-11-07T13:44:03.324Z", PackInstaller.jsonString(meta, "generated_at"))
        assertEquals("2026-11-07T13_44_03.324Z", versionDir("2026-11-07T13:44:03.324Z"))
    }
}
