package io.github.iampopovich.eculense.feature.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.iampopovich.eculense.core.analytics.LiveAlertKind
import io.github.iampopovich.eculense.core.data.ActiveAlert

/** «Engine overheating · 112 °C»: what the alert banner says (big, one line per alert). */
@Composable
internal fun alertBannerText(alert: ActiveAlert): String {
    val value = when (alert.kind) {
        LiveAlertKind.OVERHEAT -> stringResource(R.string.dash_value_celsius, alert.value)
        else -> stringResource(R.string.dash_value_volts, alert.value)
    }
    return stringResource(R.string.dash_alert_banner, stringResource(alertTitle(alert.kind)), value)
}

/** C4: full-width error banners for live alerts and a tappable one for new codes (opens the DTC screen). */
@Composable
internal fun AlertBanners(state: DashboardUiState, onOpenCodes: () -> Unit) {
    if (state.alerts.isEmpty() && state.newCodes.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        state.alerts.forEach { a ->
            Surface(color = MaterialTheme.colorScheme.error, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.onError)
                    Spacer(Modifier.width(8.dp))
                    Text(alertBannerText(a), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onError)
                }
            }
        }
        if (state.newCodes.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(bottom = 8.dp).clickable(onClick = onOpenCodes),
            ) {
                Text(
                    stringResource(R.string.dash_new_code, state.newCodes.joinToString(", ")),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}
