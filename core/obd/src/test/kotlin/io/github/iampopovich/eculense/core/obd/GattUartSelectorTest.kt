package io.github.iampopovich.eculense.core.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A: which characteristics of a BLE adapter carry the ELM327 serial stream (tiers FFF0 -> 18F0 -> FFE0 -> NUS -> any). */
class GattUartSelectorTest {
    private val u = GattTables::uuid16

    @Test
    fun `OBDLink CX takes FFF0 and never the SUOTA service`() {
        val pair = GattUartSelector.select(GattTables.OBDLINK_CX)!!
        assertEquals(UartTier.FFF0, pair.tier)
        assertEquals(u(0xFFF0), pair.service)
        assertEquals(u(0xFFF1), pair.notify)
        assertEquals(u(0xFFF2), pair.write)
        assertEquals(true, pair.writeWithoutResponse)
    }

    @Test
    fun `Vgate takes 18F0 before its vendor service`() {
        val pair = GattUartSelector.select(GattTables.VGATE)!!
        assertEquals(UartTier.VGATE_18F0, pair.tier)
        assertEquals(u(0x2AF0), pair.notify)
        assertEquals(u(0x2AF1), pair.write)
    }

    @Test
    fun `Viecar takes FFF0 with a write-with-response characteristic`() {
        val pair = GattUartSelector.select(GattTables.VIECAR)!!
        assertEquals(UartTier.FFF0, pair.tier)
        assertEquals(false, pair.writeWithoutResponse)
    }

    @Test
    fun `HM-10 uses FFE1 for both directions`() {
        val pair = GattUartSelector.select(GattTables.HM10)!!
        assertEquals(UartTier.HM10_FFE0, pair.tier)
        assertEquals(u(0xFFE1), pair.notify)
        assertEquals(u(0xFFE1), pair.write)
        assertEquals(true, pair.writeWithoutResponse)
    }

    @Test
    fun `Nordic UART notifies on TX and writes to RX`() {
        val pair = GattUartSelector.select(GattTables.NORDIC_UART)!!
        assertEquals(UartTier.NORDIC_UART, pair.tier)
        assertEquals(GattUuids.NUS_TX_NOTIFY, pair.notify)
        assertEquals(GattUuids.NUS_RX_WRITE, pair.write)
    }

    @Test
    fun `an unknown vendor falls back to any writable and notifiable pair outside standard services`() {
        val pair = GattUartSelector.select(GattTables.UNKNOWN_VENDOR)!!
        assertEquals(UartTier.GENERIC, pair.tier)
        assertEquals("0000abf2-0000-1000-8000-00805f9b34fb", pair.notify.toString())
        assertEquals("0000abf1-0000-1000-8000-00805f9b34fb", pair.write.toString())
    }

    @Test
    fun `a device with only standard services is not an adapter`() {
        assertNull(GattUartSelector.select(GattTables.NOT_AN_ADAPTER))
        assertNull(GattUartSelector.select(emptyList()))
    }

    @Test
    fun `the vendor-only Vgate table still finds the E781 channel`() {
        val vendorOnly = GattTables.VGATE.filter { it.uuid != u(0x18F0) }
        val pair = GattUartSelector.select(vendorOnly)!!
        assertEquals(UartTier.GENERIC, pair.tier)
        assertEquals("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f", pair.write.toString())
    }

    @Test
    fun `a chosen pair round-trips through its text form and is checked against the table`() {
        val pair = GattUartSelector.select(GattTables.OBDLINK_CX)!!
        val parsed = GattUartPair.parse(pair.encode())
        assertEquals(pair, parsed)
        assertEquals(true, pair.isIn(GattTables.OBDLINK_CX))
        assertEquals(false, pair.isIn(GattTables.VGATE))
        assertNull(GattUartPair.parse("garbage"))
    }
}
