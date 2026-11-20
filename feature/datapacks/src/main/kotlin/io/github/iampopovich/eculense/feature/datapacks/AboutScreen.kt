package io.github.iampopovich.eculense.feature.datapacks

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.datapacks.PackCatalog
import io.github.iampopovich.eculense.core.model.AppBuild
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.ObdProtocols
import io.github.iampopovich.eculense.core.obd.endpointOrNull
import io.github.iampopovich.eculense.core.ui.UiText
import kotlinx.coroutines.flow.flowOf
import org.koin.compose.getKoin

/** Data source for «About»: what, whose, under which license, where to get it. */
internal data class Attribution(val title: UiText, val license: UiText, val url: String, val note: UiText)

/** All external app data: on-demand packs + bundled code database + OBDb. */
internal val ATTRIBUTIONS: List<Attribution> =
    PackCatalog.all.map {
        Attribution(
            PackTexts.title(it), PackTexts.license(it), it.sourceUrl,
            UiText.Res(R.string.datapacks_pinned_version, listOf(it.attribution.trimEnd().removeSuffix("."), it.version)),
        )
    } + listOf(
        Attribution(
            UiText.Res(R.string.datapacks_obdb_title), UiText.Raw("CC BY-SA 4.0"), "https://github.com/OBDb",
            UiText.Res(R.string.datapacks_obdb_note),
        ),
        Attribution(
            UiText.Res(R.string.datapacks_builtin_title), UiText.Raw("MIT"), "https://github.com/lennykean/OBDII.DTC",
            UiText.Raw("Generic DTC titles converted from lennykean/OBDII.DTC, MIT License (assets/dtc_codes.csv)."),
        ),
    )

@Composable
internal fun AboutRoute() {
    val context = LocalContext.current
    val koin = getKoin()
    val build = remember { koin.getOrNull<AppBuild>() }
    val version = build?.versionName ?: runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    val cars = remember { koin.getOrNull<CarRepository>() }
    val controller = remember { koin.getOrNull<ConnectionController>() }
    val car by remember { cars?.observeActiveCar() ?: flowOf(null) }.collectAsState(initial = null)
    val state = controller?.state?.collectAsState()?.value ?: ConnectionState.Disconnected
    AboutScreen(version) {
        ReportFacts(
            appVersion = version ?: "?", flavor = build?.flavor,
            androidRelease = Build.VERSION.RELEASE ?: "?", sdkInt = Build.VERSION.SDK_INT,
            deviceModel = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" "),
            carMake = car?.brand, carModel = car?.model, carYear = car?.year,
            adapterKind = state.endpointKind(),
            adapterVersion = (state as? ConnectionState.Connected)?.info?.version,
            protocol = (state as? ConnectionState.Connected)?.info?.protocolNumber?.let { p ->
                "$p (" + when {
                    ObdProtocols.isCan(p) -> "CAN"
                    ObdProtocols.isKLine(p) -> "K-Line"
                    else -> "other"
                } + ")"
            },
            connection = state.reportText(),
        )
    }
}

private fun ConnectionState.endpointKind(): String? = endpointOrNull?.kind

private fun ConnectionState.reportText(): String = when (this) {
    is ConnectionState.Connected -> "connected"
    is ConnectionState.Failed -> "failed: ${error::class.simpleName}"
    ConnectionState.Disconnected -> "disconnected"
    else -> this::class.simpleName?.lowercase() ?: "unknown"
}

@Composable
internal fun AboutScreen(version: String?, modifier: Modifier = Modifier, facts: (() -> ReportFacts)? = null) {
    val uri = LocalUriHandler.current
    var preview by remember { mutableStateOf<IssueDraft?>(null) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ECULense", style = MaterialTheme.typography.headlineSmall)
                version?.let { Text(stringResource(R.string.datapacks_version, it), style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.datapacks_about_tagline), style = MaterialTheme.typography.bodyMedium)
            }
        }
        // C11: both actions full width, one per row (large font), each shows the exact text before the browser opens.
        if (facts != null) {
            item(key = "feedback") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.datapacks_feedback), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = { preview = ProblemReport.draft(ReportKind.PROBLEM, facts()) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("report-problem"),
                    ) { Text(stringResource(R.string.datapacks_report_problem)) }
                    OutlinedButton(
                        onClick = { preview = ProblemReport.draft(ReportKind.IDEA, facts()) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.datapacks_report_idea)) }
                }
            }
        }
        item { Text(stringResource(R.string.datapacks_sources), style = MaterialTheme.typography.titleMedium) }
        items(ATTRIBUTIONS, key = { it.url }) { a ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(a.title.asString(), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.datapacks_license, a.license.asString()), style = MaterialTheme.typography.bodyMedium)
                Text(a.note.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { runCatching { uri.openUri(a.url) } }) { Text(a.url.removePrefix("https://")) }
                HorizontalDivider()
            }
        }
        item { Text(stringResource(R.string.datapacks_vehicle_db_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp)) }
    }
    preview?.let { draft ->
        ReportPreviewDialog(
            draft,
            onOpen = { preview = null; runCatching { uri.openUri(ProblemReport.url(draft)) } },
            onDismiss = { preview = null },
        )
    }
}

/** C11: exactly what goes into the issue form; nothing leaves the phone until the user submits it on GitHub. */
@Composable
internal fun ReportPreviewDialog(draft: IssueDraft, onOpen: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.datapacks_report_preview_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.datapacks_report_preview_intro), style = MaterialTheme.typography.bodyMedium)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
                    Text(
                        draft.title + "\n\n" + draft.body,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("report-body"),
                    )
                }
                Text(stringResource(R.string.datapacks_report_log_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onOpen) { Text(stringResource(R.string.datapacks_report_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.datapacks_report_cancel)) } },
    )
}
