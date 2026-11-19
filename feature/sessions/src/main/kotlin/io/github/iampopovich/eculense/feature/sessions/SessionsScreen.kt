package io.github.iampopovich.eculense.feature.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.model.Session
import io.github.iampopovich.eculense.core.model.SessionSource
import io.github.iampopovich.eculense.core.navigation.BackupDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.navigation.RouteHandler
import io.github.iampopovich.eculense.core.navigation.SessionDetailDestination
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinViewModel
import io.github.iampopovich.eculense.core.ui.EcuDates

@Composable
internal fun SessionsRoute(viewModel: SessionsViewModel = koinViewModel()) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val koin = getKoin()
    // import lives in :feature:backup; without it (feature removed from the build) there's simply no button
    val hasBackup = remember { koin.getAll<RouteHandler>().any { it.routeClass == BackupDestination::class } }
    SessionsScreen(
        sessions,
        onOpen = { navigator.navigate(SessionDetailDestination(it.id)) },
        onDelete = { viewModel.delete(it.id) },
        onImport = if (hasBackup) ({ navigator.navigate(BackupDestination) }) else null,
    )
}

@Composable
internal fun SessionsScreen(
    sessions: List<Session>,
    onOpen: (Session) -> Unit,
    onDelete: (Session) -> Unit,
    modifier: Modifier = Modifier,
    onImport: (() -> Unit)? = null,
) {
    Column(modifier) {
        if (onImport != null) {
            OutlinedButton(onClick = onImport, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Text("Импорт из Car Scanner") }
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

private val SessionSource.label: String
    get() = when (this) {
        SessionSource.LIVE -> "запись с адаптера"
        SessionSource.IMPORT_CARSCANNER -> "импорт из Car Scanner"
    }
