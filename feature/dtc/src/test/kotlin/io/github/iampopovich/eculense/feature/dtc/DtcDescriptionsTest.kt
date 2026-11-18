package io.github.iampopovich.eculense.feature.dtc

import org.junit.Assert.assertEquals
import org.junit.Test

class DtcDescriptionsTest {
    @Test
    fun `parses quoted csv`() {
        val map = parseDtcCsv(sequenceOf("\"P0100\",\"Mass or Volume Air Flow Circuit Malfunction\"", "garbage"))
        assertEquals(mapOf("P0100" to "Mass or Volume Air Flow Circuit Malfunction"), map)
    }
}
