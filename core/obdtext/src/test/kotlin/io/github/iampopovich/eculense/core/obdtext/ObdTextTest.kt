package io.github.iampopovich.eculense.core.obdtext

import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.EndpointLabel
import io.github.iampopovich.eculense.core.obd.ObdError
import io.github.iampopovich.eculense.core.obd.ObdException
import io.github.iampopovich.eculense.core.obd.StandardPids
import io.github.iampopovich.eculense.core.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdTextTest {
    @Test
    fun `every standard pid has a localized name`() {
        StandardPids.all.forEach { pid -> assertNotNull(pid.sensorId, ObdText.pidNameRes(pid.code)) }
        assertEquals(StandardPids.all.map { it.code }.toSet(), ObdText.PID_NAMES.keys)
    }

    @Test
    fun `adapter note states the version fact, never a clone verdict`() {
        val clone = io.github.iampopovich.eculense.core.obd.AdapterCapabilities(version = "ELM327 v1.5", kind = io.github.iampopovich.eculense.core.obd.AdapterKind.CLONE)
        assertEquals(UiText.Res(R.string.obd_adapter_note_clone_version, listOf("ELM327 v1.5")), ObdText.adapterNote(clone))
        val stn = io.github.iampopovich.eculense.core.obd.AdapterCapabilities(version = "ELM327 v1.4b", stnId = "STN1110 v4.0", kind = io.github.iampopovich.eculense.core.obd.AdapterKind.STN)
        assertEquals(null, ObdText.adapterNote(stn))
        assertEquals("STN1110 v4.0", ObdText.adapterChip(stn))
        assertEquals(null, ObdText.adapterChip(clone))
    }

    @Test
    fun `sensor names fall back to the id`() {
        assertEquals(UiText.Res(R.string.obd_pid_0c), ObdText.sensorName("01.0C"))
        assertEquals(UiText.Raw("01.99"), ObdText.sensorName("01.99"))
        assertEquals(UiText.Raw("22.F010"), ObdText.sensorName("22.F010"))
    }

    @Test
    fun `typed errors become resources, other errors stay raw`() {
        assertEquals(UiText.Res(R.string.obd_error_ecu_silent), ObdText.error(ObdError.EcuSilent))
        assertEquals(UiText.Res(R.string.obd_error_timeout, listOf("0100")), ObdText.error(ObdException(ObdError.Timeout("0100"))))
        assertEquals(
            UiText.Res(R.string.obd_error_link_lost, listOf(UiText.Res(R.string.obd_error_adapter_closed))),
            ObdText.error(ObdError.LinkLost(ObdError.AdapterClosed)),
        )
        assertEquals(UiText.Raw("socket closed"), ObdText.error(IllegalStateException("socket closed")))
        val typed = listOf(
            ObdError.Timeout("x"), ObdError.NoTransport("x"), ObdError.NoProtocol("x"), ObdError.EcuSilent,
            ObdError.CommandFailed("x", "y"), ObdError.AdapterClosed, ObdError.NotOpen, ObdError.NoBaudRate(listOf(1)),
            ObdError.DemoDisconnected, ObdError.NoBluetooth, ObdError.UsbNoAccess, ObdError.UsbPermissionDenied,
            ObdError.UsbNotOpen, ObdError.UsbDetached, ObdError.LinkLost(ObdError.NotOpen), ObdError.BusInitFailed,
            ObdError.AdapterNotResponding, ObdError.AdapterNoPower(4.0), ObdError.IgnitionOff(12.1),
            ObdError.NotOnAdapterWifi("192.168.0.10"), ObdError.AdapterWifiLost, ObdError.BleNoUartService,
            ObdError.BleGattError("connect", 133), ObdError.BleDisconnected,
        )
        typed.forEach { assertTrue(it.toString(), ObdText.error(it) is UiText.Res) }
    }

    @Test
    fun `connection hints name the concrete thing to check`() {
        assertEquals(UiText.Res(R.string.obd_hint_bt), ObdText.hint(ObdError.AdapterNotResponding, "bt"))
        assertEquals(UiText.Res(R.string.obd_hint_wifi), ObdText.hint(ObdError.Other("connect timed out"), "wifi"))
        assertEquals(UiText.Res(R.string.obd_hint_ignition), ObdText.hint(ObdError.IgnitionOff(12.2), "bt"))
        assertEquals(UiText.Res(R.string.obd_hint_ignition), ObdText.hint(ObdError.LinkLost(ObdError.EcuSilent), "wifi"))
        assertEquals(UiText.Res(R.string.obd_hint_no_power), ObdText.hint(ObdError.AdapterNoPower(3.9), "bt"))
        assertEquals(null, ObdText.hint(ObdError.DemoDisconnected, "demo"))
        assertEquals(UiText.Res(R.string.obd_hint_wifi), ObdText.hint(ObdError.NotOnAdapterWifi("192.168.0.10"), "wifi"))
        assertEquals(UiText.Res(R.string.obd_hint_wifi), ObdText.hint(ObdError.LinkLost(ObdError.AdapterWifiLost), "wifi"))
        assertEquals(UiText.Res(R.string.obd_hint_ble), ObdText.hint(ObdError.AdapterNotResponding, "ble"))
        assertEquals(UiText.Res(R.string.obd_hint_ble), ObdText.hint(ObdError.BleGattError("connect", 133), "ble"))
        assertEquals(UiText.Res(R.string.obd_protocol_5), ObdText.protocolName('5'))
        assertEquals(UiText.Res(R.string.obd_protocol_auto), ObdText.protocolName(null))
    }

    @Test
    fun `built-in endpoints are localized, devices are shown as is`() {
        val bt = AdapterEndpoint("bt", "AA:BB", "OBDII")
        assertEquals(UiText.Raw("OBDII"), ObdText.endpointLabel(bt))
        assertEquals(UiText.Raw("AA:BB"), ObdText.endpointDetail(bt))
        assertEquals(UiText.Res(R.string.obd_adapter_version_unknown), ObdText.adapterVersion(""))
    }
}
