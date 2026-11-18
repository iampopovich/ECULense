package io.github.iampopovich.eculense.core.ailocal

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/** Действия экрана; по умолчанию пустые — удобно в UI-тестах. */
internal class LocalModelActions(
    val onDownload: (String) -> Unit = {},
    val onConfirmDownload: () -> Unit = {},
    val onDismissConfirm: () -> Unit = {},
    val onCancelDownload: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onBench: (String) -> Unit = {},
    val onUse: (String) -> Unit = {},
    val onUseBackend: (String, BackendKind) -> Unit = { _, _ -> },
    val onStopUsing: () -> Unit = {},
    val onTokenInput: (String) -> Unit = {},
    val onSaveToken: () -> Unit = {},
    val onDeleteToken: () -> Unit = {},
    val onLinkInput: (String) -> Unit = {},
    val onAddLink: () -> Unit = {},
    val onPickFile: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

@Composable
internal fun LocalModelRoute(viewModel: LocalModelViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            var name = "model.litertlm"
            var size: Long? = null
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: name
                    size = if (c.isNull(1)) null else c.getLong(1)
                }
            }
            viewModel.importFile(uri, name, size)
        }
    }
    LocalModelScreen(
        state,
        LocalModelActions(
            onDownload = viewModel::requestDownload,
            onConfirmDownload = viewModel::confirmDownload,
            onDismissConfirm = viewModel::dismissConfirm,
            onCancelDownload = viewModel::cancelDownload,
            onDelete = viewModel::delete,
            onBench = viewModel::runBench,
            onUse = viewModel::use,
            onUseBackend = viewModel::useBackend,
            onStopUsing = viewModel::stopUsing,
            onTokenInput = viewModel::setTokenInput,
            onSaveToken = viewModel::saveToken,
            onDeleteToken = viewModel::deleteToken,
            onLinkInput = viewModel::setLinkInput,
            onAddLink = viewModel::addLink,
            onPickFile = { picker.launch(arrayOf("*/*")) },
            onMessageShown = viewModel::messageShown,
        ),
    )
}

