package io.github.iampopovich.eculense.core.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleScanRulesTest {
    private fun d(address: String, name: String?, rssi: Int? = -60, vararg services: Int) =
        ScannedDevice(address, name, services.map { GattUuids.uuid16(it) }, rssi)

    @Test
    fun `adapters are recognised by advertised service or by name`() {
        assertTrue(BleScanRules.isLikelyObd(d("A", null, -70, 0xFFF0)))
        assertTrue(BleScanRules.isLikelyObd(ScannedDevice("A", "x", listOf(GattUuids.NUS_SERVICE), null)))
        assertTrue(BleScanRules.isLikelyObd(d("B", "OBDII")))
        assertTrue(BleScanRules.isLikelyObd(d("C", "IOS-Vlink")))
        assertTrue(BleScanRules.isLikelyObd(d("D", "OBDLink CX")))
        assertTrue(BleScanRules.isLikelyObd(d("E", "Viecar")))
        assertFalse(BleScanRules.isLikelyObd(d("F", "Mi Band 7", -50, 0x180F)))
        assertFalse(BleScanRules.isLikelyObd(d("G", null)))
        assertFalse(BleScanRules.isLikelyObd(d("H", "CXK speaker")))
    }

    @Test
    fun `rows put adapters first, then strongest signal, one row per address`() {
        val rows = BleScanRules.toAdapters(
            "ble",
            listOf(d("1", "Speaker", -40), d("2", "OBDII", -90), d("3", null, -50, 0x18F0), d("2", "OBDII", -80)),
        )
        // The OBD-like name comes before the stronger device that only advertises a tier UUID.
        assertEquals(listOf("2", "3", "1"), rows.map { it.endpoint.address })
        assertEquals("3", rows[1].endpoint.label) // no name: the address is shown
        assertEquals(-80, rows[0].rssi)
        assertEquals(listOf(true, true, false), rows.map { it.likelyObd })
        assertEquals(listOf(false, true, false), rows.map { it.serviceOnly })
    }

    @Test
    fun `a tier UUID without an OBD-like name is service-only and sorted after name matches`() {
        val rows = BleScanRules.toAdapters(
            "ble",
            listOf(d("u1", "Kettle", -30, 0xFFE0), d("n1", "OBDII", -95), d("n2", "Viecar", -70, 0xFFF0), d("x", "Watch", -20)),
        )
        assertEquals(listOf("n2", "n1", "u1", "x"), rows.map { it.endpoint.address })
        assertEquals(listOf(false, false, true, false), rows.map { it.serviceOnly })
        assertTrue(rows[2].likelyObd)
        assertTrue(rows.all { it.endpoint.kind == "ble" && !it.endpoint.isSimulated })
    }

    @Test
    fun `dual-mode adapters are noticed by their paired classic name`() {
        assertTrue(BleScanRules.alsoPairedAsClassic("Viecar", listOf("VIECAR")))
        assertTrue(BleScanRules.alsoPairedAsClassic("OBDII BLE", listOf("OBDII")))
        assertFalse(BleScanRules.alsoPairedAsClassic("Viecar", listOf("Headphones")))
        assertFalse(BleScanRules.alsoPairedAsClassic("", listOf("OBDII")))
    }
}
