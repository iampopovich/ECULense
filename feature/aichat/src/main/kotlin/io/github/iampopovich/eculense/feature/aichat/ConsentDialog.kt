package io.github.iampopovich.eculense.feature.aichat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** What is sent to the provider when vehicle data is enabled (the :core:aitools tools list the same). */
internal val SHARED_DATA = listOf(
    R.string.aichat_consent_item_profile,
    R.string.aichat_consent_item_trips,
    R.string.aichat_consent_item_sensors,
    R.string.aichat_consent_item_findings,
)

/**
 * Consent before the first sending of vehicle data to a cloud provider (plan 3.7): what is sent and where.
 * Tapping outside the dialog doesn't close it (the decision must be explicit); «Назад» cancels, the question goes back into the field.
 */
@Composable
internal fun ConsentDialog(request: ConsentRequest, onAccept: () -> Unit, onDecline: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.aichat_consent_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.aichat_consent_intro))
                SHARED_DATA.forEach { Text("• " + stringResource(it)) }
                Text(
                    stringResource(R.string.aichat_consent_where, ChatTexts.providerTitle(request.provider).asString(), request.provider.model),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.aichat_consent_not_sent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.aichat_consent_allow)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.aichat_consent_decline)) } },
    )
}
