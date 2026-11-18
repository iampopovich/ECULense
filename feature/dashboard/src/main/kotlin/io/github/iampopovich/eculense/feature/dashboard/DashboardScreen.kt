package io.github.iampopovich.eculense.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.navigation.ConnectDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun DashboardRoute(viewModel: DashboardViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    DashboardScreen(state, onToggleRecording = viewModel::toggleRecording, onConnect = { navigator.navigate(ConnectDestination) })
}

@Composable
internal fun DashboardScreen(
    state: DashboardUiState,
    onToggleRecording: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.connected) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Нет подключения к адаптеру")
                Button(onClick = onConnect) { Text("Подключить") }
            }
        }
        return
    }
    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = onToggleRecording) {
            Text(if (state.recording) "■ Остановить запись" else "● Записывать")
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.values, key = { it.pid.sensorId }) { item ->
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.pid.name, style = MaterialTheme.typography.labelMedium)
                        Text(
                            item.value?.let { "%.1f".format(it) } ?: "—",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(item.pid.unit, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
