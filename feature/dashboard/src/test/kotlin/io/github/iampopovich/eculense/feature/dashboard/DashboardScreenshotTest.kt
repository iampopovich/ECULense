package io.github.iampopovich.eculense.feature.dashboard

import io.github.iampopovich.eculense.core.data.ActiveAlert
import io.github.iampopovich.eculense.core.analytics.LiveAlertKind
import io.github.iampopovich.eculense.core.obd.StandardPids
import io.github.iampopovich.eculense.core.screenshots.snapshotMatrix
import io.github.iampopovich.eculense.core.ui.UiText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** C13: dashboard tiles in every value state, dark theme (the driving screen), synthetic values only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DashboardScreenshotTest {
    private fun pid(code: Int) = StandardPids.byCode(code)!!
    private val wave = List(30) { i -> (i % 10).toFloat() }

    private val state = DashboardUiState(
        connected = true,
        recording = true,
        status = UiText.Raw("Demo · ISO 15765-4 CAN · 13.9 V"),
        alerts = listOf(ActiveAlert(LiveAlertKind.OVERHEAT, 112.0)),
        tiles = listOf(
            TileState(pid(0x0C), 2150.0, "rpm", ValueStatus.LIVE, spark = wave),
            TileState(pid(0x0D), 63.0, "km/h", ValueStatus.LIVE, spark = wave),
            TileState(pid(0x05), 112.0, "°C", ValueStatus.LIVE, spark = wave, alert = true),
            TileState(pid(0x06), null, "%", ValueStatus.OPEN_LOOP),
            TileState(pid(0x2F), 41.0, "%", ValueStatus.LIVE, rangeKm = 230.0),
            TileState(pid(0x5E), 5.4, "L/h", ValueStatus.LIVE, estimated = true),
            TileState(pid(0x0F), 24.0, "°C", ValueStatus.STALE, ageSec = 45),
            TileState(pid(0x42), null, "V", ValueStatus.NOT_ANSWERING),
        ),
    )

    @Test
    fun dashboard() = snapshotMatrix("dashboard", dark = true) {
        DashboardScreen(state, onToggleRecording = {}, onConnect = {}, onCarData = {}, onDriving = {})
    }
}
