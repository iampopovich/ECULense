package io.github.iampopovich.eculense.feature.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/** R-I: «Demo» badge over values from a simulated link (debug-only demo adapters); release copy in src/release. */
@Composable
internal fun SimulatedBadge() {
    val context = LocalContext.current
    Text(context.getString(R.string.widget_demo), style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.primary))
}
