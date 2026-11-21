package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.analytics.EngineTraits
import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.FindingKey
import io.github.iampopovich.eculense.core.analytics.FindingText
import io.github.iampopovich.eculense.core.analytics.FuelKind
import io.github.iampopovich.eculense.core.analytics.HealthItem
import io.github.iampopovich.eculense.core.analytics.HealthItemId
import io.github.iampopovich.eculense.core.analytics.HealthStatus
import io.github.iampopovich.eculense.core.analytics.Norm
import io.github.iampopovich.eculense.core.analytics.NormKey
import io.github.iampopovich.eculense.core.analytics.NormSource
import io.github.iampopovich.eculense.core.analytics.O2Kind
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.analytics.Trait
import io.github.iampopovich.eculense.core.database.FindingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-H: norms survive the finding payload, the health item codec and the car_norms row form. */
class CarNormsCodecTest {
    private val norm = Norm(NormKey.CHARGING_DRIVING_V, 12.3, 12.9, NormSource.BASELINE, center = 12.6, mad = 0.05, sampleCount = 14)

    @Test
    fun `finding payload v3 keeps the norms, a v2 row is stale and gets regenerated`() {
        val f = Finding("voltage-low", Severity.OK, FindingText(FindingKey.TITLE_VOLTAGE_LOW), FindingText(FindingKey.VOLTAGE_NORM_OK), norms = listOf(norm))
        val back = f.toEntity(1, 5).toModel()
        assertEquals(listOf(norm.copy(confidence = 1.0, updatedAtMs = 0)), back.finding.norms.map { it.copy(confidence = 1.0, updatedAtMs = 0) })
        assertFalse(back.stale)
        val v2 = FindingEntity(carId = 1, ruleId = "voltage-low", severity = "OK", title = "t", message = "m",
            evidenceJson = """{"v":2,"title":{"k":"TITLE_VOLTAGE_LOW"},"message":[{"k":"VOLTAGE_LOW_OK","a":[{"t":"n","v":13.2,"d":1}]}],"evidence":[],"sessions":[]}""",
            periodFromMs = null, periodToMs = null, createdAtMs = 1)
        assertTrue(v2.toModel().stale)
        assertFalse(v2.toModel().finding.isLegacyText)
    }

    @Test
    fun `health item keeps its norm, old rows without it still decode`() {
        val items = listOf(HealthItem(HealthItemId.VOLTAGE, HealthStatus.OK, 12.6, norm = norm), HealthItem(HealthItemId.IDLE, HealthStatus.OK, 720.0))
        val back = HealthCodec.decodeItems(HealthCodec.encodeItems(items))
        assertEquals(NormSource.BASELINE, back[0].norm?.source)
        assertEquals(14, back[0].norm?.sampleCount)
        assertEquals(12.9, back[0].norm?.high!!, 1e-9)
        assertEquals(null, back[1].norm)
        assertEquals(HealthStatus.OK, HealthCodec.decodeItems("VOLTAGE=OK|13.9|CHARGING_VOLTAGE").single().status)
    }

    @Test
    fun `traits round trip through car_norms rows`() {
        val t = EngineTraits(fuel = Trait(FuelKind.DIESEL, NormSource.ECU), o2 = Trait(O2Kind.WIDEBAND, NormSource.ECU), forcedInduction = Trait(true, NormSource.ECU), hasMaf = Trait(true, NormSource.BASELINE))
        val back = NormCodec.traits(NormCodec.traitRows(7, t))
        assertEquals(FuelKind.DIESEL, back.fuel?.value)
        assertEquals(O2Kind.WIDEBAND, back.o2?.value)
        assertEquals(true, back.forcedInduction?.value)
        assertEquals(NormSource.BASELINE, back.hasMaf?.source)
        assertEquals(norm.key, NormCodec.norm(NormCodec.toEntity(7, norm))?.key)
    }
}
