package io.github.iampopovich.eculense.feature.dtc

import io.github.iampopovich.eculense.core.dtcdb.DtcInfo
import io.github.iampopovich.eculense.core.dtcdb.DtcSource
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.FreezeFrame
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.screenshots.snapshotMatrix
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** C13: the code card with its freeze frame and the action row; synthetic values only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DtcScreenshotTest {
    private val frame = FreezeFrame(
        "P0171",
        listOf(
            SensorReading("01.0C", 820.0, "rpm", 0),
            SensorReading("01.0D", 0.0, "km/h", 0),
            SensorReading("01.05", 88.0, "°C", 0),
            SensorReading("01.06", 14.8, "%", 0),
            SensorReading("01.07", 9.4, "%", 0),
        ),
    )
    private val state = DtcUiState(
        checked = true,
        canAskAi = true,
        items = listOf(
            DtcItem(
                Dtc("P0171", DtcKind.STORED),
                DtcInfo("P0171", "System Too Lean (Bank 1)", DtcSource.OBDEX, description = "The ECU sees too much air or too little fuel."),
                frame,
            ),
            DtcItem(Dtc("P0420", DtcKind.PENDING), DtcInfo("P0420", "Catalyst System Efficiency Below Threshold (Bank 1)", DtcSource.OBDEX)),
        ),
    )

    @Test
    fun dtcCard() = snapshotMatrix("dtc_card") {
        DtcScreen(state, onRead = {}, onClear = {}, onAskAi = { _, _ -> })
    }
}
