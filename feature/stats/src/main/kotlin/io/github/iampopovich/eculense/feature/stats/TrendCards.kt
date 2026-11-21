package io.github.iampopovich.eculense.feature.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.iampopovich.eculense.core.analytics.StatsTrends
import io.github.iampopovich.eculense.core.analytics.StyleTrendPoint
import io.github.iampopovich.eculense.core.analytics.TemperatureBucket
import io.github.iampopovich.eculense.core.analytics.TrendBucket
import io.github.iampopovich.eculense.core.model.UnitsSettings
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Date label of a trend period: «Week of 2 Mar 2026» or «March 2026». Plain helper (no Locale.getDefault in composables). */
internal fun periodLabel(start: LocalDate, bucket: TrendBucket, locale: Locale): String = when (bucket) {
    TrendBucket.WEEK -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(start)
    TrendBucket.MONTH -> DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(start).replaceFirstChar { it.titlecase(locale) }
}

/** Temperature bounds in the user's unit, whole degrees. */
internal fun temperatureRange(fromC: Int, units: UnitsSettings): Triple<String, String, String> {
    val (lo, unit) = units.display(fromC.toDouble(), "°C")
    val (hi, _) = units.display((fromC + StatsTrends.BUCKET_C).toDouble(), "°C")
    return Triple(lo.roundToInt().toString().replace('-', '−'), hi.roundToInt().toString().replace('-', '−'), unit)
}

/** R2.8 (a): median style score per week or month; hidden by the caller when there is no trend. */
@Composable
internal fun StyleTrendCard(points: List<StyleTrendPoint>, bucket: TrendBucket) {
    val locale = LocalConfiguration.current.locales[0]
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (bucket == TrendBucket.WEEK) R.string.stats_style_trend_week else R.string.stats_style_trend_month),
                style = MaterialTheme.typography.titleMedium,
            )
            points.asReversed().forEach { p ->
                val label = periodLabel(p.start, bucket, locale)
                TrendRow(
                    if (bucket == TrendBucket.WEEK) stringResource(R.string.stats_week_of, label) else label,
                    stringResource(R.string.trip_style_score, p.medianScore),
                    pluralStringResource(R.plurals.stats_trend_trips, p.trips, p.trips),
                )
            }
            Text(stringResource(R.string.stats_style_trend_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** R2.8 (b): consumption per 5 °C of outside temperature; hidden by the caller without enough data. */
@Composable
internal fun TemperatureCard(buckets: List<TemperatureBucket>, units: UnitsSettings) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.stats_temp_title), style = MaterialTheme.typography.titleMedium)
            buckets.forEach { b ->
                val (lo, hi, unit) = temperatureRange(b.fromC, units)
                TrendRow(
                    stringResource(R.string.stats_temp_range, lo, hi, unit),
                    StatsFormat.consumption(b.medianLPer100Km, units),
                    pluralStringResource(R.plurals.stats_trend_trips, b.trips, b.trips),
                )
            }
            Text(
                stringResource(R.string.stats_temp_note, StatsTrends.MIN_TRIPS_PER_BUCKET),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Label on the left, value and trip count on the right (the value column wraps under a large font). */
@Composable
private fun TrendRow(label: String, value: String, count: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.titleSmall)
            Text(count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
