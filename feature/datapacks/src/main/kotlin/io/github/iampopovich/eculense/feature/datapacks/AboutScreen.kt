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
import androidx.compose.ui.unit.dp
import io.github.iampopovich.eculense.core.datapacks.PackCatalog

/** Источник данных для «О приложении»: что, чьё, под какой лицензией, где взять. */
internal data class Attribution(val title: String, val license: String, val url: String, val note: String)

/** Все внешние данные приложения: пакеты по запросу + встроенная база кодов + OBDb. */
internal val ATTRIBUTIONS: List<Attribution> =
    PackCatalog.all.map { Attribution(it.title, it.license, it.sourceUrl, "${it.attribution} Зафиксированная версия: ${it.version}.") } + listOf(
        Attribution(
            "Расширенные PID (OBDb)", "CC BY-SA 4.0", "https://github.com/OBDb",
            "Signalset data: OBDb (obdb.community), CC BY-SA 4.0. Скачивается по марке и модели без изменений.",
        ),
        Attribution(
            "Встроенная база общих кодов", "MIT", "https://github.com/lennykean/OBDII.DTC",
            "Generic DTC titles converted from lennykean/OBDII.DTC, MIT License (assets/dtc_codes.csv).",
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
                version?.let { Text("Версия $it", style = MaterialTheme.typography.bodyMedium) }
                Text("OBD-II сканер. Данные машины хранятся только на телефоне.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { Text("Источники данных", style = MaterialTheme.typography.titleMedium) }
        items(ATTRIBUTIONS, key = { it.url }) { a ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(a.title, style = MaterialTheme.typography.titleSmall)
                Text("Лицензия: ${a.license}", style = MaterialTheme.typography.bodyMedium)
                Text(a.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { runCatching { uri.openUri(a.url) } }) { Text(a.url.removePrefix("https://")) }
                HorizontalDivider()
            }
        }
        item { Text("Данные справочника моделей собраны проектом gor3a с autoevolution.com; профиль вашей машины от справочника не зависит.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp)) }
    }
}
