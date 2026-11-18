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

/** Что уходит провайдеру, когда включены данные машины (то же перечисляют инструменты :core:aitools). */
internal val SHARED_DATA = listOf(
    "профиль машины: марка, модель, год, двигатель, пробег",
    "итоги поездок: путь, расход, время",
    "статистика записей датчиков (мин., средн., макс.)",
    "находки анализа и история кодов ошибок",
)

/**
 * Согласие перед первой отправкой данных машины облачному провайдеру (план 3.7): что уходит и куда.
 * Касание вне диалога не закрывает его (решение должно быть явным); «Назад» — отмена, вопрос возвращается в поле.
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
