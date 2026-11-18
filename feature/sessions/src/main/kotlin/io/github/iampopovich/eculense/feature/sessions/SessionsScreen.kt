package io.github.iampopovich.eculense.feature.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.model.Session
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.navigation.SessionDetailDestination
import org.koin.compose.viewmodel.koinViewModel
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SessionsRoute(viewModel: SessionsViewModel = koinViewModel()) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    SessionsScreen(sessions, onOpen = { navigator.navigate(SessionDetailDestination(it.id)) }, onDelete = { viewModel.delete(it.id) })
}

@Composable
internal fun SessionsScreen(sessions: List<Session>, onOpen: (Session) -> Unit, onDelete: (Session) -> Unit, modifier: Modifier = Modifier) {
    if (sessions.isEmpty()) {
        Text("Записей пока нет. Нажмите «Записывать» на панели.", modifier)
        return
    }
    LazyColumn(modifier) {
        items(sessions, key = { it.id }) { session ->
            ListItem(
                headlineContent = { Text(session.name) },
                supportingContent = { Text(DateFormat.getDateTimeInstance().format(Date(session.startedAtMs)) + " · " + session.source.name) },
                trailingContent = { TextButton(onClick = { onDelete(session) }) { Text("Удалить") } },
                modifier = Modifier.clickable { onOpen(session) },
            )
        }
    }
}
