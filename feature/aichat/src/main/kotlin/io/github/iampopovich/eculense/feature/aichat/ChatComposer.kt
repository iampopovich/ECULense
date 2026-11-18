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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * Поле ввода чата в стиле Material 3 (Gemini/ChatGPT): одна «пилюля» surfaceContainerHigh, внутри — растущее поле
 * без своей рамки (до 6 строк) и круглая кнопка справа внизу. Пустое поле → «Отправить» недоступна (предупреждение
 * ошибок); пока идёт ответ — та же кнопка становится «Стоп» и обрывает поток (видимость состояния).
 * Кнопки 40 dp с зоной касания 48 dp (minimumInteractiveComponentSize внутри IconButton).
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
                            Text("Спросите о машине или ошибке", style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        inner()
                    }
                },
            )
            if (streaming) {
                FilledTonalIconButton(onClick = onStop) {
                    Icon(painterResource(R.drawable.ic_stop), contentDescription = "Остановить ответ")
                }
            } else {
                FilledIconButton(onClick = onSend, enabled = value.isNotBlank()) {
                    Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = "Отправить")
                }
            }
        }
    }
}
