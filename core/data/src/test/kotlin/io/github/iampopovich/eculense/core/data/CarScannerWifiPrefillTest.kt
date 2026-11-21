package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.carscanner.CbzWriter
import io.github.iampopovich.eculense.core.carscanner.JdbcSqlite
import io.github.iampopovich.eculense.core.obd.EndpointLabel
import io.github.iampopovich.eculense.core.obd.InMemoryAdapterPrefs
import io.github.iampopovich.eculense.core.obd.WifiTransportFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

/** R2.7: the Wi-Fi adapter of a Car Scanner backup becomes a ready entry on the Connect screen (synthetic 192.0.2.x). */
class CarScannerWifiPrefillTest {
    @get:Rule val tmp = TemporaryFolder()

    private val prefs = InMemoryAdapterPrefs()
    private val importer by lazy {
        CarScannerImporter({ null }, ImportSessions(), ImportDtcs(), FakeCars(), FakeTrips(), FakeUnits(), JdbcSqlite, tmp.root, InMemoryImportLedger(), prefs)
    }

    private fun backup(host: String?, port: String?): ByteArray = CbzWriter().string("SelectedBrand", "Test")
        .apply { host?.let { string("WiFiServer", it) } }
        .apply { port?.let { string("WiFiPort", it) } }
        .bytes(tmp.root)

    @Test
    fun `wifi server and port from the backup are stored and listed first by the wifi factory`() = runTest {
        val bytes = backup("192.0.2.17", "35001")
        importer.importStream({ ByteArrayInputStream(bytes) }, "backup.cbz")
        assertEquals("192.0.2.17:35001", prefs.suggestedWifi.first())

        val listed = WifiTransportFactory { prefs.suggestedWifi.first() }.discover()
        assertEquals("192.0.2.17:35001", listed.first().address)
        assertEquals(EndpointLabel.WIFI_CAR_SCANNER, listed.first().labelKey)
        assertEquals(WifiTransportFactory.DEFAULT_ADDRESS, listed[1].address)
    }

    @Test
    fun `missing or malformed host stores nothing and the default address is not listed twice`() = runTest {
        importer.importStream({ ByteArrayInputStream(backup(null, "35000")) }, "a.cbz")
        assertNull(prefs.suggestedWifi.first())
        importer.importStream({ ByteArrayInputStream(backup("192.0.2.1; rm", "35000")) }, "b.cbz")
        assertNull(prefs.suggestedWifi.first())
        importer.importStream({ ByteArrayInputStream(backup("192.0.2.5", "99999")) }, "c.cbz")
        assertNull(prefs.suggestedWifi.first())

        val defaultOnly = WifiTransportFactory { WifiTransportFactory.DEFAULT_ADDRESS }.discover()
        assertEquals(listOf(WifiTransportFactory.DEFAULT_ADDRESS), defaultOnly.map { it.address })
    }

    @Test
    fun `host without a port uses the ELM327 wifi default port`() = runTest {
        importer.importStream({ ByteArrayInputStream(backup("192.0.2.40", null)) }, "d.cbz")
        assertEquals("192.0.2.40:35000", prefs.suggestedWifi.first())
    }
}
