package io.github.iampopovich.eculense.feature.fuel

import io.github.iampopovich.eculense.core.data.FuelInsights
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.FuelFillUp
import io.github.iampopovich.eculense.core.screenshots.snapshotMatrix
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/** C13: fuel log with a summary and full-to-full rows; synthetic fill-ups and generic prices only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FuelLogScreenshotTest {
    private val base = 1_790_000_000_000L
    private val fills = List(4) { i ->
        FuelFillUp(
            id = i + 1L, carId = 1, atMs = base + i * 7 * 86_400_000L, odometerKm = 100_000L + i * 450,
            litres = 40.0 + i, pricePerL = 1.5, total = (40.0 + i) * 1.5, station = "Station ${i + 1}",
        )
    }.reversed()
    private val state = FuelLogUiState(
        loading = false,
        car = CarProfile(id = 1, name = "Test car", brand = "Testmake", model = "Model", year = 2015, currency = "EUR"),
        insights = FuelInsights.of(fills, emptyList(), correctionOn = false),
        nowMs = base + 30 * 86_400_000L,
    )

    @Test
    fun fuelLog() = snapshotMatrix("fuel_log") {
        FuelLogScreen(
            state, ZoneOffset.UTC,
            onNew = { error("not clicked") }, onEdit = { error("not clicked") }, onSave = { false },
            onDelete = {}, onCorrection = {}, onImport = {}, onMessageShown = {},
        )
    }
}
