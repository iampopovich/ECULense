package io.github.iampopovich.eculense.feature.health

import io.github.iampopovich.eculense.core.analytics.HealthItem
import io.github.iampopovich.eculense.core.analytics.HealthItemId
import io.github.iampopovich.eculense.core.analytics.HealthNote
import io.github.iampopovich.eculense.core.analytics.HealthResult
import io.github.iampopovich.eculense.core.analytics.HealthStatus
import io.github.iampopovich.eculense.core.data.HealthCheckRecord
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class HealthReportHtmlTest {
    // Fake resources: "<id>(args)" so every piece of text is traceable to a resource.
    private val str: Str = { id, args -> "R$id" + if (args.isEmpty()) "" else args.joinToString(",", "(", ")") }

    private val record = HealthCheckRecord(
        id = 1, carId = 1, takenAtMs = 1_795_200_000_000L,
        result = HealthResult(
            HealthStatus.FAULT,
            listOf(
                HealthItem(HealthItemId.STORED_CODES, HealthStatus.FAULT, 1.0),
                HealthItem(HealthItemId.COOLANT, HealthStatus.OK, 104.0, HealthNote.RESIDUAL_HEAT),
            ),
        ),
        dtcs = listOf(Dtc("P0133", DtcKind.STORED)),
        freezeFrame = null, readiness = null, counters = null, odometerKm = 154_000,
    )

    private fun html(car: String = "Lada <Vesta>") =
        HealthReportHtml.build(str, record, car, listOf(ReportFinding("Warning", "Battery", "Low")), { it.name }, { it }, Locale.ENGLISH, ZoneOffset.UTC)

    @Test
    fun `the report is self-contained with light and dark styles`() {
        val h = html()
        assertTrue(h.startsWith("<!DOCTYPE html>"))
        assertTrue(h.contains("prefers-color-scheme: dark"))
        assertFalse(h.contains("http://") || h.contains("https://") || h.contains("<script") || h.contains("<link") || h.contains("<img"))
    }

    @Test
    fun `texts come from resources, values and codes are present, input is escaped`() {
        val h = html()
        assertTrue(h.contains("R${R.string.health_report_title}"))
        assertTrue(h.contains("R${R.string.health_verdict_fault}"))
        assertTrue(h.contains("P0133"))
        assertTrue(h.contains("R${R.string.health_note_residual_heat}"))
        assertTrue(h.contains("R${R.string.health_mileage_value}(154000)"))
        assertTrue(h.contains("Lada &lt;Vesta&gt;"))
        assertFalse(h.contains("<Vesta>"))
        assertTrue(h.contains("20.11.2026"))
    }

    @Test
    fun `no VIN or location fields exist in the report`() {
        val h = html().lowercase()
        assertFalse(h.contains("vin"))
        assertFalse(h.contains("latitude") || h.contains("lat=") || h.contains("gps"))
    }

    @Test
    fun `the AI prefill names the verdict, the bad items and the codes`() {
        val p = aiPrompt(str, record, "20.11.2026")
        assertTrue(p, p.startsWith("R${R.string.health_ai_prompt}(20.11.2026,R${R.string.health_verdict_fault},"))
        assertTrue(p, p.contains("P0133"))
    }
}
