package io.github.iampopovich.eculense.feature.connect

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun ConnectRoute(viewModel: ConnectViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val endpoints by viewModel.endpoints.collectAsStateWithLifecycle()
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
    ConnectScreen(state, endpoints, viewModel::connect, viewModel::disconnect, viewModel::refresh)
}

@Composable
internal fun ConnectScreen(
    state: ConnectionState,
    endpoints: List<AdapterEndpoint>,
    onConnect: (AdapterEndpoint) -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(statusText(state), style = MaterialTheme.typography.titleMedium)
        if (state is ConnectionState.Connected) {
            Text(stringResource(R.string.connect_adapter_info, state.info.version, state.info.protocolNumber, state.supportedPids.size))
            Button(onClick = onDisconnect) { Text(stringResource(R.string.connect_disconnect)) }
        }
        OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.connect_refresh)) }
        if (endpoints.isEmpty()) {
            Text(stringResource(R.string.connect_empty))
        }
        LazyColumn {
            items(endpoints, key = { it.kind + it.address }) { endpoint ->
                ListItem(
                    headlineContent = { Text(endpoint.label) },
                    supportingContent = { Text(endpoint.address) },
                    modifier = Modifier.clickable(enabled = state !is ConnectionState.Connecting) { onConnect(endpoint) },
                )
            }
        }
    }
}

/** Adapter labels and failure reasons come from the transport layer and are shown as is. */
@Composable
private fun statusText(state: ConnectionState): String = when (state) {
    ConnectionState.Disconnected -> stringResource(R.string.connect_status_disconnected)
    is ConnectionState.Connecting -> stringResource(R.string.connect_status_connecting, state.endpoint.label)
    is ConnectionState.Connected -> stringResource(R.string.connect_status_connected, state.endpoint.label)
    is ConnectionState.Failed -> stringResource(R.string.connect_status_failed, state.reason)
}
