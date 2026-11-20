package io.github.iampopovich.eculense.feature.connect

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.navigation.AdapterSettingsDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.ReplayTransportFactory
import io.github.iampopovich.eculense.core.obd.isSessionAlive
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
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refresh()
    }
    LaunchedEffect(Unit) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) viewModel.refresh() else permissions.launch(needed.toTypedArray())
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
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(statusText(state), style = MaterialTheme.typography.titleMedium)
        if (state is ConnectionState.Failed) {
            ObdText.hint(state.error, state.endpoint.kind)?.let { hint ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text(
                        hint.asString(),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }
        }
        if (state is ConnectionState.Connected) AdapterRows(state)
        if (state.isSessionAlive) {
            Button(onClick = onDisconnect) { Text(stringResource(R.string.connect_disconnect)) }
        }
        OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.connect_refresh)) }
        TextButton(onClick = onSettings) { Text(stringResource(R.string.connect_settings)) }
        if (endpoints.isEmpty()) {
            Text(stringResource(R.string.connect_empty))
        }
        LazyColumn {
            items(endpoints, key = { it.kind + it.address }) { endpoint ->
                ListItem(
                    headlineContent = { Text(ObdText.endpointLabel(endpoint).asString()) },
                    supportingContent = { Text(ObdText.endpointDetail(endpoint).asString()) },
                    modifier = Modifier.clickable(enabled = state !is ConnectionState.Connecting) { onConnect(endpoint) },
                )
            }
        }
    }
}

/** A6: compact rows with what the adapter is and how it talks to the car. */
@Composable
private fun AdapterRows(state: ConnectionState.Connected) {
    val caps = state.capabilities
    val version = ObdText.adapterVersion(state.info.version).asString()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(stringResource(R.string.connect_row_adapter, version, ObdText.adapterKind(caps.kind).asString()))
        caps.stnId?.let { Row(stringResource(R.string.connect_row_chip, listOfNotNull(it, caps.deviceId).joinToString(" · "))) }
        caps.vlinkerId?.let { Row(stringResource(R.string.connect_row_chip, it)) }
        Row(stringResource(R.string.connect_row_protocol, ObdText.protocolName(state.info.protocolNumber).asString()))
        state.info.batteryVoltage?.let { Row(stringResource(R.string.connect_row_voltage, volts(it))) }
        Row(stringResource(R.string.connect_row_pids, state.supportedPids.size))
        if (caps.unsupported.isNotEmpty()) {
            Row(stringResource(R.string.connect_row_unsupported, caps.unsupported.sorted().joinToString(", ")))
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
