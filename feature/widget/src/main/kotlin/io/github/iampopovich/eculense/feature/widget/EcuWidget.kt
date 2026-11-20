package io.github.iampopovich.eculense.feature.widget

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.DashboardPrefs
import io.github.iampopovich.eculense.core.data.LastSeen
import io.github.iampopovich.eculense.core.data.LastSeenStore
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.isSimulated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class EcuWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EcuWidget()
}

/** C9: the home-screen widget. Data comes from [WidgetRepository]; no periodic work of its own. */
class EcuWidget : GlanceAppWidget(), KoinComponent {
    override val sizeMode: SizeMode = SizeMode.Responsive(WidgetSizes.ALL)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository: WidgetRepository by inject()
        val initial = repository.current()
        provideContent {
            val pushed by repository.pushed.collectAsState()
            GlanceTheme { WidgetContent(pushed ?: initial) }
        }
    }
}

/**
 * Builds the widget model and pushes updates: at once on connection changes, at most every 10 s with live values,
 * nothing while idle. Reads only; the demo link shows its values but nothing is stored here.
 */
internal class WidgetRepository(
    private val context: Context,
    private val controller: ConnectionController,
    private val dashboard: DashboardPrefs,
    private val cars: CarRepository,
    private val lastSeen: LastSeenStore,
    private val trips: TripRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val latest = HashMap<String, Double>()
    private val throttle = WidgetThrottle()
    private val _pushed = MutableStateFlow<WidgetModel?>(null)

    /** The newest model while the app process runs; null = not computed yet (the widget loads [current]). */
    val pushed: StateFlow<WidgetModel?> = _pushed.asStateFlow()

    fun start() {
        scope.launch {
            var wasConnected: Boolean? = null
            controller.state.collect { s ->
                val connected = s is ConnectionState.Connected
                if (connected != wasConnected) {
                    wasConnected = connected
                    if (!connected) latest.clear()
                    push(connectionChanged = true)
                }
            }
        }
        scope.launch {
            controller.readings.collect { r ->
                latest[r.sensorId] = r.value
                if (controller.state.value is ConnectionState.Connected) push(connectionChanged = false)
            }
        }
    }

    private suspend fun push(connectionChanged: Boolean) {
        if (!throttle.shouldPush(clock(), connectionChanged)) return
        if (!hasWidgets()) return
        _pushed.value = current()
        runCatching { EcuWidget().updateAll(context) }
    }

    private suspend fun hasWidgets(): Boolean =
        runCatching { GlanceAppWidgetManager(context).getGlanceIds(EcuWidget::class.java).isNotEmpty() }.getOrDefault(false)

    suspend fun current(): WidgetModel {
        val s = controller.state.value
        if (s is ConnectionState.Connected) {
            return WidgetModels.live(dashboard.selection.first(), s.supportedPids, HashMap(latest), s.isSimulated)
        }
        val car = cars.activeCarId() ?: return WidgetModel.Idle()
        val seen = runCatching { lastSeen.observe(car).first() }.getOrDefault(LastSeen())
        val trip = runCatching { trips.trips(car).maxByOrNull { it.endedAtMs } }.getOrNull()
        return WidgetModels.idle(seen, trip, clock())
    }
}
