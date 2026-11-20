package io.github.iampopovich.eculense.feature.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.iampopovich.eculense.core.navigation.DeepLinks
import io.github.iampopovich.eculense.core.obdtext.ObdText
import java.util.Locale

/** Size classes of [EcuWidget]: 2x1, 3x1, 4x2 (launcher cells ≈ 57-73 dp). */
internal object WidgetSizes {
    val SMALL = DpSize(110.dp, 40.dp)
    val WIDE = DpSize(180.dp, 40.dp)
    val LARGE = DpSize(250.dp, 110.dp)
    val ALL = setOf(SMALL, WIDE, LARGE)

    /** How many live values fit: 1 on 2x1, 2 on 3x1, 3 on 4x2. */
    fun valuesFor(size: DpSize): Int = when {
        size.width >= LARGE.width && size.height >= LARGE.height -> 3
        size.width >= WIDE.width -> 2
        else -> 1
    }

    fun tall(size: DpSize): Boolean = size.height >= LARGE.height
}

/** Launch intent of the app; [connect] asks the activity to connect to the last adapter. */
internal fun appIntent(context: Context, connect: Boolean): Intent =
    (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .apply { if (connect) putExtra(DeepLinks.EXTRA_CONNECT_LAST, true) }

@Composable
internal fun WidgetContent(model: WidgetModel) {
    val context = LocalContext.current
    val size = LocalSize.current
    val open = actionStartActivity(appIntent(context, connect = false))
    val connect = actionStartActivity(appIntent(context, connect = true))
    Column(
        modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(8.dp)
            .clickable(if (model is WidgetModel.Idle) connect else open),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (model) {
            is WidgetModel.Live -> LiveValues(model, WidgetSizes.valuesFor(size))
            is WidgetModel.Idle -> IdleFacts(model, WidgetSizes.tall(size), connect)
        }
    }
}

@Composable
private fun LiveValues(model: WidgetModel.Live, count: Int) {
    val context = LocalContext.current
    val onSurface = GlanceTheme.colors.onSurface
    if (model.simulated) {
        Text(context.getString(R.string.widget_demo), style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.primary))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        model.values.take(count).forEachIndexed { i, v ->
            if (i > 0) Spacer(GlanceModifier.width(12.dp))
            val name = ObdText.pidNameRes(v.code)?.let { context.getString(it) } ?: v.unit
            val number = v.value?.let { formatValue(v.code, it) } ?: "—"
            Column(GlanceModifier.semantics { contentDescription = "$name $number ${v.unit}" }) {
                Text(name, maxLines = 1, style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant))
                Text("$number ${v.unit}", maxLines = 1, style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = onSurface))
            }
        }
    }
}

@Composable
private fun IdleFacts(model: WidgetModel.Idle, tall: Boolean, connect: Action) {
    val context = LocalContext.current
    val style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurface)
    val lines = idleLines(context, model)
    if (lines.isEmpty()) {
        Text(context.getString(R.string.widget_no_data), maxLines = 2, style = style)
    } else {
        // One line on a 2x1/3x1 widget (the voltage first), all of them on 4x2.
        lines.take(if (tall) lines.size else 1).forEach { Text(it, maxLines = 1, style = style) }
    }
    if (tall) Button(context.getString(R.string.widget_connect), onClick = connect)
}

/** Text lines of the idle state, in priority order: voltage, code check, last trip. */
internal fun idleLines(context: Context, m: WidgetModel.Idle): List<String> = listOfNotNull(
    m.voltage?.let { context.getString(R.string.widget_voltage, String.format(Locale.getDefault(), "%.1f", it), ageText(context, m.voltageAgeMs ?: 0)) },
    m.dtcCount?.let { n ->
        val age = ageText(context, m.dtcAgeMs ?: 0)
        if (n == 0) context.getString(R.string.widget_dtc_none, age) else context.getString(R.string.widget_dtc_some, n, age)
    },
    m.tripKm?.let { context.getString(R.string.widget_trip, String.format(Locale.getDefault(), "%.1f", it), ageText(context, m.tripAgeMs ?: 0)) },
)

internal fun ageText(context: Context, ms: Long): String = when (val a = Age.of(ms)) {
    Age.Now -> context.getString(R.string.widget_age_now)
    is Age.Minutes -> context.getString(R.string.widget_age_min, a.n)
    is Age.Hours -> context.getString(R.string.widget_age_hours, a.n)
    is Age.Days -> context.getString(R.string.widget_age_days, a.n)
}

private fun formatValue(code: Int, v: Double): String {
    val decimals = when (code) {
        0x42, 0x10, 0x5E, 0x06, 0x07, 0x08, 0x09 -> 1
        else -> 0
    }
    return String.format(Locale.getDefault(), "%.${decimals}f", v)
}