@Composable
internal fun LocalModelScreen(state: LocalModelUiState, actions: LocalModelActions, modifier: Modifier = Modifier) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            actions.onMessageShown()
        }
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("local-model-list"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Модель на телефоне", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp)) }
            item { HonestNote() }
            item { DeviceCard(state.device, state.nano) }
            item { Text("Модели из каталога Koog", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
            items(state.rows.filter { it.model.origin == ModelOrigin.KOOG }, key = { it.model.packId }) { row -> ModelCard(row, state.hasToken, actions) }
            val custom = state.rows.filter { it.model.origin != ModelOrigin.KOOG }
            item { Text("Своя модель", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
            items(custom, key = { it.model.packId }) { row -> ModelCard(row, state.hasToken, actions) }
            item { CustomModelCard(state, actions) }
            item { TokenCard(state, actions) }
            item { Box(Modifier.padding(bottom = 24.dp)) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    state.confirm?.let { ConfirmDialog(it, actions) }
}

@Composable
private fun HonestNote() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Text(
            "Модель на телефоне работает без интернета, и данные машины никуда не уходят. Но такие модели заметно " +
                "медленнее и ошибаются чаще облачных: перепроверяйте советы. Скачать можно любую модель — на свой риск: " +
                "файлы весят гигабайты, а тянет ли телефон, точно покажет тест после скачивания.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun DeviceCard(device: DeviceProfile?, nano: NanoStatus?) {
    Card(Modifier.fillMaxWidth().testTag("device-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Ваше устройство", style = MaterialTheme.typography.titleMedium)
            if (device == null) {
                Text("Проверяем…")
                return@Column
            }
            Text("ОЗУ: ${FitEstimator.gb(device.totalRamBytes)} ГБ, свободно сейчас ${FitEstimator.gb(device.availRamBytes)} ГБ")
            Text("Процессор: ${device.soc}" + if (device.arm64) " (arm64)" else " (нет arm64 — модели не запустятся)")
            Text("Свободно места: ${FitEstimator.gb(device.freeStorageBytes)} ГБ")
            Text("GPU для моделей: " + if (device.gpu) "есть (OpenCL)" else "нет драйвера OpenCL — только CPU")
            Text("NPU: " + (device.npu?.let { "$it (нужна модель, собранная под этот NPU)" } ?: "не найден"))
            Text("Google AICore: " + if (device.aicore) "установлен" else "нет")
            nano?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun FitChip(fit: Fit) {
    val color = when (fit) {
        Fit.OK -> Color(0xFF1B5E20)
        Fit.TIGHT -> Color(0xFF8D5A00)
        Fit.UNLIKELY -> MaterialTheme.colorScheme.error
        Fit.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text("Оценка: ${fit.label}", color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun ModelCard(row: ModelRow, hasToken: Boolean, actions: LocalModelActions) {
    val m = row.model
    Card(Modifier.fillMaxWidth().testTag("model-${m.packId}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(m.title, style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(m.sizeBytes?.let { "${FitEstimator.gb(it)} ГБ" } ?: "размер неизвестен", m.fileName, if (m.gated) "нужен токен HF" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (m.description.isNotBlank()) Text(m.description, style = MaterialTheme.typography.bodyMedium)
            if (row.report == null) {
                FitChip(row.fit.fit)
                row.fit.reasons.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (m.gated && !hasToken && !row.installed) {
                Text("Закрытая модель: введите токен Hugging Face ниже и примите лицензию на странице модели.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            when {
                row.downloading -> {
                    val total = m.sizeBytes
                    if (row.progress != null && row.progress >= 0) {
                        // Лямбда стабильна, значение читается при отрисовке: частые обновления прогресса не
                        // перекомпоновывают индикатор (иначе на телефоне застревала прокрутка списка жестом).
                        val pct by rememberUpdatedState(row.progress / 100f)
                        LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(
                        (if (row.retrying) "Связь прервалась, повтор с места обрыва… " else "Скачивание: ") +
                            FitEstimator.size(row.downloadedBytes) + (total?.let { " из ${FitEstimator.size(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { actions.onCancelDownload(m.packId) }) { Text("Остановить") }
                }
                !row.installed -> {
                    row.error?.let { Text("Ошибка загрузки: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (m.url == null) {
                        Text("Ссылка для скачивания неизвестна — добавьте файл как свою модель.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { actions.onDownload(m.packId) }) {
                                Text(if (row.partial) "Продолжить (${FitEstimator.size(row.downloadedBytes)} уже есть)" else "Скачать")
                            }
                            if (row.partial) TextButton(onClick = { actions.onDelete(m.packId) }) { Text("Удалить") }
                        }
                    }
                }
                else -> InstalledPart(row, actions)
            }
        }
    }
}

@Composable
private fun InstalledPart(row: ModelRow, actions: LocalModelActions) {
    val m = row.model
    if (row.benching) {
        Text("Тест скорости: " + (row.benchingBackend?.let { "${it.label}…" } ?: "…"), fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(Modifier.fillMaxWidth())
        row.benchDone.forEach { RunLine(it) }
        Text("Каждый бэкенд проверяется в отдельном процессе: если он упадёт или не хватит памяти, приложение не закроется.", style = MaterialTheme.typography.bodySmall)
        return
    }
    row.report?.let { VerdictCard(it, row, actions) } ?: Text("Модель скачана. Тест скорости ещё не проводился.", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (row.activeBackend == null) {
            Button(onClick = { actions.onUse(m.packId) }) { Text("Использовать в чате") }
        } else {
            OutlinedButton(onClick = actions.onStopUsing) { Text("Не использовать") }
        }
        OutlinedButton(onClick = { actions.onBench(m.packId) }) { Text(if (row.report == null) "Тест скорости" else "Повторить тест") }
        TextButton(onClick = { actions.onDelete(m.packId) }) { Text("Удалить") }
    }
}

@Composable
private fun VerdictCard(report: BenchReport, row: ModelRow, actions: LocalModelActions) {
    val verdict = report.verdict
    val color = when (verdict) {
        Verdict.COMFORT -> Color(0xFF1B5E20)
        Verdict.SLOW -> Color(0xFF8D5A00)
        Verdict.UNSUITABLE -> MaterialTheme.colorScheme.error
    }
    Card(Modifier.fillMaxWidth().testTag("verdict-${row.model.packId}"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Вердикт: ${verdict.label}", color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                when (verdict) {
                    Verdict.COMFORT -> "Отвечает быстро: первые слова через пару секунд, дальше ≥ 8 токенов в секунду."
                    Verdict.SLOW -> "Работает, но медленно: короткий ответ — десятки секунд, с данными машины — дольше."
                    Verdict.UNSUITABLE -> "Ни один бэкенд не заработал на этом телефоне."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            report.best?.let { Text("Лучший бэкенд: ${it.backend.label}", fontWeight = FontWeight.SemiBold) }
            report.runs.forEach { RunLine(it) }
            Text("Тест: запрос ${report.runs.firstOrNull { it.ok }?.prefillTokens ?: BenchService.PREFILL_TOKENS} токенов, ответ ${BenchService.DECODE_TOKENS} токенов.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            row.activeBackend?.let { active ->
                Text("Используется в чате: ${active.label}", fontWeight = FontWeight.SemiBold)
                val others = report.runs.filter { it.ok && it.backend != active }
                if (others.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        others.forEach { r -> AssistChip(onClick = { actions.onUseBackend(row.model.packId, r.backend) }, label = { Text("Перейти на ${r.backend.label}") }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunLine(run: BackendRun) {
    val text = if (run.ok) {
        "${run.backend.label}: первый токен ${sec(run.ttftSeconds)} с · ${num(run.decodeTokensPerSecond)} ток/с · чтение запроса ${num(run.prefillTokensPerSecond)} ток/с · " +
            "загрузка ${sec(run.initSeconds)} с" + (run.peakRssBytes?.let { " · память ${FitEstimator.gb(it)} ГБ" } ?: "")
    } else {
        "${run.backend.label}: не подходит — ${run.error ?: "ошибка"}"
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (run.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
}

private fun sec(v: Double) = "%.1f".format(v).replace('.', ',')
private fun num(v: Double) = "%.1f".format(v).replace('.', ',')

@Composable
private fun CustomModelCard(state: LocalModelUiState, actions: LocalModelActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Любая модель LiteRT-LM (.litertlm): ссылка на файл с Hugging Face (например, из litert-community) или файл с телефона.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = state.linkInput,
                onValueChange = actions.onLinkInput,
                label = { Text("Ссылка Hugging Face") },
                placeholder = { Text("https://huggingface.co/…/resolve/main/model.litertlm") },
                singleLine = true,
                isError = state.linkError != null,
                supportingText = state.linkError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onAddLink, enabled = state.linkInput.isNotBlank() && !state.linkBusy) { Text(if (state.linkBusy) "Проверяем…" else "Проверить ссылку") }
                OutlinedButton(onClick = actions.onPickFile, enabled = state.importProgress == null) { Text("Файл с телефона") }
            }
            state.importProgress?.let { Text("Копирование: ${FitEstimator.size(it)}…", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun TokenCard(state: LocalModelUiState, actions: LocalModelActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Токен Hugging Face (необязательно)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Нужен только для закрытых моделей (FunctionGemma, Gemma 3). Хранится зашифрованным ключом Android Keystore и уходит только на huggingface.co.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.hasToken) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Токен сохранён", modifier = Modifier.weight(1f))
                    TextButton(onClick = actions.onDeleteToken) { Text("Удалить токен") }
                }
            } else {
                OutlinedTextField(
                    value = state.tokenInput,
                    onValueChange = actions.onTokenInput,
                    label = { Text("hf_…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = actions.onSaveToken, enabled = state.tokenInput.isNotBlank()) { Text("Сохранить токен") }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(c: DownloadConfirm, actions: LocalModelActions) {
    AlertDialog(
        onDismissRequest = actions.onDismissConfirm,
        title = { Text("Скачать «${c.title}»?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Размер: " + (c.sizeBytes?.let { "${FitEstimator.gb(it)} ГБ" } ?: "неизвестен") + ". Оценка: ${c.fit.fit.label}.")
                c.fit.reasons.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Скачиваете на свой риск: модель может не запуститься или работать медленно — это покажет тест после скачивания. Загрузку можно остановить и продолжить позже.")
                if (c.metered) Text("Вы на мобильной сети: загрузка израсходует трафик.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                if (c.gatedWithoutToken) Text("Модель закрыта: без токена Hugging Face загрузка завершится ошибкой 401.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = actions.onConfirmDownload) { Text(if (c.metered) "Скачать по мобильной сети" else "Скачать") } },
        dismissButton = { TextButton(onClick = actions.onDismissConfirm) { Text("Отмена") } },
    )
}
