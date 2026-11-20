package io.github.iampopovich.eculense.feature.stats

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import io.github.iampopovich.eculense.core.analytics.DrivingStyle
import io.github.iampopovich.eculense.core.analytics.SensorIds
import io.github.iampopovich.eculense.core.analytics.StylePenalty
import io.github.iampopovich.eculense.core.analytics.StyleSkipReason
import io.github.iampopovich.eculense.core.analytics.StyleThresholds
import io.github.iampopovich.eculense.core.analytics.TripEventType
import io.github.iampopovich.eculense.core.model.FuelMethod
import io.github.iampopovich.eculense.core.model.TripStats
import io.github.iampopovich.eculense.core.navigation.EngineMapDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.navigation.RouteHandler
import io.github.iampopovich.eculense.core.ui.EcuDates
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
internal fun TripDetailRoute(tripId: Long, viewModel: TripDetailViewModel = koinViewModel { parametersOf(tripId) }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val koin = getKoin()
    val hasMaps = remember { koin.getAll<RouteHandler>().any { it.routeClass == EngineMapDestination::class } }
    TripDetailScreen(state, onEngineMap = if (hasMaps) ({ id -> navigator.navigate(EngineMapDestination(id)) }) else null)
}

@Composable
internal fun TripDetailScreen(state: TripDetailUiState, onEngineMap: ((Long) -> Unit)? = null) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val t = state.trip
        if (state.loading) {
            Text(stringResource(R.string.trip_loading))
            return@Column
        }
        if (t == null) {
            Text(stringResource(R.string.trip_missing))
            return@Column
        }
        val u = state.units
        Text(EcuDates.dateTime(t.startedAtMs), style = MaterialTheme.typography.headlineSmall)
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatPair(R.string.stats_distance, StatsFormat.distance(t.distanceKm, u), R.string.trip_duration, StatsFormat.duration(t.endedAtMs - t.startedAtMs))
                StatPair(R.string.stats_driving, StatsFormat.duration(t.drivingMs), R.string.stats_standing, StatsFormat.duration(t.standingMs))
                StatPair(
                    R.string.trip_avg_speed, state.avgSpeedKmh?.let { stringResource(R.string.trip_kmh, StatsFormat.number(it, 0)) } ?: "—",
                    R.string.trip_max_speed, state.maxSpeedKmh?.let { stringResource(R.string.trip_kmh, StatsFormat.number(it, 0)) } ?: "—",
                )
                val approx = if (t.fuelMethod == FuelMethod.MAF_ESTIMATE) "≈ " else ""
                StatPair(
                    R.string.stats_fuel, if (t.fuelKnown) approx + StatsFormat.volume(t.fuelL, u) else stringResource(R.string.stats_fuel_unknown),
                    R.string.stats_avg_consumption, if (t.fuelKnown) approx + StatsFormat.consumption(TripStats.of(listOf(t)).avgLPer100Km, u) else "—",
                )
                state.cost?.let { c -> StatPair(R.string.trip_cost, approx + StatsFormat.money(c, state.car?.currency), null, null) }
            }
        }
        if (state.sessionId == null) {
            Text(stringResource(R.string.trip_no_recording), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.style?.let { StyleCard(it) }
        state.series.forEach { Chart(it) }
        if (state.events.isNotEmpty()) {
            Text(stringResource(R.string.trip_events), style = MaterialTheme.typography.titleMedium)
            val time = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
            state.events.forEach { e ->
                ListItem(
                    headlineContent = { Text(stringResource(if (e.type == TripEventType.STALL) R.string.trip_event_stall else R.string.trip_event_mark)) },
                    supportingContent = { Text(time.format(Instant.ofEpochMilli(e.atMs).atZone(ZoneId.systemDefault()))) },
                )
            }
        }
        val sid = state.sessionId
        if (sid != null && onEngineMap != null) {
            OutlinedButton(onClick = { onEngineMap(sid) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.trip_engine_map)) }
        }
    }
}

@Composable
private fun StatPair(@StringRes l1: Int, v1: String, @StringRes l2: Int?, v2: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(l1), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(v1, style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.weight(1f)) {
            if (l2 != null && v2 != null) {
                Text(stringResource(l2), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v2, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun StyleCard(style: DrivingStyle) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.trip_style_title), style = MaterialTheme.typography.titleMedium)
            when (style) {
                is DrivingStyle.NotScored -> Text(
                    when (style.reason) {
                        StyleSkipReason.NO_SPEED -> stringResource(R.string.trip_style_no_speed)
                        StyleSkipReason.TOO_SHORT -> stringResource(R.string.trip_style_short, StatsFormat.number(StyleThresholds.MIN_DISTANCE_KM, 0))
                        StyleSkipReason.SPARSE_SAMPLES -> stringResource(R.string.trip_style_sparse, StatsFormat.number((style.medianIntervalMs ?: 0) / 1000.0, 1))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                is DrivingStyle.Scored -> {
                    Text(stringResource(R.string.trip_style_score, style.score), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.trip_style_counts, style.accelEvents, style.brakeEvents),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        listOfNotNull(
                            style.highRpmShare?.let { stringResource(R.string.trip_style_high_rpm, (it * 100).roundToInt(), StyleThresholds.HIGH_RPM.toInt()) },
                            stringResource(R.string.trip_style_idle, (style.idleShare * 100).roundToInt()),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    style.topPenalty?.let {
                        Text(
                            stringResource(tipRes(it)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@StringRes
private fun tipRes(p: StylePenalty): Int = when (p) {
    StylePenalty.HARSH_ACCEL -> R.string.trip_tip_accel
    StylePenalty.HARSH_BRAKE -> R.string.trip_tip_brake
    StylePenalty.HIGH_RPM -> R.string.trip_tip_rpm
    StylePenalty.IDLE -> R.string.trip_tip_idle
}

@StringRes
private fun seriesRes(id: String): Int = when (id) {
    SensorIds.SPEED -> R.string.trip_chart_speed
    SensorIds.RPM -> R.string.trip_chart_rpm
    else -> R.string.trip_chart_coolant
}

@Composable
private fun Chart(s: Series) {
    val producer = remember(s) { CartesianChartModelProducer() }
    LaunchedEffect(s) { producer.runTransaction { lineModel { series(x = s.x, y = s.y) } } }
    Text(stringResource(seriesRes(s.sensorId)), style = MaterialTheme.typography.titleSmall)
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(),
            startAxis = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom(),
            getXStep = { _, minX, maxX -> niceStep(maxX - minX) },
        ),
        modelProducer = producer,
        modifier = Modifier.fillMaxWidth().height(180.dp),
    )
}

private val steps = listOf(1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0)

/** About six labels across the trip (Vico otherwise steps by the gcd of x differences). */
internal fun niceStep(durationSec: Double): Double = steps.firstOrNull { it >= durationSec / 6 } ?: steps.last()
