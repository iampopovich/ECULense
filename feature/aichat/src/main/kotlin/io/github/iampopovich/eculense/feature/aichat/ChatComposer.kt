package io.github.iampopovich.eculense.feature.aichat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * Material 3-style chat input (Gemini/ChatGPT): a single surfaceContainerHigh "pill" containing a growing field
 * without its own border (up to 6 lines) and a round button at the bottom right. Empty field → «Отправить» is disabled (error
 * prevention); while a response is streaming, the same button becomes «Стоп» and cuts the stream (visibility of system status).
 * 40 dp buttons with a 48 dp touch target (minimumInteractiveComponentSize inside IconButton).
 */
@Composable
internal fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f).padding(vertical = 12.dp).testTag("chat-input"),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(stringResource(R.string.aichat_input_hint), style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        inner()
                    }
                },
            )
            if (streaming) {
                FilledTonalIconButton(onClick = onStop) {
                    Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.aichat_stop))
                }
            } else {
                FilledIconButton(onClick = onSend, enabled = value.isNotBlank()) {
                    Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = stringResource(R.string.aichat_send))
                }
            }
        }
    }
}
