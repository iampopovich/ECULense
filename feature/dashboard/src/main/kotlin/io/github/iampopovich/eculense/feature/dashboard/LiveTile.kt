package io.github.iampopovich.eculense.feature.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.iampopovich.eculense.core.obdtext.ObdText
import java.util.Locale

/** Big number of a tile: «—» without data, «○» in open loop, «~» before estimates. PID 03 is shown in words. */
@Composable
internal fun tileValueText(tile: TileState): String = when {
    tile.value == null || tile.status == ValueStatus.NO_DATA || tile.status == ValueStatus.NOT_ANSWERING -> "—"
    tile.status == ValueStatus.OPEN_LOOP -> "○"
    tile.pid.code == 0x03 -> ObdText.fuelSystemStatus(tile.value).asString()
    else -> (if (tile.estimated) "~" else "") + formatNumber(tile.value, tile.decimals)
}

/** Small line under the value: why there is no number, how old it is, or that it is an estimate. */
@Composable
internal fun tileNote(tile: TileState): String? = when (tile.status) {
    ValueStatus.NOT_ANSWERING -> stringResource(R.string.dash_not_answering)
    ValueStatus.OPEN_LOOP -> stringResource(R.string.dash_open_loop_hint)
    ValueStatus.STALE -> tile.ageSec?.let { age ->
        if (age < 120) stringResource(R.string.dash_age_seconds, age.toInt()) else stringResource(R.string.dash_age_minutes, (age / 60).toInt())
    }
    ValueStatus.LIVE, ValueStatus.NO_DATA -> if (tile.estimated) stringResource(R.string.dash_estimated_hint) else null
}

@Composable
internal fun tileName(tile: TileState): String =
    ObdText.pidNameRes(tile.pid.code)?.let { stringResource(it) } ?: tile.pid.sensorId

/** Plain helper: number formatting outside composables (lint NonObservableLocale). */
internal fun formatNumber(value: Double, decimals: Int): String = String.format(Locale.getDefault(), "%.${decimals}f", value)

/** C7: a tiny trend line of the last 60 s (no axes; min..max of the window fills the height). */
@Composable
internal fun Sparkline(points: List<Float>, color: Color, modifier: Modifier = Modifier, height: Dp = 20.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (points.size < 2) return@Canvas
        val min = points.min()
        val max = points.max()
        val span = (max - min).takeIf { it > 1e-6f } ?: 1f
        val stepX = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = i * stepX
            val y = if (max - min < 1e-6f) size.height / 2 else size.height - (v - min) / span * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
