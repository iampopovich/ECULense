package io.github.iampopovich.eculense.feature.datapacks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.iampopovich.eculense.core.datapacks.PackCatalog
import io.github.iampopovich.eculense.core.ui.UiText

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
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    AboutScreen(version)
}

@Composable
internal fun AboutScreen(version: String?, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ECULense", style = MaterialTheme.typography.headlineSmall)
                version?.let { Text(stringResource(R.string.datapacks_version, it), style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.datapacks_about_tagline), style = MaterialTheme.typography.bodyMedium)
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
}
