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
            ObdError.UsbNotOpen, ObdError.UsbDetached, ObdError.LinkLost(ObdError.NotOpen),
        )
        typed.forEach { assertTrue(it.toString(), ObdText.error(it) is UiText.Res) }
    }

    @Test
    fun `built-in endpoints are localized, devices are shown as is`() {
        val demo = AdapterEndpoint("demo", "Virtual", "Demo", EndpointLabel.DEMO)
        assertEquals(UiText.Res(R.string.obd_endpoint_demo), ObdText.endpointLabel(demo))
        assertEquals(UiText.Res(R.string.obd_endpoint_demo_detail), ObdText.endpointDetail(demo))
        val bt = AdapterEndpoint("bt", "AA:BB", "OBDII")
        assertEquals(UiText.Raw("OBDII"), ObdText.endpointLabel(bt))
        assertEquals(UiText.Raw("AA:BB"), ObdText.endpointDetail(bt))
        assertEquals(UiText.Res(R.string.obd_adapter_version_unknown), ObdText.adapterVersion(""))
    }
}
