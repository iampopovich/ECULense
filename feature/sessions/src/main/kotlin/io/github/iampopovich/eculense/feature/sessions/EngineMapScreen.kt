package io.github.iampopovich.eculense.feature.sessions

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.analytics.EngineMap
import io.github.iampopovich.eculense.core.analytics.EngineMapPattern
import io.github.iampopovich.eculense.core.analytics.EngineMaps
import io.github.iampopovich.eculense.core.analytics.MapAxis
import io.github.iampopovich.eculense.core.analytics.TrimBin
import io.github.iampopovich.eculense.core.model.Session
import io.github.iampopovich.eculense.core.navigation.EngineMapDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.ui.EcuDates
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.util.Locale

@Composable
internal fun EngineMapRoute(sessionId: Long, compareWith: Long?, viewModel: EngineMapViewModel = koinViewModel { parametersOf(sessionId, compareWith) }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    EngineMapScreen(state, sessions.filter { it.id != sessionId }) { other -> navigator.navigate(EngineMapDestination(sessionId, other)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EngineMapScreen(state: EngineMapUiState, others: List<Session>, onCompare: (Long) -> Unit) {
    var axis by remember { mutableStateOf(MapAxis.RPM) }
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.map_title), style = MaterialTheme.typography.headlineSmall)
        val map = state.map
        if (state.loading || map == null) {
            Text(stringResource(R.string.sessions_loading))
            return@Column
        }
        Text(stringResource(R.string.map_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (map.points.isEmpty()) {
            Text(stringResource(R.string.map_no_points), style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        SummaryCard(map, state.session, null)
        val hasLoad = map.loadBins.any { it.count > 0 }
        if (hasLoad) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(MapAxis.RPM, MapAxis.LOAD).forEachIndexed { i, a ->
                    SegmentedButton(selected = axis == a, onClick = { axis = a }, shape = SegmentedButtonDefaults.itemShape(i, 2), icon = {}) {
                        Text(stringResource(if (a == MapAxis.RPM) R.string.map_axis_rpm else R.string.map_axis_load))
                    }
                }
            }
        }
        val shownAxis = if (hasLoad) axis else MapAxis.RPM
        Scatter(map, shownAxis, state.other)
        Legend(map, state.other != null)
        BinTable(map, state.other, shownAxis)
        state.other?.let { SummaryCard(it, state.otherSession, R.string.map_compare_second) }
        if (others.isNotEmpty()) {
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (state.other == null) R.string.map_compare else R.string.map_compare_change))
            }
        }
    }
    if (picking) {
        ModalBottomSheet(onDismissRequest = { picking = false }) {
            Text(stringResource(R.string.map_compare_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(others, key = { it.id }) { s ->
                    ListItem(headlineContent = { Text(EcuDates.dateTime(s.startedAtMs)) }, supportingContent = { Text(s.name) },
                        modifier = Modifier.clickable { picking = false; onCompare(s.id) })
                }
            }
        }
    }
}

@StringRes
private fun patternRes(p: EngineMapPattern): Int = when (p) {
    EngineMapPattern.AIR_LEAK_LIKE -> R.string.map_pattern_air_leak
    EngineMapPattern.FUEL_DELIVERY_LIKE -> R.string.map_pattern_fuel_delivery
    EngineMapPattern.LEAN_EVERYWHERE -> R.string.map_pattern_lean
    EngineMapPattern.RICH_EVERYWHERE -> R.string.map_pattern_rich
    EngineMapPattern.NORMAL -> R.string.map_pattern_normal
    EngineMapPattern.NOT_ENOUGH_DATA -> R.string.map_pattern_no_data
}

private fun pct(v: Double?): String = v?.let { String.format(Locale.ROOT, "%+.1f %%", it) } ?: "—"

