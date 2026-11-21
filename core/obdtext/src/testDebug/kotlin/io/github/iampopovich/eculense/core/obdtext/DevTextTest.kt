package io.github.iampopovich.eculense.core.obdtext

import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.EndpointLabel
import io.github.iampopovich.eculense.core.obd.ObdError
import io.github.iampopovich.eculense.core.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

/** R-I: developer endpoint text exists only in debug builds (src/debug resources). */
class DevTextTest {
    @Test
    fun `developer endpoints are localized in debug builds`() {
        val demo = AdapterEndpoint("demo", "Virtual", "Demo", EndpointLabel.DEMO)
        assertEquals(UiText.Res(R.string.obd_endpoint_demo), ObdText.endpointLabel(demo))
        assertEquals(UiText.Res(R.string.obd_endpoint_demo_detail), ObdText.endpointDetail(demo))
        val emulator = AdapterEndpoint("wifi", "10.0.2.2:35000", "emu", EndpointLabel.PC_EMULATOR)
        assertEquals(UiText.Res(R.string.obd_endpoint_pc_emulator), ObdText.endpointLabel(emulator))
        assertEquals(UiText.Raw("10.0.2.2:35000"), ObdText.endpointDetail(emulator))
        assertEquals(UiText.Res(R.string.obd_error_demo_disconnected), ObdText.error(ObdError.DemoDisconnected))
    }
}
