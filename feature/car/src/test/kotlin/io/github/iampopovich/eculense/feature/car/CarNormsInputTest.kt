package io.github.iampopovich.eculense.feature.car

import io.github.iampopovich.eculense.core.analytics.NormKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-H: user norm input — decimal comma, open sides, typo guard. */
class CarNormsInputTest {
    @Test
    fun `parses bands with a decimal comma and open sides`() {
        assertEquals(12.4 to 13.0, CarNormsViewModel.parse(NormKey.CHARGING_DRIVING_V, "12,4", "13.0").getOrThrow())
        assertEquals(null to 200.0, CarNormsViewModel.parse(NormKey.IDLE_RPM_SWING, " ", "200").getOrThrow())
    }

    @Test
    fun `refuses empty, reversed and implausible input`() {
        listOf(Triple(NormKey.IDLE_RPM, "", ""), Triple(NormKey.IDLE_RPM, "900", "700"), Triple(NormKey.CHARGING_IDLE_V, "135", ""), Triple(NormKey.LTFT_B1, "abc", "5"))
            .forEach { (k, lo, hi) -> assertTrue("$k $lo $hi", CarNormsViewModel.parse(k, lo, hi).isFailure) }
        assertTrue(!CarNormsViewModel.editable(NormKey.HARSH_ACCEL))
    }
}