@Composable
private fun SummaryCard(map: EngineMap, session: Session?, @StringRes heading: Int?) {
    val s = map.summary
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            heading?.let { Text(stringResource(it), style = MaterialTheme.typography.labelLarge) }
            session?.let { Text(EcuDates.dateTime(it.startedAtMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(stringResource(patternRes(s.pattern)), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(
                    if (s.loadAxis == MapAxis.LOAD) R.string.map_medians_load else R.string.map_medians_rpm,
                    pct(s.idleMedian), s.idleCount, pct(s.loadMedian), s.loadCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (s.pattern != EngineMapPattern.NORMAL && s.pattern != EngineMapPattern.NOT_ENOUGH_DATA) {
                Text(stringResource(R.string.map_hypothesis_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val bank1 = Color(0xFF1E88E5)
private val bank2 = Color(0xFFE65100)

@Composable
private fun Scatter(map: EngineMap, axis: MapAxis, other: EngineMap?) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val zero = MaterialTheme.colorScheme.onSurfaceVariant
    val second = MaterialTheme.colorScheme.tertiary
    val xMax = when (axis) {
        MapAxis.RPM -> maxOf(4_000.0, (map.points + other?.points.orEmpty()).maxOfOrNull { it.rpm } ?: 0.0)
        MapAxis.LOAD -> 100.0
    }
    val yLim = 25.0
    Text(stringResource(if (axis == MapAxis.RPM) R.string.map_x_rpm else R.string.map_x_load, xMax.toInt()), style = MaterialTheme.typography.labelMedium)
    Canvas(Modifier.fillMaxWidth().height(240.dp)) {
        fun x(v: Double) = (v / xMax).toFloat().coerceIn(0f, 1f) * size.width
        fun y(v: Double) = ((yLim - v.coerceIn(-yLim, yLim)) / (2 * yLim)).toFloat() * size.height
        listOf(-20.0, -10.0, 10.0, 20.0).forEach { drawLine(grid, Offset(0f, y(it)), Offset(size.width, y(it)), 1f) }
        drawLine(zero, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        val edges = if (axis == MapAxis.RPM) EngineMaps.RPM_EDGES else EngineMaps.LOAD_EDGES
        edges.drop(1).forEach { e -> drawLine(grid, Offset(x(e), 0f), Offset(x(e), size.height), 1f) }
        fun plot(m: EngineMap, colorOf: (Int) -> Color) {
            // At most ~2000 dots: thinning keeps the shape and the frame time.
            val step = maxOf(1, m.points.size / 2000)
            m.points.forEachIndexed { i, p ->
                if (i % step != 0) return@forEachIndexed
                val xv = if (axis == MapAxis.RPM) p.rpm else p.load ?: return@forEachIndexed
                drawCircle(colorOf(p.bank).copy(alpha = 0.55f), radius = 3.5f, center = Offset(x(xv), y(p.trim)))
            }
        }
        other?.let { plot(it) { second } }
        plot(map) { if (it == 1) bank1 else bank2 }
    }
    Row(Modifier.fillMaxWidth()) {
        Text("+25 %", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.map_y_hint), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Legend(map: EngineMap, compared: Boolean) {
    val parts = buildList {
        if (1 in map.banks) add(stringResource(R.string.map_legend_bank1))
        if (2 in map.banks) add(stringResource(R.string.map_legend_bank2))
        if (compared) add(stringResource(R.string.map_legend_second))
    }
    Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun BinTable(map: EngineMap, other: EngineMap?, axis: MapAxis) {
    val bins = if (axis == MapAxis.RPM) map.rpmBins else map.loadBins
    val otherBins = other?.let { if (axis == MapAxis.RPM) it.rpmBins else it.loadBins }.orEmpty()
    val unit = if (axis == MapAxis.RPM) "" else " %"
    Text(stringResource(R.string.map_table_title), style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth()) {
        Cell(stringResource(if (axis == MapAxis.RPM) R.string.map_col_rpm else R.string.map_col_load), 1.4f, bold = true)
        map.banks.forEach { Cell(stringResource(R.string.map_col_bank, it), 1f, bold = true) }
        if (other != null) Cell(stringResource(R.string.map_col_second), 1f, bold = true)
    }
    val ranges = bins.map { it.from to it.to }.distinct()
    ranges.forEach { (from, to) ->
        fun of(list: List<TrimBin>, bank: Int?) = list.filter { it.from == from && (bank == null || it.bank == bank) }
        if (of(bins, null).sumOf { it.count } == 0 && of(otherBins, null).sumOf { it.count } == 0) return@forEach
        Row(Modifier.fillMaxWidth()) {
            Cell(if (to == null) "≥ ${from.toInt()}$unit" else "${from.toInt()}–${to.toInt()}$unit", 1.4f)
            map.banks.forEach { b -> val bin = of(bins, b).firstOrNull(); Cell("${pct(bin?.median)} (${bin?.count ?: 0})", 1f) }
            if (other != null) {
                val o = of(otherBins, null)
                val med = o.mapNotNull { it.median }.takeIf { it.isNotEmpty() }?.average()
                Cell("${pct(med)} (${o.sumOf { it.count }})", 1f)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float, bold: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.Bold else null,
        modifier = Modifier.weight(weight).padding(vertical = 4.dp),
    )
}
