package io.github.iampopovich.eculense.feature.connect

import io.github.iampopovich.eculense.core.obd.AdapterCapabilities
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.PollStats
import io.github.iampopovich.eculense.core.obd.VehicleInfo
import io.github.iampopovich.eculense.core.screenshots.snapshotMatrix
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** C13: the adapter and ECU card of a K-Line connection; synthetic adapter, no real address. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ConnectScreenshotTest {
    private val endpoint = AdapterEndpoint("demo", "demo", "Demo")
    private val state = ConnectionState.Connected(
        endpoint,
        AdapterInfo("ELM327 v1.5", '5', false, 12.6),
        setOf(0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0F, 0x11),
        AdapterCapabilities(version = "ELM327 v1.5"),
    )

    @Test
    fun adapterCard() = snapshotMatrix("connect_adapter") {
        ConnectScreen(
            state, endpoints = listOf(endpoint), onConnect = {}, onDisconnect = {}, onRefresh = {},
            pollStats = PollStats(cycles = 40, cycleMsP50 = 1_700, pidMsP50 = emptyMap()),
            vehicleInfo = VehicleInfo(calibrationIds = listOf("TESTCAL0001")),
        )
    }
}
