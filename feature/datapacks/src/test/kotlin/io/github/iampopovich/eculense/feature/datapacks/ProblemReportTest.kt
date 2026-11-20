package io.github.iampopovich.eculense.feature.datapacks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/** C11: the prefilled issue carries versions and car make/model/year, never VIN, car name, address or location. */
class ProblemReportTest {
    private val facts = ReportFacts(
        appVersion = "0.2.0", flavor = "local", androidRelease = "14", sdkInt = 34, deviceModel = "Testphone X1",
        carMake = "Testmake", carModel = "Model", carYear = 2005, adapterKind = "bt", adapterVersion = "ELM327 v1.5",
        protocol = "5 (K-Line)", connection = "connected",
    )

    @Test
    fun `problem draft lists all facts`() {
        val d = ProblemReport.draft(ReportKind.PROBLEM, facts)
        assertEquals("bug", d.labels)
        listOf("App: 0.2.0 (local)", "Android: 14 (API 34)", "Device: Testphone X1", "Car: Testmake Model 2005",
            "Adapter: ELM327 v1.5 (bt)", "Protocol: 5 (K-Line)", "Connection: connected").forEach {
            assertTrue(it, d.body.contains(it))
        }
    }

    @Test
    fun `no identifying fields exist in the report`() {
        val body = ProblemReport.draft(ReportKind.IDEA, facts).body.lowercase()
        listOf("vin", "gps", "latitude", "location", "mac", "address", "name").forEach { assertFalse(it, body.contains(it)) }
    }

    @Test
    fun `missing facts read as not set or unknown`() {
        val d = ProblemReport.draft(ReportKind.IDEA, facts.copy(carMake = null, carModel = null, carYear = null, adapterKind = null, adapterVersion = null, protocol = null))
        assertTrue(d.body.contains("Car: not set"))
        assertTrue(d.body.contains("Adapter: unknown"))
        assertEquals("enhancement", d.labels)
    }

    @Test
    fun `the url opens the new issue form with title, body and label encoded`() {
        val d = ProblemReport.draft(ReportKind.PROBLEM, facts)
        val url = ProblemReport.url(d)
        assertTrue(url.startsWith("https://github.com/iampopovich/ECULense/issues/new?title="))
        assertFalse(url.contains(" ") || url.contains("\n"))
        val body = URLDecoder.decode(url.substringAfter("&body=").substringBefore("&labels="), "UTF-8")
        assertEquals(d.body, body)
    }
}
