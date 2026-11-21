package io.github.iampopovich.eculense.feature.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import org.robolectric.RuntimeEnvironment
import io.github.iampopovich.eculense.core.data.LastSeen
import io.github.iampopovich.eculense.core.model.DistanceUnit
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.UnitsSettings
import io.github.iampopovich.eculense.core.model.TripSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** C9: widget model rules and the rendered Glance tree (English resources). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetTest {
    private val min = 60_000L

    @Test
    fun `live values follow the dashboard selection, first three supported`() {
        val m = WidgetModels.live(listOf(0x05, 0x0C, 0x5E, 0x0D, 0x42), setOf(0x05, 0x0C, 0x0D, 0x42), mapOf("01.0C" to 800.0), simulated = false)
        assertEquals(listOf(0x05, 0x0C, 0x0D), m.values.map { it.code })
        assertEquals(800.0, m.values[1].value!!, 0.0)
        assertEquals(null, m.values[0].value)
    }

    @Test
    fun `idle ages are computed from the stored times`() {
        val trip = Trip(carId = 1, startedAtMs = 0, endedAtMs = 1_000, distanceKm = 12.3, fuelL = 1.0, fuelPricePerL = null,
            drivingMs = 1_000, standingMs = 0, source = TripSource.LIVE)
        val m = WidgetModels.idle(LastSeen(12.4, 10 * min, 0, 20 * min), trip, nowMs = 30 * min)
        assertEquals(20 * min, m.voltageAgeMs)
        assertEquals(10 * min, m.dtcAgeMs)
        assertEquals(30 * min - 1_000, m.tripAgeMs)
        assertFalse(m.empty)
        assertTrue(WidgetModels.idle(LastSeen(), null, 0).empty)
    }

    @Test
    fun `ages read as now, minutes, hours, days`() {
        assertEquals(Age.Now, Age.of(30_000))
        assertEquals(Age.Minutes(5), Age.of(5 * min))
        assertEquals(Age.Hours(3), Age.of(3 * 60 * min))
        assertEquals(Age.Days(3), Age.of(72 * 60 * min))
    }

    @Test
    fun `updates are immediate on connection changes and at most every 10 s with live values`() {
        val t = WidgetThrottle()
        assertTrue(t.shouldPush(0, connectionChanged = true))
        assertFalse(t.shouldPush(5_000, connectionChanged = false))
        assertTrue(t.shouldPush(6_000, connectionChanged = true))
        assertFalse(t.shouldPush(15_000, connectionChanged = false))
        assertTrue(t.shouldPush(16_000, connectionChanged = false))
    }

    @Test
    fun `sizes 2x1 to 4x2 show one to three values`() {
        assertEquals(1, WidgetSizes.valuesFor(WidgetSizes.SMALL))
        assertEquals(2, WidgetSizes.valuesFor(WidgetSizes.WIDE))
        assertEquals(3, WidgetSizes.valuesFor(WidgetSizes.LARGE))
    }

    @Test
    fun `not connected 4x2 shows voltage, code check, trip and the connect button`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 110.dp))
        setContext(RuntimeEnvironment.getApplication())
        provideComposable {
            WidgetContent(WidgetModel.Idle(voltage = 12.4, voltageAgeMs = 5 * min, dtcCount = 0, dtcAgeMs = 3 * 60 * min, tripDistance = 12.3, tripAgeMs = 72 * 60 * min))
        }
        onNode(hasText("Battery 12.4 V · 5 min ago")).assertExists()
        onNode(hasText("No codes · 3 h ago")).assertExists()
        onNode(hasText("Trip 12.3 km · 3 days ago")).assertExists()
        onNode(hasText("Connect")).assertExists()
    }

    @Test
    fun `not connected 2x1 shows only the voltage line`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(110.dp, 40.dp))
        setContext(RuntimeEnvironment.getApplication())
        provideComposable { WidgetContent(WidgetModel.Idle(voltage = 12.4, voltageAgeMs = 0, dtcCount = 2, dtcAgeMs = 0)) }
        onNode(hasText("Battery 12.4 V · just now")).assertExists()
        onNode(hasText("Codes: 2 · just now")).assertDoesNotExist()
        onNode(hasText("Connect")).assertDoesNotExist()
    }

    @Test
    fun `connected demo shows live values marked as demo`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 110.dp))
        setContext(RuntimeEnvironment.getApplication())
        provideComposable {
            WidgetContent(WidgetModels.live(listOf(0x0C, 0x0D, 0x05), emptySet(), mapOf("01.0C" to 2150.0, "01.0D" to 63.0), simulated = true))
        }
        onNode(hasText("Demo")).assertExists()
        onNode(hasText("2150 rpm")).assertExists()
        onNode(hasText("63 km/h")).assertExists()
        onNode(hasText("— °C")).assertExists()
    }

    @Test
    fun `live values and the last trip follow the user's units`() {
        val us = UnitsSettings(metricDistance = false, celsius = false)
        val m = WidgetModels.live(listOf(0x0D, 0x05), emptySet(), mapOf("01.0D" to 100.0, "01.05" to 90.0), simulated = false, units = us)
        assertEquals("mph", m.values[0].unit)
        assertEquals(100.0 / UnitsSettings.KM_PER_MILE, m.values[0].value!!, 1e-9)
        assertEquals("°F", m.values[1].unit)
        assertEquals(194.0, m.values[1].value!!, 1e-9)
        val trip = Trip(carId = 1, startedAtMs = 0, endedAtMs = 1_000, distanceKm = 16.09344, fuelL = 1.0, fuelPricePerL = null,
            drivingMs = 1_000, standingMs = 0, source = TripSource.LIVE)
        val idle = WidgetModels.idle(LastSeen(), trip, nowMs = 1_000, units = us)
        assertEquals(DistanceUnit.MI, idle.distanceUnit)
        assertEquals(10.0, idle.tripDistance!!, 1e-9)
    }

    @Test
    fun `mph and fahrenheit are rendered with their units`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 110.dp))
        setContext(RuntimeEnvironment.getApplication())
        val us = UnitsSettings(metricDistance = false, celsius = false)
        provideComposable {
            WidgetContent(WidgetModels.live(listOf(0x0D, 0x05), emptySet(), mapOf("01.0D" to 100.0, "01.05" to 90.0), simulated = false, units = us))
        }
        onNode(hasText("62 mph")).assertExists()
        onNode(hasText("194 °F")).assertExists()
    }

    @Test
    fun `idle trip line in miles`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 110.dp))
        setContext(RuntimeEnvironment.getApplication())
        provideComposable { WidgetContent(WidgetModel.Idle(voltage = 12.4, voltageAgeMs = 0, tripDistance = 10.0, distanceUnit = DistanceUnit.MI, tripAgeMs = 0)) }
        onNode(hasText("Trip 10.0 mi · just now")).assertExists()
    }
}
