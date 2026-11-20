package io.github.iampopovich.eculense.core.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** C14: the VIN is never shown by default; bullets of the same length stand in for it. */
object VinPrivacy {
    const val BULLET = '•'

    fun masked(vin: String): String = BULLET.toString().repeat(vin.length.coerceAtLeast(1))

    /** For text fields: the typed VIN is drawn as bullets until the user taps «Show». */
    val fieldMask: VisualTransformation = VisualTransformation { text ->
        TransformedText(AnnotatedString(masked(text.text).take(text.text.length)), OffsetMapping.Identity)
    }
}

/**
 * A VIN line: «[label] •••••••••••••••••» with a «Show» button that reveals it until the screen leaves composition
 * (plain remember, not saved). [label] is the localized prefix ("VIN").
 */
@Composable
fun HiddenVin(vin: String, label: String, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label ${if (shown) vin else VinPrivacy.masked(vin)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = { shown = !shown }) {
            Text(stringResource(if (shown) R.string.common_vin_hide else R.string.common_vin_show))
        }
    }
}
