package io.github.iampopovich.eculense.feature.dtc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.MidGroup
import io.github.iampopovich.eculense.core.obd.ObdDiagnostics
import io.github.iampopovich.eculense.core.obd.TestResult
import io.github.iampopovich.eculense.core.obdtext.ObdText
import io.github.iampopovich.eculense.core.ui.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

internal data class Mode06UiState(
    val loading: Boolean = false,
    /** K-Line / J1850: mode 06 is not read there, the screen explains why. */
    val notCan: Boolean = false,
    val groups: List<Pair<MidGroup, List<TestResult>>> = emptyList(),
    val checked: Boolean = false,
    val error: UiText? = null,
)

/** B8: on-board test results, read on demand (CAN only). */
internal class Mode06ViewModel(private val controller: ConnectionController) : ViewModel() {
    private val state = MutableStateFlow(Mode06UiState())
    val uiState: StateFlow<Mode06UiState> = state.asStateFlow()

    fun read() {
        val connected = controller.state.value as? ConnectionState.Connected
        if (connected == null) {
            state.update { it.copy(error = UiText.Res(R.string.dtc_err_no_connection)) }
            return
        }
        if (!connected.info.isCan) {
            state.update { it.copy(notCan = true, error = null) }
            return
        }
        viewModelScope.launch {
            state.update { it.copy(loading = true, error = null) }
            try {
                val results = controller.withSession { ObdDiagnostics(it).mode06() }
                if (results == null) {
                    state.update { it.copy(error = UiText.Res(R.string.dtc_err_no_connection)) }
                } else {
                    val groups = results.groupBy { it.group }.toSortedMap().map { (g, list) -> g to list }
                    state.update { it.copy(groups = groups, checked = true) }
                }
            } catch (e: Exception) {
                state.update { it.copy(error = ObdText.error(e)) }
            } finally {
                state.update { it.copy(loading = false) }
            }
        }
    }
}

@Composable
internal fun Mode06Route(viewModel: Mode06ViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.read() }
    Mode06Screen(state, viewModel::read)
}

@Composable
internal fun Mode06Screen(state: Mode06UiState, onRead: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "head") {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.mode06_intro), style = MaterialTheme.typography.bodyMedium)
                if (!state.notCan) Button(onClick = onRead, enabled = !state.loading) { Text(stringResource(R.string.mode06_read)) }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                if (state.notCan) Text(stringResource(R.string.mode06_kline))
                if (state.checked && state.groups.isEmpty()) Text(stringResource(R.string.mode06_none))
            }
        }
        state.groups.forEach { (group, results) ->
            item(key = "g-$group") {
                Text(stringResource(DiagText.group(group)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            items(results, key = { "r-${it.mid}-${it.tid}-${it.uasId}" }) { ResultRow(it) }
        }
    }
}

@Composable
private fun ResultRow(r: TestResult) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.mode06_test, "%02X".format(r.tid), "%02X".format(r.mid)), style = MaterialTheme.typography.bodyMedium)
            val unit = r.unit?.symbol?.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
            val decimals = if (r.unit == null) 0 else 3
            val value = DiagText.number(r.value, decimals) + unit
            val min = DiagText.number(r.min, decimals)
            val max = DiagText.number(r.max, decimals) + unit
            val line = if (r.unit == null) {
                stringResource(R.string.mode06_limits_raw, value, min, max, "%02X".format(r.uasId))
            } else {
                stringResource(R.string.mode06_limits, value, min, max)
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        val (text, bg, fg) = if (r.passed) {
            Triple(R.string.mode06_pass, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        } else {
            Triple(R.string.mode06_fail, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        }
        Surface(color = bg, shape = MaterialTheme.shapes.small) {
            Text(stringResource(text), color = fg, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}
