package io.github.iampopovich.eculense.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.navigation.CarDataDestination
import io.github.iampopovich.eculense.core.navigation.ConnectDestination
import io.github.iampopovich.eculense.core.navigation.DrivingModeDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.navigation.RouteHandler
import androidx.compose.runtime.remember
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun DashboardRoute(viewModel: DashboardViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    // The session name is stored in the database, so it is built in the language active when recording starts.
    val sessionName = stringResource(R.string.dash_session_name)
    KeepScreenOn(state.connected && state.keepScreenOn)
    // The car data screen is an optional module: the button shows only if it is wired in.
    val koin = getKoin()
    val hasCarData = remember { koin.getAll<RouteHandler>().any { it.routeClass == CarDataDestination::class } }
    DashboardScreen(
        state,
        onToggleRecording = { viewModel.toggleRecording { stamp -> sessionName.format(stamp) } },
        onConnect = { navigator.navigate(ConnectDestination) },
        onCarData = if (hasCarData) ({ navigator.navigate(CarDataDestination) }) else null,
        onDriving = { navigator.navigate(DrivingModeDestination) },
    )
}

/** C6: keep the screen on while connected (setting, default on). */
@Composable
internal fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
internal fun DashboardScreen(
    state: DashboardUiState,
    onToggleRecording: () -> Unit,
    onConnect: () -> Unit,
    onCarData: (() -> Unit)?,
    onDriving: () -> Unit,
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
        // Record button on the left, icon-only actions on the right: no long label next to a weight(1f) text.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onToggleRecording) {
                Text(stringResource(if (state.recording) R.string.dash_stop_recording else R.string.dash_record))
            }
            Spacer(Modifier.weight(1f))
            if (onCarData != null) {
                IconButton(onClick = onCarData) {
                    Icon(painterResource(R.drawable.ic_list), contentDescription = stringResource(R.string.dash_open_car_data))
                }
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDriving) {
                Icon(painterResource(R.drawable.ic_fullscreen), contentDescription = stringResource(R.string.dash_open_driving))
            }
        }
        if (state.tiles.isEmpty()) {
            Text(stringResource(R.string.dash_no_pids), style = MaterialTheme.typography.bodyLarge)
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.tiles, key = { it.pid.sensorId }) { tile -> DashboardTile(tile) }
        }
    }
}

@Composable
private fun DashboardTile(tile: TileState) {
    val dim = tile.status == ValueStatus.STALE
    val valueColor = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val note = tileNote(tile)
    val valueText = tileValueText(tile)
    Card {
        Column(Modifier.padding(12.dp)) {
            Text(tileName(tile), style = MaterialTheme.typography.labelMedium, maxLines = 2)
            Text(
                valueText,
                style = MaterialTheme.typography.headlineMedium,
                color = valueColor,
                modifier = Modifier.semantics { contentDescription = listOfNotNull(valueText, tile.unit, note).joinToString(" ") },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tile.unit, style = MaterialTheme.typography.labelSmall)
                note?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Sparkline(
                tile.spark,
                color = if (dim) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
