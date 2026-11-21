package io.github.iampopovich.eculense.feature.dtc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.dtcdb.DtcInfo
import io.github.iampopovich.eculense.core.dtcdb.DtcSource
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.FreezeFrame
import io.github.iampopovich.eculense.core.navigation.AiChatDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.navigation.Mode06Destination
import io.github.iampopovich.eculense.core.navigation.VehicleModulesDestination
import io.github.iampopovich.eculense.core.obd.MonitorState
import io.github.iampopovich.eculense.core.obd.MonitorStatus
import io.github.iampopovich.eculense.core.obd.Readiness
import io.github.iampopovich.eculense.core.obd.RecentClearCheck
import io.github.iampopovich.eculense.core.obdtext.ObdText
import io.github.iampopovich.eculense.core.ui.EcuDates
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun DtcRoute(viewModel: DtcViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    DtcScreen(
        state = state,
        onRead = viewModel::read,
        onClear = viewModel::clear,
        onAskAi = { code, info ->
            val prompt = askAiPrompt(code, info) { id, args -> resources.getString(id, *args.toTypedArray()) }
            navigator.navigate(AiChatDestination(prompt))
        },
        onMode06 = { navigator.navigate(Mode06Destination) },
        onModules = { navigator.navigate(VehicleModulesDestination) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DtcScreen(
    state: DtcUiState,
    onRead: () -> Unit,
    onClear: (stationaryConfirmed: Boolean) -> Unit,
    onAskAi: (String, DtcInfo) -> Unit,
    modifier: Modifier = Modifier,
    onMode06: () -> Unit = {},
    onModules: () -> Unit = {},
) {
    var confirmClear by remember { mutableStateOf(false) }
    val canClear = !state.loading && (state.items.isNotEmpty() || state.readiness?.milOn == true) && state.speed != SpeedGate.MOVING
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "actions") {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // FlowRow: with a large font the second button moves to the next line instead of wrapping by letters.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRead, enabled = !state.loading) { Text(stringResource(R.string.dtc_read)) }
                    OutlinedButton(onClick = { confirmClear = true }, enabled = canClear) { Text(stringResource(R.string.dtc_clear)) }
                }
                if (state.speed == SpeedGate.MOVING && state.items.isNotEmpty()) {
                    Text(stringResource(R.string.dtc_clear_blocked_moving), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                if (state.checked && state.items.isEmpty() && !state.loading) Text(stringResource(R.string.dtc_no_codes))
            }
        }
        state.comparison?.let { c -> item(key = "comparison") { ComparisonCard(c) } }
        items(state.items, key = { "now-" + it.dtc.code + it.dtc.kind }) { item ->
            Column {
                ListItem(
                    headlineContent = { Text("${item.dtc.code} · ${stringResource(item.dtc.kind.labelRes)}") },
                    supportingContent = { InfoWithAi(item.info, state.canAskAi) { onAskAi(item.dtc.code, item.info) } },
                )
                item.freezeFrame?.let { FreezeFrameBlock(item.dtc.code, it) }
            }
        }
        state.readiness?.let { r -> item(key = "readiness") { ReadinessSection(r, state.thisCycle) } }
        state.usedCar?.let { u -> item(key = "usedcar") { UsedCarCard(u) } }
        if (state.checked) {
            item(key = "mode06") {
                Column {
                    TextButton(onClick = onMode06) { Text(stringResource(R.string.dtc_mode06_open)) }
                    TextButton(onClick = onModules) { Text(stringResource(R.string.dtc_modules_open)) }
                }
            }
        }
        if (state.history.isNotEmpty()) {
            item(key = "history-header") {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.dtc_history_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
            }
            items(state.history, key = { "history-" + it.code }) { h ->
                ListItem(
                    headlineContent = { Text("${h.code} · ${stringResource(h.kind.labelRes)}") },
                    overlineContent = { Text(pluralStringResource(R.plurals.dtc_history_seen, h.count, h.count, EcuDates.date(h.lastMs))) },
                    supportingContent = { InfoWithAi(h.info, state.canAskAi) { onAskAi(h.code, h.info) } },
                )
            }
        }
        item(key = "bottom") { Spacer(Modifier.padding(8.dp)) }
    }
    if (confirmClear) ClearDialog(state, onDismiss = { confirmClear = false }) { confirmed -> confirmClear = false; onClear(confirmed) }
}

/** B4: what a clear erases, and the «car stands» confirmation when there is no fresh speed reading. */
@Composable
private fun ClearDialog(state: DtcUiState, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var stationary by remember { mutableStateOf(false) }
    val needsConfirm = state.speed == SpeedGate.UNKNOWN
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dtc_clear_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.dtc_clear_text))
                listOf(R.string.dtc_clear_loss_freeze, R.string.dtc_clear_loss_readiness, R.string.dtc_clear_loss_counters).forEach {
                    Text(stringResource(CoreUiR.string.common_bullet, stringResource(it)), style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.dtc_clear_return), style = MaterialTheme.typography.bodyMedium)
                if (needsConfirm) {
                    Text(stringResource(R.string.dtc_clear_no_speed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = stationary, role = Role.Checkbox, onValueChange = { stationary = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = stationary, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.dtc_clear_checkbox))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(stationary) }, enabled = !needsConfirm || stationary) { Text(stringResource(R.string.dtc_clear)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiR.string.common_cancel)) } },
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

