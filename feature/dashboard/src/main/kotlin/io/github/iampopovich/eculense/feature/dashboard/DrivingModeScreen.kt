package io.github.iampopovich.eculense.feature.dashboard

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/**
 * C6 driving mode: 2–6 big tiles (the first ones of the dashboard selection), readable in a ≤2 s glance. Tap anywhere
 * shows/hides the few controls; no text input. Day/night follows the system theme; the HUD mirror flips the picture
 * horizontally on a black background for a windshield reflection.
 */
@Composable
internal fun DrivingModeRoute(viewModel: DashboardViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    KeepScreenOn(state.keepScreenOn)
    HideSystemBars()
    DrivingModeScreen(
        state = state,
        onExit = { back?.onBackPressed() },
        onTiles = viewModel::setDrivingTiles,
        onMirror = viewModel::setHudMirror,
    )
}

@Composable
private fun HideSystemBars() {
    val view = LocalView.current
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window, view) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Grid shape for [count] tiles: columns × rows, wider in landscape. */
internal fun drivingGrid(count: Int, landscape: Boolean): Pair<Int, Int> {
    val columns = when {
        count <= 1 -> 1
        landscape -> if (count <= 3) count else (count + 1) / 2
        else -> if (count <= 3) 1 else 2
    }
    return columns to (count + columns - 1) / columns
}

@Composable
internal fun DrivingModeScreen(
    state: DashboardUiState,
    onExit: () -> Unit,
    onTiles: (Int) -> Unit,
    onMirror: (Boolean) -> Unit,
) {
    var controls by rememberSaveable { mutableStateOf(true) }
    val mirror = state.hudMirror
    val dark = mirror || isSystemInDarkTheme()
    val background = if (mirror) Color.Black else MaterialTheme.colorScheme.background
    val foreground = if (mirror) Color.White else MaterialTheme.colorScheme.onBackground
    val tiles = state.tiles.take(state.drivingTiles)
    Box(
        Modifier.fillMaxSize().background(background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controls = !controls },
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Controls take their own row at the top (never drawn over the tiles) and are not mirrored.
            if (controls) DrivingControls(state, onExit, onTiles, onMirror, Modifier.align(Alignment.CenterHorizontally))
            Column(
                Modifier.weight(1f).fillMaxWidth().graphicsLayer { if (mirror) scaleX = -1f },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DrivingContent(state, tiles, foreground, dark, mirror)
            }
        }
    }
}

@Composable
private fun ColumnScope.DrivingContent(state: DashboardUiState, tiles: List<TileState>, foreground: Color, dark: Boolean, mirror: Boolean) {
            state.banner?.let {
                Text(
                    it.asString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (mirror) Color.White else MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (mirror) Color.DarkGray else MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.medium)
                        .padding(12.dp),
                )
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val (columns, rows) = drivingGrid(tiles.size, landscape = maxWidth > maxHeight)
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (r in 0 until rows) {
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (c in 0 until columns) {
                                val tile = tiles.getOrNull(r * columns + c)
                                Box(Modifier.weight(1f).fillMaxSize()) {
                                    if (tile != null) {
                                        // A subtle surface + outline so neighbouring tiles read as separate.
                                        val shape = MaterialTheme.shapes.large
                                        val surface = if (mirror) Color(0xFF161616) else MaterialTheme.colorScheme.surfaceContainer
                                        val outline = if (mirror) Color(0xFF3A3A3A) else MaterialTheme.colorScheme.outlineVariant
                                        Box(Modifier.fillMaxSize().background(surface, shape).border(1.dp, outline, shape)) {
                                            DrivingTile(tile, foreground, dark)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
}

@Composable
private fun DrivingTile(tile: TileState, foreground: Color, dark: Boolean) {
    val dim = tile.status == ValueStatus.STALE
    // Dimmed values keep a readable contrast: 70 % white on black / dark grey on light.
    val valueColor = if (dim) foreground.copy(alpha = if (dark) 0.7f else 0.65f) else foreground
    val note = tileNote(tile)
    Column(Modifier.fillMaxSize().padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(tileName(tile), style = MaterialTheme.typography.titleMedium, color = foreground, maxLines = 1, textAlign = TextAlign.Center)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                tileValueText(tile),
                color = valueColor,
                maxLines = 1,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.displayLarge,
                autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 200.sp, stepSize = 4.sp),
            )
        }
        Text(listOfNotNull(tile.unit.takeIf { it.isNotEmpty() }, note).joinToString(" · "), style = MaterialTheme.typography.titleMedium, color = valueColor, maxLines = 1)
    }
}

@Composable
private fun DrivingControls(
    state: DashboardUiState,
    onExit: () -> Unit,
    onTiles: (Int) -> Unit,
    onMirror: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onExit) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.drive_exit))
            }
            FilledTonalIconButton(
                onClick = { onTiles(state.drivingTiles - 1) },
                enabled = state.drivingTiles > io.github.iampopovich.eculense.core.data.DashboardSelection.MIN_DRIVING_TILES,
            ) {
                Icon(painterResource(R.drawable.ic_remove), contentDescription = stringResource(R.string.drive_fewer))
            }
            Text(stringResource(R.string.drive_tiles_count, state.drivingTiles), style = MaterialTheme.typography.titleMedium)
            FilledTonalIconButton(
                onClick = { onTiles(state.drivingTiles + 1) },
                enabled = state.drivingTiles < io.github.iampopovich.eculense.core.data.DashboardSelection.MAX_DRIVING_TILES,
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.drive_more))
            }
            FilledIconToggleButton(checked = state.hudMirror, onCheckedChange = onMirror) {
                Icon(painterResource(R.drawable.ic_flip), contentDescription = stringResource(R.string.drive_mirror))
            }
        }
    }
}
