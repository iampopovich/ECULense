package io.github.iampopovich.eculense.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import io.github.iampopovich.eculense.core.data.MarkResult
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.navigation.DtcDestination
import io.github.iampopovich.eculense.core.ui.CarNames
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
        onMark = viewModel::markEvent,
        onOpenCodes = {
            viewModel.dismissNewCodes()
            navigator.navigate(DtcDestination)
        },
    )
    MarkResultEffect(state.markResult, viewModel::markShown)
    state.carChoice?.let { cars -> CarChoiceSheet(cars, onPick = viewModel::startRecordingFor, onDismiss = viewModel::dismissCarChoice) }
}

/** B10: the result of a mark shows as a short toast (no reading needed while driving: the button gives feedback too). */
@Composable
internal fun MarkResultEffect(result: MarkResult?, onShown: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    LaunchedEffect(result) {
        val r = result ?: return@LaunchedEffect
        val text = when (r) {
            MarkResult.SAVED -> R.string.dash_mark_saved
            MarkResult.NOT_RECORDING -> R.string.dash_mark_not_recording
            MarkResult.SIMULATED -> DevText.markSimulated
        }
        Toast.makeText(context, resources.getString(text), Toast.LENGTH_SHORT).show()
        onShown()
    }
}

/** C8: «For which car?» before a recording, with «Don't ask again». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarChoiceSheet(cars: List<CarProfile>, onPick: (Long?, Boolean) -> Unit, onDismiss: () -> Unit) {
    var dontAsk by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(stringResource(R.string.dash_which_car), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            cars.forEach { car ->
                ListItem(
                    headlineContent = { Text(CarNames.display(car)) },
                    supportingContent = listOfNotNull(car.brand, car.model).joinToString(" ").takeIf { it.isNotBlank() }?.let { { Text(it) } },
                    modifier = Modifier.clickable { onPick(car.id, dontAsk) },
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.dash_dont_ask)) },
                leadingContent = { Checkbox(checked = dontAsk, onCheckedChange = null) },
                modifier = Modifier.toggleable(dontAsk, role = Role.Checkbox) { dontAsk = it },
            )
        }
    }
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
    onMark: () -> Unit = {},
    onOpenCodes: () -> Unit = {},
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
        AlertBanners(state, onOpenCodes)
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
        // The button may shrink and wrap (large font, small screen) but never pushes the icons off screen.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onToggleRecording, modifier = Modifier.weight(1f, fill = false)) {
                Text(stringResource(if (state.recording) R.string.dash_stop_recording else R.string.dash_record), maxLines = 2)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
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
        }
        // B10: a big full-width target of its own, only while a recording runs (a mark belongs to a recording).
        if (state.recording) {
            Button(onClick = onMark, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(painterResource(R.drawable.ic_flag), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.dash_mark_event), style = MaterialTheme.typography.titleMedium)
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
    val valueColor = when {
        tile.alert -> MaterialTheme.colorScheme.onErrorContainer
        dim -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val note = tileNote(tile)
    val valueText = tileValueText(tile)
    val colors = if (tile.alert) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(colors = colors) {
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
