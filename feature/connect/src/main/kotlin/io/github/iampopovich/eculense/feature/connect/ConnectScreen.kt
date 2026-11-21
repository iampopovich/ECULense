package io.github.iampopovich.eculense.feature.connect

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.navigation.AdapterSettingsDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.ObdProtocols
import io.github.iampopovich.eculense.core.obd.PollStats
import io.github.iampopovich.eculense.core.obd.ReplayTransportFactory
import io.github.iampopovich.eculense.core.obd.VehicleInfo
import io.github.iampopovich.eculense.core.obd.isSessionAlive
import io.github.iampopovich.eculense.core.obd.isSimulated
import io.github.iampopovich.eculense.core.ui.HiddenVin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.iampopovich.eculense.core.obdtext.ObdText
import org.koin.compose.viewmodel.koinViewModel
import java.util.Locale

@Composable
internal fun ConnectRoute(viewModel: ConnectViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val endpoints by viewModel.endpoints.collectAsStateWithLifecycle()
    val pollStats by viewModel.pollStats.collectAsStateWithLifecycle()
    val vehicleInfo by viewModel.vehicleInfo.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var replayEndpoint by remember { mutableStateOf<AdapterEndpoint?>(null) }
    // Replay adapter: pick an exported traffic log (text), read it off the main thread, then connect through it.
    val pickLog = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val endpoint = replayEndpoint
        if (uri != null && endpoint != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes().let { b -> if (b.size > MAX_LOG_BYTES) b.copyOf(MAX_LOG_BYTES) else b }.decodeToString() }
                    }.getOrNull()
                }
                if (!text.isNullOrBlank()) viewModel.connectReplay(endpoint, text)
            }
        }
    }
    val ble by viewModel.ble.collectAsStateWithLifecycle()
    val hasBle = ble != null
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refresh()
        viewModel.startScan()
    }
    LaunchedEffect(Unit) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            // A: «Nearby devices» scan (neverForLocation). On API <= 30 location is asked only from the BLE section.
            if (Build.VERSION.SDK_INT >= 31 && hasBle) add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) viewModel.refresh() else permissions.launch(needed.toTypedArray())
    }
    // A: BLE scans run only while this screen is visible (~10 s each); leaving the screen stops them.
    LifecycleStartEffect(Unit) {
        viewModel.startScan()
        onStopOrDispose { viewModel.stopScan() }
    }
    val askScanPermission = {
        val permission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        permissions.launch(arrayOf(permission))
    }
    ConnectScreen(
        state,
        endpoints,
        onConnect = { endpoint ->
            if (endpoint.kind == ReplayTransportFactory.KIND) {
                replayEndpoint = endpoint
                pickLog.launch(arrayOf("text/plain"))
            } else {
                viewModel.connect(endpoint)
            }
        },
        viewModel::disconnect,
        viewModel::refresh,
        onSettings = { navigator.navigate(AdapterSettingsDestination) },
        pollStats = pollStats,
        vehicleInfo = vehicleInfo,
        ble = ble,
        onBleScan = viewModel::startScan,
        onBleAllow = askScanPermission,
        onBleShowAll = viewModel::setShowAll,
    )
}

private const val MAX_LOG_BYTES = 4 * 1024 * 1024

@Composable
internal fun ConnectScreen(
    state: ConnectionState,
    endpoints: List<AdapterEndpoint>,
    onConnect: (AdapterEndpoint) -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onSettings: () -> Unit = {},
    pollStats: PollStats? = null,
    vehicleInfo: VehicleInfo? = null,
    ble: BleSection? = null,
    onBleScan: () -> Unit = {},
    onBleAllow: () -> Unit = {},
    onBleShowAll: (Boolean) -> Unit = {},
) {
    // One scrolling list: with a large font the adapter card alone fills the screen, and a fixed Column cut off
    // the buttons and the adapter list below it (C13 screenshot).
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "status") { Text(statusText(state), style = MaterialTheme.typography.titleMedium) }
        if (state is ConnectionState.Failed) {
            ObdText.hint(state.error, state.endpoint.kind)?.let { hint ->
                item(key = "hint") {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Text(
                            hint.asString(),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                        )
                    }
                }
            }
        }
        if (state is ConnectionState.Connected) item(key = "caps") { AdapterRows(state, pollStats, vehicleInfo) }
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.isSessionAlive) {
                    Button(onClick = onDisconnect) { Text(stringResource(R.string.connect_disconnect)) }
                }
                OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.connect_refresh)) }
                TextButton(onClick = onSettings) { Text(stringResource(R.string.connect_settings)) }
                if (endpoints.isEmpty()) {
                    Text(stringResource(R.string.connect_empty))
                }
            }
        }
        // R-I: real adapters only; simulated endpoints (debug builds) go into the collapsed «For development» section.
        val (real, simulated) = endpoints.partition { !it.isSimulated }
        val enabled = state !is ConnectionState.Connecting
        items(real, key = { it.kind + it.address }) { endpoint -> EndpointRow(endpoint, enabled, onConnect) }
        if (ble != null) bleSection(ble, enabled, onConnect, onBleScan, onBleAllow, onBleShowAll)
        devEndpoints(simulated, enabled, onConnect)
    }
}

