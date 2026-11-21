package io.github.iampopovich.eculense.core.aitools

import io.github.iampopovich.eculense.core.analytics.Norm
import io.github.iampopovich.eculense.core.analytics.NormKey
import io.github.iampopovich.eculense.core.analytics.NormSource
import io.github.iampopovich.eculense.core.analytics.Severity
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-H: the AI sees what a finding was compared with, with the norm's source and sample count, in English. */
class NormJsonTest {
    @Test
    fun `finding json carries compared_with with value, source and sampleCount`() {
        val j = findingJson(
            "idle-rpm", Severity.WARNING, "Idle speed", mapOf("Compared with" to "x", "Recent recordings (median)" to "540 rpm"),
            listOf(Norm(NormKey.IDLE_RPM, 600.0, 800.0, NormSource.BASELINE, center = 700.0, sampleCount = 9), Norm(NormKey.IDLE_RPM_SWING, null, 200.0, NormSource.GENERIC)),
        )
        val list = j.getValue("compared_with").jsonArray.map { it.jsonObject }
        assertEquals("600…800 rpm", list[0].getValue("value").jsonPrimitive.content)
        assertEquals("9", list[0].getValue("sampleCount").jsonPrimitive.content)
        assertTrue(list[0].getValue("source").jsonPrimitive.content.startsWith("own baseline"))
        assertTrue(list[1].getValue("source").jsonPrimitive.content.startsWith("generic"))
        assertEquals(setOf("Recent recordings (median)"), j.getValue("evidence").jsonObject.keys)
    }
}
