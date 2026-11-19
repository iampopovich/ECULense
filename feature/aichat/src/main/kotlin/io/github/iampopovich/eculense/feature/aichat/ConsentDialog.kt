package io.github.iampopovich.eculense.feature.aichat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** What is sent to the provider when vehicle data is enabled (the :core:aitools tools list the same). */
internal val SHARED_DATA = listOf(
    "профиль машины: марка, модель, год, двигатель, пробег",
    "итоги поездок: путь, расход, время",
    "статистика записей датчиков (мин., средн., макс.)",
    "находки анализа и история кодов ошибок",
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
        title = { Text("Отправить данные машины ИИ?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Чтобы ответить по вашей машине, ИИ запросит у приложения:")
                SHARED_DATA.forEach { Text("• $it") }
                Text(
                    "Данные уйдут провайдеру «${request.provider.title}» (модель ${request.provider.model}) и будут обработаны на его серверах.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "VIN, координаты GPS и API-ключ не передаются. Отключить можно в любой момент переключателем «Использовать данные моей машины».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Разрешить") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Без данных машины") } },
    )
}
