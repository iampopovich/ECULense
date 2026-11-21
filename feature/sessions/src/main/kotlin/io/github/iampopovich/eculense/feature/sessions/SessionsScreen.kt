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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.model.Session
import io.github.iampopovich.eculense.core.model.SessionSource
import io.github.iampopovich.eculense.core.navigation.BackupDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.navigation.RouteHandler
import io.github.iampopovich.eculense.core.navigation.SessionDetailDestination
import io.github.iampopovich.eculense.core.ui.EcuDates
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinViewModel
import io.github.iampopovich.eculense.core.ui.R as CoreUiR

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
            OutlinedButton(onClick = onImport, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.sessions_import))
            }
        }
        if (sessions.isEmpty()) {
            Text(stringResource(R.string.sessions_empty), Modifier.padding(16.dp))
            return@Column
        }
        LazyColumn {
            items(sessions, key = { it.id }) { session ->
                ListItem(
                    headlineContent = { Text(session.name) },
                    supportingContent = { Text(stringResource(CoreUiR.string.common_join_dot, EcuDates.dateTime(session.startedAtMs), stringResource(session.source.labelRes))) },
                    trailingContent = { TextButton(onClick = { onDelete(session) }) { Text(stringResource(CoreUiR.string.common_delete)) } },
                    modifier = Modifier.clickable { onOpen(session) },
                )
            }
        }
    }
}

internal val SessionSource.labelRes: Int
    get() = when (this) {
        SessionSource.LIVE -> R.string.sessions_source_live
        SessionSource.IMPORT_CARSCANNER -> R.string.sessions_source_carscanner
    }
