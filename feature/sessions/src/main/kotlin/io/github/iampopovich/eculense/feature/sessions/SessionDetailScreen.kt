package io.github.iampopovich.eculense.feature.sessions

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import io.github.iampopovich.eculense.core.analytics.TripEventType
import io.github.iampopovich.eculense.core.data.TripEvent
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.obdtext.ObdText
import io.github.iampopovich.eculense.core.ui.CarNames
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** B10: events are shown with this window of data around them. */
internal const val EVENT_WINDOW_MS = 2 * 60_000L

/** Time range ±[EVENT_WINDOW_MS] around [atMs]; null (whole recording) when [atMs] is null. */
internal fun eventRange(atMs: Long?): LongRange? = atMs?.let { (it - EVENT_WINDOW_MS)..(it + EVENT_WINDOW_MS) }

/** Points within ±[EVENT_WINDOW_MS] of [atMs]; everything when [atMs] is null. */
internal fun windowed(points: List<SensorReading>, atMs: Long?): List<SensorReading> =
    eventRange(atMs)?.let { r -> points.filter { it.timestampMs in r } } ?: points

/** «+3:12»: offset of an event from the recording start. */
internal fun offsetLabel(atMs: Long, startMs: Long?): String {
    val s = ((atMs - (startMs ?: atMs)) / 1000).coerceAtLeast(0)
    return "+%d:%02d".format(s / 60, s % 60)
}

@Composable
internal fun SessionDetailRoute(sessionId: Long, viewModel: SessionDetailViewModel = koinViewModel { parametersOf(sessionId) }) {
    val sensors by viewModel.sensors.collectAsStateWithLifecycle()
    val sensor by viewModel.selected.collectAsStateWithLifecycle()
    val focus by viewModel.focus.collectAsStateWithLifecycle()
    val series by viewModel.series.collectAsStateWithLifecycle()
    val startMs by viewModel.startMs.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val cars by viewModel.cars.collectAsStateWithLifecycle()
    val carId by viewModel.carId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var moved by remember { mutableStateOf<String?>(null) }
    val modelProducer = remember { CartesianChartModelProducer() }
    val points = series?.takeIf { it.sensorId == sensor }?.points.orEmpty()

    LaunchedEffect(points) {
        if (points.size >= 2) {
            val t0 = points.first().tMs
            modelProducer.runTransaction {
                lineModel { series(x = points.map { (it.tMs - t0) / 1000.0 }, y = points.map { it.value }) }
            }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Default sensor goes first, otherwise the selected chip may end up off-screen.
            sensors.sortedBy { it != defaultSensor(sensors) }.forEach { id ->
                val label = ObdText.sensorName(id).asString()
                FilterChip(selected = id == sensor, onClick = { viewModel.select(id) }, label = { Text(label) })
            }
        }
        if (focus != null) {
            Text(stringResource(R.string.sessions_event_window), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (points.size >= 2) {
            SessionChart(modelProducer, sensor, series?.unit.orEmpty())
        } else if (loading) {
            Text(stringResource(R.string.sessions_loading))
        } else {
            Text(stringResource(R.string.sessions_too_few_points))
        }
        if (events.isNotEmpty()) EventList(events, startMs, focus) { viewModel.toggleFocus(it) }
        val navigator = io.github.iampopovich.eculense.core.navigation.LocalNavigator.current
        if (sensors.any { it == "01.06" || it == "01.08" }) {
            OutlinedButton(onClick = { navigator.navigate(io.github.iampopovich.eculense.core.navigation.EngineMapDestination(sessionId)) }) {
                Text(stringResource(R.string.sessions_engine_map))
            }
        }
        val chooserTitle = stringResource(R.string.sessions_export_chooser)
        Button(onClick = {
            scope.launch {
                val uri = viewModel.exportCsv()
                val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, chooserTitle))
            }
        }) { Text(stringResource(R.string.sessions_share_csv)) }
        if (cars.size > 1) {
            OutlinedButton(onClick = { picking = true }) { Text(stringResource(R.string.sessions_reassign)) }
            cars.firstOrNull { it.id == carId }?.let {
                Text(stringResource(R.string.sessions_car_now, CarNames.display(it)), style = MaterialTheme.typography.bodyMedium)
            }
        }
        moved?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
    if (picking) {
        val movedText = stringResource(R.string.sessions_reassigned)
        ReassignSheet(cars, carId, onDismiss = { picking = false }) { car, name ->
            picking = false
            viewModel.moveTo(car)
            moved = movedText.format(name)
        }
    }
}

/**
 * Line chart of the decimated series: a tap or drag shows the value at the point (Vico default marker, value with the
 * user's unit), the vertical axis is titled «sensor, unit».
 */
@Composable
private fun SessionChart(modelProducer: CartesianChartModelProducer, sensorId: String?, unit: String) {
    val name = sensorId?.let { ObdText.sensorName(it).asString() }.orEmpty()
    val axisTitle = if (unit.isBlank()) name else stringResource(R.string.sessions_axis_title, name, unit)
    val textColor = MaterialTheme.colorScheme.onSurface
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    val marker = rememberDefaultCartesianMarker(
        label = rememberTextComponent(
            style = labelStyle,
            padding = Insets(6.dp, 3.dp),
            background = rememberShapeComponent(fill = Fill(MaterialTheme.colorScheme.surfaceContainerHighest), shape = RoundedCornerShape(6.dp)),
        ),
        valueFormatter = DefaultCartesianMarker.ValueFormatter.default(decimalCount = 1, suffix = if (unit.isBlank()) "" else " $unit"),
    )
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(),
            startAxis = VerticalAxis.rememberStart(
                titleComponent = rememberTextComponent(style = MaterialTheme.typography.labelMedium.copy(color = textColor)),
                title = { axisTitle },
            ),
            bottomAxis = HorizontalAxis.rememberBottom(),
            marker = marker,
            getXStep = { _, minX, maxX -> niceXStepSeconds(maxX - minX) },
        ),
        modelProducer = modelProducer,
        modifier = Modifier.fillMaxWidth().height(260.dp),
    )
}

@Composable
private fun EventList(events: List<TripEvent>, startMs: Long?, focus: Long?, onFocus: (Long) -> Unit) {
    val time = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    Text(stringResource(R.string.sessions_events_title), style = MaterialTheme.typography.titleMedium)
    events.forEach { e ->
        val name = stringResource(if (e.type == TripEventType.STALL) R.string.sessions_event_stall else R.string.sessions_event_mark)
        val at = time.format(Instant.ofEpochMilli(e.atMs).atZone(ZoneId.systemDefault()))
        ListItem(
            headlineContent = { Text(name) },
            supportingContent = { Text(stringResource(R.string.sessions_event_time, at, offsetLabel(e.atMs, startMs))) },
            trailingContent = { Text(stringResource(if (focus == e.atMs) R.string.sessions_event_show_all else R.string.sessions_event_show_window)) },
            modifier = Modifier.clickable { onFocus(e.atMs) },
        )
    }
}

/** C8: compact rows in a bottom sheet; the current car is marked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReassignSheet(cars: List<CarProfile>, current: Long?, onDismiss: () -> Unit, onPick: (Long, String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(stringResource(R.string.sessions_reassign_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            cars.forEach { car ->
                val name = CarNames.display(car)
                ListItem(
                    leadingContent = { RadioButton(selected = car.id == current, onClick = null) },
                    headlineContent = { Text(name) },
                    modifier = Modifier.clickable(enabled = car.id != current) { onPick(car.id, name) },
                )
            }
        }
    }
}
