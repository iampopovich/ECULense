package io.github.iampopovich.eculense.core.ailocal

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
    val onSelect: (String?) -> Unit = {},
    val onRefreshCatalog: () -> Unit = {},
    val onSearchQuery: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
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
            onSelect = viewModel::select,
            onRefreshCatalog = { viewModel.refreshCatalog() },
            onSearchQuery = viewModel::setSearchQuery,
            onSearch = viewModel::search,
        ),
    )
}

/**
 * Экран «На устройстве»: компактная карточка телефона, затем ОДИН список моделей по размеру (строка = название,
 * размер, бейдж источника, оценка, иконка действия/результата), детали и действия — в нижней панели по нажатию.
 */
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
        LazyColumn(Modifier.fillMaxSize().testTag("local-model-list")) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Модель на телефоне", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                    DeviceCard(state.device, state.nano)
                    Text(
                        "Работает без интернета, данные машины не уходят. Но медленнее и ошибается чаще облачных — перепроверяйте советы. " +
                            "Скачивание — на ваш риск; тянет ли телефон, покажет тест после загрузки.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { CatalogHeader(state, actions) }
            items(state.rows, key = { it.model.packId }) { row -> ModelListRow(row, state.hasToken, actions) }
            item { SectionTitle("Другие модели (Hugging Face)") }
            item { SearchBox(state, actions) }
            state.searchResults?.let { results ->
                if (results.isEmpty() && !state.searchBusy) {
                    item { Text("Ничего нового не найдено.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
                }
                items(results, key = { "hf-" + it.model.packId }) { row -> ModelListRow(row, state.hasToken, actions) }
            }
            item { SectionTitle("Своя модель") }
            item { Box(Modifier.padding(horizontal = 16.dp)) { CustomModelCard(state, actions) } }
            item { Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { TokenCard(state, actions) } }
            item { Box(Modifier.padding(bottom = 24.dp)) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    val selected = state.selected?.let { id -> state.rows.firstOrNull { it.model.packId == id } ?: state.searchResults?.firstOrNull { it.model.packId == id } }
    if (selected != null) ModelSheet(selected, state.hasToken, actions)
    state.confirm?.let { ConfirmDialog(it, actions) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun CatalogHeader(state: LocalModelUiState, actions: LocalModelActions) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Модели", style = MaterialTheme.typography.titleMedium)
            Text(
                "Google AI Edge Gallery ${state.catalogVersion.replace('_', '.')} + Koog · по размеру",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.catalogRefreshing) {
            CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = actions.onRefreshCatalog) { Icon(painterResource(R.drawable.ic_model_refresh), contentDescription = "Обновить каталог") }
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceProfile?, nano: NanoStatus?) {
    Card(Modifier.fillMaxWidth().testTag("device-card")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Ваше устройство", style = MaterialTheme.typography.titleSmall)
            if (device == null) {
                Text("Проверяем…", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Text(
                "ОЗУ ${FitEstimator.gb(device.totalRamBytes)} ГБ (свободно ${FitEstimator.gb(device.availRamBytes)}) · место ${FitEstimator.gb(device.freeStorageBytes)} ГБ",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                listOf(
                    device.soc + if (device.arm64) "" else " — нет arm64, модели не запустятся",
                    if (device.gpu) "GPU есть" else "GPU нет",
                    device.npu?.let { "NPU $it" } ?: "NPU нет",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            nano?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun fitColor(fit: Fit, scheme: ColorScheme): Color = when (fit) {
    Fit.OK -> Color(0xFF1B5E20)
    Fit.TIGHT -> Color(0xFF8D5A00)
    Fit.UNLIKELY -> scheme.error
    Fit.UNKNOWN -> scheme.onSurfaceVariant
}

private fun verdictColor(v: Verdict, scheme: ColorScheme): Color = when (v) {
    Verdict.COMFORT -> Color(0xFF1B5E20)
    Verdict.SLOW -> Color(0xFF8D5A00)
    Verdict.UNSUITABLE -> scheme.error
}

/** Маленький бейдж: источник, «только действия», «нужен токен HF», оценка. */
@Composable
private fun Badge(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, strong: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ModelBadges(row: ModelRow, hasToken: Boolean, withFit: Boolean = true) {
    val m = row.model
    val scheme = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Badge(m.origin.badge)
        if (m.koog && m.origin != ModelOrigin.KOOG) Badge(ModelOrigin.KOOG.badge)
        if (m.actionsOnly) Badge("только действия", scheme.tertiary)
        if (m.gated && !hasToken && !row.installed) Badge("нужен токен HF", scheme.error)
        if (withFit && !row.installed) Badge(row.fit.fit.label.lowercase(), fitColor(row.fit.fit, scheme), strong = true)
    }
}

/** Одна строка списка: название, размер, бейджи — и иконка действия/состояния справа. Нажатие — детали. */
@Composable
private fun ModelListRow(row: ModelRow, hasToken: Boolean, actions: LocalModelActions) {
    val m = row.model
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable { actions.onSelect(m.packId) }
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .testTag("model-${m.packId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(m.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(m.sizeBytes?.let { FitEstimator.size(it) } ?: "?", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ModelBadges(row, hasToken)
        }
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { RowTrailing(row, actions) }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp))
}

@Composable
private fun RowTrailing(row: ModelRow, actions: LocalModelActions) {
    val m = row.model
    when {
        row.downloading -> {
            if (row.progress != null && row.progress >= 0) {
                // Лямбда стабильна, значение читается при отрисовке: частые обновления прогресса не перекомпоновывают
                // индикатор (иначе на телефоне застревала прокрутка списка жестом).
                val pct by rememberUpdatedState(row.progress / 100f)
                CircularProgressIndicator(progress = { pct }, modifier = Modifier.size(32.dp).semantics { contentDescription = "Скачивание" }, strokeWidth = 3.dp)
            } else {
                CircularProgressIndicator(Modifier.size(32.dp).semantics { contentDescription = "Скачивание" }, strokeWidth = 3.dp)
            }
        }
        row.installed && row.benching -> CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = "Идёт тест скорости" }, strokeWidth = 2.dp)
        row.installed && row.report != null -> {
            val v = row.report.verdict
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (row.activeBackend != null) Icon(painterResource(R.drawable.ic_model_check), contentDescription = "Используется в чате", tint = Color(0xFF1B5E20), modifier = Modifier.size(18.dp))
                Text(v.label, color = verdictColor(v, MaterialTheme.colorScheme), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        row.installed -> Icon(painterResource(R.drawable.ic_model_check), contentDescription = "Скачана", tint = MaterialTheme.colorScheme.primary)
        m.url != null -> IconButton(onClick = { actions.onDownload(m.packId) }) {
            Icon(painterResource(R.drawable.ic_model_download), contentDescription = if (row.partial) "Продолжить загрузку ${m.title}" else "Скачать ${m.title}")
        }
    }
}

@Composable
private fun SearchBox(state: LocalModelUiState, actions: LocalModelActions) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Поиск по litert-community: Qwen3, Phi-4-mini, SmolLM, TinyLlama и др. Эти модели не проверены ни Google, ни нами — на ваш риск.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = actions.onSearchQuery,
            label = { Text("Найти модель") },
            singleLine = true,
            isError = state.searchError != null,
            supportingText = state.searchError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.onSearch() }),
            trailingIcon = {
                if (state.searchBusy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else IconButton(onClick = actions.onSearch, enabled = state.searchQuery.isNotBlank()) { Icon(painterResource(R.drawable.ic_model_search), contentDescription = "Искать") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(row: ModelRow, hasToken: Boolean, actions: LocalModelActions) {
    val m = row.model
    val uri = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = { actions.onSelect(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp).testTag("model-sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(m.title, style = MaterialTheme.typography.titleLarge)
            ModelBadges(row, hasToken, withFit = false)
            Text(
                listOfNotNull(m.sizeBytes?.let { FitEstimator.size(it) } ?: "размер неизвестен", m.fileName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (m.description.isNotBlank()) Text(m.description, style = MaterialTheme.typography.bodyMedium)
            if (m.actionsOnly) Text("Только вызов функций (действия телефона), для разговора о машине не подходит.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            val facts = listOfNotNull(
                m.accelerators.takeIf { it.isNotEmpty() }?.let { "Ускорители: " + it.joinToString(", ") { a -> a.uppercase() } },
                m.minRamGb?.let { "Gallery рекомендует от $it ГБ ОЗУ" },
                m.license.takeIf { it.isNotBlank() }?.let { "Лицензия: $it" },
            )
            facts.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (m.sourceUrl.isNotBlank()) {
                TextButton(onClick = { runCatching { uri.openUri(m.sourceUrl) } }) { Text("Страница модели") }
            }
            if (row.report == null) {
                Text("Оценка: ${row.fit.fit.label}", color = fitColor(row.fit.fit, MaterialTheme.colorScheme), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                row.fit.reasons.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (m.gated && !hasToken && !row.installed) {
                Text("Закрытая модель: введите токен Hugging Face внизу экрана и примите лицензию на странице модели.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider()
            when {
                row.downloading -> {
                    val total = m.sizeBytes
                    if (row.progress != null && row.progress >= 0) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Ждёт повтора после обрыва: нажатие перезапускает загрузку сразу (из открытого приложения).
                        if (row.retrying) Button(onClick = { actions.onDownload(m.packId) }) { Text("Повторить сейчас") }
                        OutlinedButton(onClick = { actions.onCancelDownload(m.packId) }) { Text("Остановить") }
                    }
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
    Card(Modifier.fillMaxWidth().testTag("verdict-${row.model.packId}"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Вердикт: ${verdict.label}", color = verdictColor(verdict, MaterialTheme.colorScheme), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Любой файл LiteRT-LM (.litertlm): ссылка на Hugging Face или файл с телефона.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun TokenCard(state: LocalModelUiState, actions: LocalModelActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Токен Hugging Face (необязательно)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Нужен только для закрытых моделей (Gemma 3, Gemma 3n, FunctionGemma). Хранится зашифрованным ключом Android Keystore и уходит только на huggingface.co.",
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

@Composable
private fun ConfirmDialog(c: DownloadConfirm, actions: LocalModelActions) {
    AlertDialog(
        onDismissRequest = actions.onDismissConfirm,
        title = { Text("Скачать «${c.title}»?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Размер: " + (c.sizeBytes?.let { FitEstimator.size(it) } ?: "неизвестен") + ". Оценка: ${c.fit.fit.label}.")
                c.fit.reasons.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Скачиваете на свой риск: модель может не запуститься или работать медленно — это покажет тест после скачивания. Загрузку можно остановить и продолжить позже.")
                if (c.adopt != null) Text("Модель из Hugging Face не проверена ни Google, ни нами.", style = MaterialTheme.typography.bodySmall)
                if (c.metered) Text("Вы на мобильной сети: загрузка израсходует трафик.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                if (c.gatedWithoutToken) Text("Модель закрыта: без токена Hugging Face загрузка завершится ошибкой 401.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = actions.onConfirmDownload) { Text(if (c.metered) "Скачать по мобильной сети" else "Скачать") } },
        dismissButton = { TextButton(onClick = actions.onDismissConfirm) { Text("Отмена") } },
    )
}