/** B3: the conditions the ECU froze when the code was stored. */
@Composable
private fun FreezeFrameBlock(code: String, frame: FreezeFrame) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        SectionCard {
            Text(stringResource(R.string.dtc_freeze_title, frame.dtcCode ?: code), style = MaterialTheme.typography.titleSmall)
            frame.readings.forEach { r ->
                val value = if (r.sensorId == "01.03") ObdText.fuelSystemStatus(r.value).asString() else DiagText.number(r.value) + (if (r.unit.isEmpty()) "" else " ${r.unit}")
                Text("${ObdText.sensorName(r.sensorId).asString()}: $value", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** B1: Check Engine, a one-line verdict and one compact row per monitor. */
@Composable
private fun ReadinessSection(r: Readiness, thisCycle: List<MonitorState>?) {
    SectionCard {
        Text(stringResource(R.string.dtc_readiness_title), style = MaterialTheme.typography.titleMedium)
        Text(
            if (r.milOn) stringResource(R.string.dtc_readiness_mil_on, r.dtcCount) else stringResource(R.string.dtc_readiness_mil_off),
            color = if (r.milOn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        val verdict = when {
            r.supported.isEmpty() -> stringResource(R.string.dtc_readiness_none)
            r.inspectionReady -> stringResource(R.string.dtc_readiness_ready)
            r.allComplete -> stringResource(R.string.dtc_readiness_mil_blocks)
            else -> pluralStringResource(R.plurals.dtc_readiness_not_ready, r.incompleteCount, r.incompleteCount)
        }
        Text(verdict, style = MaterialTheme.typography.bodyLarge)
        val cycle = thisCycle?.associate { it.monitor to it.status }
        r.monitors.sortedBy { it.status == MonitorStatus.NOT_SUPPORTED }.forEach { m -> MonitorRow(m, cycle?.get(m.monitor)) }
        Text(stringResource(R.string.dtc_readiness_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonitorRow(m: MonitorState, thisCycle: MonitorStatus?) {
    val (icon, tint) = when (m.status) {
        MonitorStatus.COMPLETE -> R.drawable.ic_check to MaterialTheme.colorScheme.primary
        MonitorStatus.INCOMPLETE -> R.drawable.ic_pending to MaterialTheme.colorScheme.tertiary
        MonitorStatus.NOT_SUPPORTED -> R.drawable.ic_dash to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(DiagText.monitor(m.monitor)), style = MaterialTheme.typography.bodyMedium)
            val status = stringResource(DiagText.status(m.status))
            val text = thisCycle?.takeIf { m.status != MonitorStatus.NOT_SUPPORTED && it != MonitorStatus.NOT_SUPPORTED }
                ?.let { stringResource(R.string.dtc_monitor_status_this_trip, status, stringResource(DiagText.status(it))) } ?: status
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (m.status == MonitorStatus.NOT_SUPPORTED) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
        }
    }
}

/** B2: the used-car check with its numbers and what they mean for a buyer. */
@Composable
private fun UsedCarCard(check: RecentClearCheck) {
    SectionCard {
        Text(stringResource(R.string.dtc_usedcar_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(DiagText.verdict(check.verdict)), fontWeight = FontWeight.Medium)
        val c = check.counters
        c.warmUpsSinceClear?.let { Text(stringResource(R.string.dtc_usedcar_warmups, it)) }
        c.kmSinceClear?.let { Text(stringResource(R.string.dtc_usedcar_km, it)) }
        c.minutesSinceClear?.let { Text(stringResource(R.string.dtc_usedcar_minutes, it / 60, it % 60)) }
        c.minutesWithMil?.let { Text(stringResource(R.string.dtc_usedcar_mil_minutes, it / 60, it % 60)) }
        c.kmWithMil?.let { Text(stringResource(R.string.dtc_usedcar_mil_km, it)) }
        check.incompleteMonitors?.let { Text(stringResource(R.string.dtc_usedcar_incomplete, it)) }
        DiagText.verdictExplain(check.verdict)?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** B4: before / after a clear. */
@Composable
private fun ComparisonCard(c: ClearComparison) {
    SectionCard {
        Text(stringResource(R.string.dtc_compare_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.dtc_compare_codes, c.codesBefore.size, c.codesAfter.size))
        if (c.milBefore != null && c.milAfter != null) {
            Text(stringResource(R.string.dtc_compare_mil, stringResource(onOff(c.milBefore)), stringResource(onOff(c.milAfter))))
        }
        if (c.incompleteBefore != null && c.incompleteAfter != null) {
            Text(stringResource(R.string.dtc_compare_incomplete, c.incompleteBefore, c.incompleteAfter))
        }
        if (c.returned.isNotEmpty()) {
            Text(stringResource(R.string.dtc_compare_returned, c.returned.joinToString(", ")), color = MaterialTheme.colorScheme.error)
        } else {
            Text(stringResource(R.string.dtc_compare_clean))
        }
    }
}

private fun onOff(on: Boolean) = if (on) R.string.dtc_mil_on else R.string.dtc_mil_off

/**
 * The description with «Ask AI» under it. A trailing button squeezed the code headline to letter-by-letter
 * wrapping with a large font (C13 screenshot), so the action sits below the text.
 */
@Composable
private fun InfoWithAi(info: DtcInfo, aiEnabled: Boolean, onAskAi: () -> Unit) {
    Column {
        DtcInfoText(info)
        if (aiEnabled) TextButton(onClick = onAskAi) { Text(stringResource(R.string.dtc_ask_ai)) }
    }
}

/** Name, plus for OBDex two common causes and symptoms; the source is labeled (whose decoding it is). */
@Composable
internal fun DtcInfoText(info: DtcInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val unknown = info.unknownTextRes
        if (unknown != null) {
            Text(stringResource(unknown), fontStyle = FontStyle.Italic)
        } else {
            Text(info.title.orEmpty())
        }
        if (info.causes.isNotEmpty()) {
            Text(stringResource(R.string.dtc_causes, info.causes.take(2).joinToString("; ")), style = MaterialTheme.typography.bodySmall)
        }
        if (info.symptoms.isNotEmpty()) {
            Text(stringResource(R.string.dtc_symptoms, info.symptoms.take(2).joinToString("; ")), style = MaterialTheme.typography.bodySmall)
        }
        sourceLabel(info)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun sourceLabel(info: DtcInfo): String? = when (info.source) {
    DtcSource.OBDEX -> stringResource(R.string.dtc_source_obdex)
    DtcSource.BUILTIN -> stringResource(R.string.dtc_source_builtin)
    DtcSource.OEM -> stringResource(R.string.dtc_source_oem, info.manufacturer.orEmpty())
    DtcSource.OEM_UNKNOWN, DtcSource.UNKNOWN -> null
}

private val DtcKind.labelRes: Int
    get() = when (this) {
        DtcKind.STORED -> R.string.dtc_kind_stored
        DtcKind.PENDING -> R.string.dtc_kind_pending
        DtcKind.PERMANENT -> R.string.dtc_kind_permanent
    }