/** One adapter row (label + address/description); tap connects. */
@Composable
internal fun EndpointRow(endpoint: AdapterEndpoint, enabled: Boolean, onConnect: (AdapterEndpoint) -> Unit) {
    ListItem(
        headlineContent = { Text(ObdText.endpointLabel(endpoint).asString()) },
        supportingContent = { Text(ObdText.endpointDetail(endpoint).asString()) },
        modifier = Modifier.clickable(enabled = enabled) { onConnect(endpoint) },
    )
}

/** A6 + B5: what the adapter is, how it talks to the car, what the ECU offers and how fast polling really is. */
@Composable
private fun AdapterRows(state: ConnectionState.Connected, stats: PollStats?, info: VehicleInfo?) {
    val caps = state.capabilities
    val version = ObdText.adapterVersion(state.info.version).asString()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.connect_caps_title), style = MaterialTheme.typography.titleSmall)
        Row(stringResource(R.string.connect_row_adapter_version, version))
        ObdText.adapterNote(caps)?.let {
            Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ObdText.adapterChip(caps)?.let { Row(stringResource(R.string.connect_row_chip, it)) }
        Row(stringResource(R.string.connect_row_protocol, ObdText.protocolName(state.info.protocolNumber).asString()))
        state.info.batteryVoltage?.let { Row(stringResource(R.string.connect_row_voltage, volts(it))) }
        Row(stringResource(R.string.connect_row_pids, state.supportedPids.size))
        val cycleMs = stats?.cycleMsP50?.takeIf { it > 0 }
        if (cycleMs != null) {
            Row(stringResource(R.string.connect_row_poll_rate, perSecond(1_000.0 / cycleMs), seconds(cycleMs / 1_000.0)))
        } else {
            Row(stringResource(R.string.connect_row_poll_rate_unknown))
        }
        if (ObdProtocols.isKLine(state.info.protocolNumber)) {
            Text(stringResource(R.string.connect_kline_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val vin = info?.vin
        when {
            info == null -> Row(stringResource(R.string.connect_row_vin_reading))
            vin != null -> HiddenVin(vin, stringResource(R.string.connect_row_vin))
            else -> Row(stringResource(R.string.connect_row_no_vin))
        }
        info?.calibrationIds?.takeIf { it.isNotEmpty() }?.let { Row(stringResource(R.string.connect_row_calid, it.joinToString(", "))) }
        info?.cvns?.takeIf { it.isNotEmpty() }?.let { Row(stringResource(R.string.connect_row_cvn, it.joinToString(", "))) }
        if (caps.unsupported.isNotEmpty()) {
            Row(stringResource(R.string.connect_row_unsupported, caps.unsupported.sorted().joinToString(", ")))
        }
    }
    }
}

@Composable
private fun Row(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Device names are shown as is; built-in endpoints and typed failures are localized by :core:obdtext. */
@Composable
private fun statusText(state: ConnectionState): String = when (state) {
    ConnectionState.Disconnected -> stringResource(R.string.connect_status_disconnected)
    is ConnectionState.Connecting -> stringResource(R.string.connect_status_connecting, ObdText.endpointLabel(state.endpoint).asString())
    is ConnectionState.Connected -> stringResource(R.string.connect_status_connected, ObdText.endpointLabel(state.endpoint).asString())
    is ConnectionState.Reconnecting, is ConnectionState.EngineOff -> ObdText.sessionState(state)?.asString().orEmpty()
    is ConnectionState.Failed -> stringResource(R.string.connect_status_failed, ObdText.error(state.error).asString())
}

/** Plain helper: number formatting outside composables (lint NonObservableLocale). */
private fun volts(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)

private fun perSecond(v: Double): String = String.format(Locale.getDefault(), if (v >= 10) "%.0f" else "%.1f", v)

private fun seconds(v: Double): String = String.format(Locale.getDefault(), if (v >= 10) "%.0f" else if (v >= 1) "%.1f" else "%.2f", v)
