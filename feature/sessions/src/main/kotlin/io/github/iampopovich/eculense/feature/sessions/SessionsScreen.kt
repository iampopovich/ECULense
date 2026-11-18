package io.github.iampopovich.eculense.feature.sessions

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.model.Session
import io.github.iampopovich.eculense.core.model.SessionSource
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.navigation.SessionDetailDestination
import org.koin.compose.viewmodel.koinViewModel
import io.github.iampopovich.eculense.core.ui.EcuDates

@Composable
internal fun SessionsRoute(viewModel: SessionsViewModel = koinViewModel()) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.import(uri, displayName(context, uri))
    }
    SessionsScreen(
        sessions,
        onOpen = { navigator.navigate(SessionDetailDestination(it.id)) },
        onDelete = { viewModel.delete(it.id) },
        onImport = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
        importState = importState,
    )
}

/** Имя файла нужно импортёру: по расширению он отличает одиночную запись .brc от бэкапа .cbz. */
private fun displayName(context: android.content.Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty()

@Composable
internal fun SessionsScreen(
    sessions: List<Session>,
    onOpen: (Session) -> Unit,
    onDelete: (Session) -> Unit,
    modifier: Modifier = Modifier,
    onImport: () -> Unit = {},
    importState: ImportState = ImportState.Idle,
) {
    Column(modifier) {
        OutlinedButton(
            onClick = onImport,
            enabled = importState !is ImportState.Running,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text("Импорт из Car Scanner") }
        when (importState) {
            ImportState.Idle -> Unit
            is ImportState.Running -> {
                Text("Импорт… обработано записей: ${importState.processed}", Modifier.padding(horizontal = 16.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            }
            is ImportState.Done -> Text(importSummary(importState), Modifier.padding(horizontal = 16.dp))
            is ImportState.Failed -> Text("Импорт не удался: ${importState.message}", Modifier.padding(horizontal = 16.dp))
        }
        if (sessions.isEmpty()) {
            Text("Записей пока нет. Нажмите «Записывать» на панели.", Modifier.padding(16.dp))
            return@Column
        }
        LazyColumn {
            items(sessions, key = { it.id }) { session ->
                ListItem(
                    headlineContent = { Text(session.name) },
                    supportingContent = { Text(EcuDates.dateTime(session.startedAtMs) + " · " + session.source.label) },
                    trailingContent = { TextButton(onClick = { onDelete(session) }) { Text("Удалить") } },
                    modifier = Modifier.clickable { onOpen(session) },
                )
            }
        }
    }
}

/** Что именно перенесено — записи, машина, поездки, единицы и цена топлива (видимость состояния системы). */
internal fun importSummary(s: ImportState.Done): String = buildList {
    add("Импортировано ${s.imported}, пропущено дублей ${s.duplicates}, ошибок ${s.failed}")
    s.car?.let { car ->
        val details = listOfNotNull(car.brand, car.year?.toString()).joinToString(", ")
        val how = if (car.created) "добавлена" else "обновлена"
        add("Машина «${car.name}»${if (details.isNotEmpty()) " ($details)" else ""} — $how")
        car.fuelPrice?.let { add("Цена топлива: ${it.toString().replace('.', ',')} ${car.currency.orEmpty()}/л".replace(" /л", "/л")) }
    }
    if (s.tripsImported + s.tripsSkipped > 0) add("Поездки: добавлено ${s.tripsImported}, уже были ${s.tripsSkipped}")
    if (s.unitsImported) add("Единицы измерения перенесены")
}.joinToString("\n")

private val SessionSource.label: String
    get() = when (this) {
        SessionSource.LIVE -> "запись с адаптера"
        SessionSource.IMPORT_CARSCANNER -> "импорт из Car Scanner"
    }
