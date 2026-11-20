package io.github.iampopovich.eculense.feature.health

import io.github.iampopovich.eculense.core.analytics.HealthItem
import io.github.iampopovich.eculense.core.analytics.HealthItemId
import io.github.iampopovich.eculense.core.analytics.HealthNote
import io.github.iampopovich.eculense.core.analytics.HealthResult
import io.github.iampopovich.eculense.core.analytics.HealthStatus
import io.github.iampopovich.eculense.core.data.HealthCheckRecord
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.screenshots.snapshotMatrix
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** C13: a health check result with every traffic-light colour; synthetic values only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HealthScreenshotTest {
    private val record = HealthCheckRecord(
        id = 1, carId = 1, takenAtMs = 1_790_000_000_000L,
        result = HealthResult(
            HealthStatus.FAULT,
            listOf(
                HealthItem(HealthItemId.STORED_CODES, HealthStatus.FAULT, 1.0),
                HealthItem(HealthItemId.CHECK_ENGINE, HealthStatus.FAULT, 1.0),
                HealthItem(HealthItemId.READINESS, HealthStatus.ATTENTION, 2.0),
                HealthItem(HealthItemId.COOLANT, HealthStatus.OK, 92.0, HealthNote.RESIDUAL_HEAT),
                HealthItem(HealthItemId.VOLTAGE, HealthStatus.OK, 14.1),
                HealthItem(HealthItemId.FUEL_TRIMS, HealthStatus.ATTENTION, 11.5),
            ),
        ),
        dtcs = listOf(Dtc("P0133", DtcKind.STORED)),
        freezeFrame = null, readiness = null, counters = null, odometerKm = 120_000,
    )

    @Test
    fun healthResult() = snapshotMatrix("health_result") {
        HealthScreen(
            HealthUiState(connected = true, shown = record, history = listOf(record)),
            onRun = {}, onConnect = {}, onOpen = {}, onShare = {}, onPrint = {}, onAskAi = {},
        )
    }
}
