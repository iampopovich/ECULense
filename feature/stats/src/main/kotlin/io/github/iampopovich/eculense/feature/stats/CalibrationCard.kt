package io.github.iampopovich.eculense.feature.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.iampopovich.eculense.core.analytics.AccelCalibrationThresholds
import io.github.iampopovich.eculense.core.analytics.CalibrationGap
import io.github.iampopovich.eculense.core.analytics.StyleThresholds

internal class CalibrationActions(val onApply: () -> Unit, val onReset: () -> Unit, val onExport: () -> Unit)

private fun g(ms2: Double): String = StatsFormat.number(ms2 / StyleThresholds.G, 2)

/** E5-lite «Threshold calibration» card on the trip page (only when the recording has an accelerometer file). */
@Composable
internal fun CalibrationCard(ui: CalibrationUi, actions: CalibrationActions) {
    val c = ui.report.calibration
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.calib_title), style = MaterialTheme.typography.titleMedium)
            Text(
                pluralStringResource(R.plurals.calib_samples, ui.report.samples, ui.report.samples, StatsFormat.duration(ui.report.durationMs)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                c.axis?.let { stringResource(R.string.calib_axis, StatsFormat.number(it.correlation, 2), StatsFormat.number(AccelCalibrationThresholds.MIN_AXIS_CORRELATION, 2)) }
                    ?: stringResource(R.string.calib_axis_none),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.calib_events, c.events.size, c.eventsWithObd), style = MaterialTheme.typography.bodyMedium)
            c.medianRatio?.let { m ->
                Text(
                    stringResource(R.string.calib_ratio, StatsFormat.number(m, 2), StatsFormat.number(c.ratioQ1 ?: m, 2), StatsFormat.number(c.ratioQ3 ?: m, 2)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val gap = c.gap
            if (gap != null) {
                Text(
                    stringResource(R.string.calib_not_enough, gapText(gap, c.axis?.correlation, c.eventsWithObd)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(stringResource(R.string.calib_recommended, g(c.recommendedMs2!!)), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                stringResource(if (ui.overridden) R.string.calib_current_car else R.string.calib_current_default, g(ui.currentMs2)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                when {
                    ui.driverLoading -> stringResource(R.string.calib_driver_loading)
                    ui.driver != null -> pluralStringResource(R.plurals.calib_driver, ui.driver.windows, g(ui.driver.thresholdMs2), ui.driver.windows)
                    else -> stringResource(R.string.calib_driver_none)
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Large system font: every action gets its own full-width row.
            if (ui.canApply) {
                Button(onClick = actions.onApply, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.calib_apply)) }
            }
            if (ui.overridden) {
                OutlinedButton(onClick = actions.onReset, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.calib_reset)) }
            }
            TextButton(onClick = actions.onExport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.calib_export)) }
            Text(stringResource(R.string.calib_howto), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun gapText(gap: CalibrationGap, correlation: Double?, events: Int): String = when (gap) {
    CalibrationGap.NO_SENSOR_DATA -> stringResource(R.string.calib_gap_no_sensor)
    CalibrationGap.NO_GRAVITY -> stringResource(R.string.calib_gap_no_gravity)
    CalibrationGap.NO_SPEED -> stringResource(R.string.calib_gap_no_speed)
    CalibrationGap.NO_MOVEMENT -> stringResource(R.string.calib_gap_no_movement)
    CalibrationGap.AXIS_NOT_FOUND -> stringResource(R.string.calib_gap_axis)
    CalibrationGap.LOW_AXIS_CONFIDENCE -> stringResource(
        R.string.calib_gap_low_axis, StatsFormat.number(correlation ?: 0.0, 2), StatsFormat.number(AccelCalibrationThresholds.MIN_AXIS_CORRELATION, 2),
    )
    CalibrationGap.TOO_FEW_EVENTS -> stringResource(R.string.calib_gap_few_events, events, AccelCalibrationThresholds.MIN_EVENTS)
    CalibrationGap.INCONSISTENT_RATIO -> stringResource(R.string.calib_gap_sign)
}

/** Own subclass: one FileProvider class per manifest entry (the merger rejects duplicates). */
class StatsFileProvider : FileProvider()
