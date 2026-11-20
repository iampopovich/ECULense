package io.github.iampopovich.eculense.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.iampopovich.eculense.core.obdtext.ObdText
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.navigation.ConnectDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import org.koin.compose.viewmodel.koinViewModel
import java.util.Locale

@Composable
internal fun DashboardRoute(viewModel: DashboardViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    // The session name is stored in the database, so it is built in the language active when recording starts.
    val sessionName = stringResource(R.string.dash_session_name)
    DashboardScreen(
        state,
        onToggleRecording = { viewModel.toggleRecording { stamp -> sessionName.format(stamp) } },
        onConnect = { navigator.navigate(ConnectDestination) },
    )
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
                Text(stringResource(R.string.dash_not_connected))
                Button(onClick = onConnect) { Text(stringResource(R.string.dash_connect)) }
            }
        }
        return
    }
    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.banner?.let {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                Text(
                    it.asString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
        }
        state.status?.let {
            Text(it.asString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(onClick = onToggleRecording) {
            Text(stringResource(if (state.recording) R.string.dash_stop_recording else R.string.dash_record))
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.values, key = { it.pid.sensorId }) { item ->
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text(ObdText.pidNameRes(item.pid.code)?.let { stringResource(it) } ?: item.pid.sensorId, style = MaterialTheme.typography.labelMedium)
                        Text(
                            item.value?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(item.unit, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
