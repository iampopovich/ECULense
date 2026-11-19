package io.github.iampopovich.eculense.feature.sessions

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import io.github.iampopovich.eculense.core.obdtext.ObdText
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun SessionDetailRoute(sessionId: Long, viewModel: SessionDetailViewModel = koinViewModel { parametersOf(sessionId) }) {
    val bySensor by viewModel.readingsBySensor.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<String?>(null) }
    val sensor = selected ?: defaultSensor(bySensor.keys.toList())
    val modelProducer = remember { CartesianChartModelProducer() }
    val points = sensor?.let { bySensor[it] }.orEmpty()

    LaunchedEffect(points) {
        if (points.size >= 2) {
            val t0 = points.first().timestampMs
            modelProducer.runTransaction {
                lineModel { series(x = points.map { (it.timestampMs - t0) / 1000.0 }, y = points.map { it.value }) }
            }
        }
    }

    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Default sensor goes first, otherwise the selected chip may end up off-screen.
            bySensor.keys.sortedBy { it != defaultSensor(bySensor.keys.toList()) }.forEach { id ->
                val label = ObdText.sensorName(id).asString()
                FilterChip(selected = id == sensor, onClick = { selected = id }, label = { Text(label) })
            }
        }
        if (points.size >= 2) {
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(),
                    startAxis = VerticalAxis.rememberStart(),
                    bottomAxis = HorizontalAxis.rememberBottom(),
                    getXStep = { _, minX, maxX -> niceXStepSeconds(maxX - minX) },
                ),
                modelProducer = modelProducer,
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )
        } else if (loading) {
            Text(stringResource(R.string.sessions_loading))
        } else {
            Text(stringResource(R.string.sessions_too_few_points))
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
    }
}
