package io.github.iampopovich.eculense.feature.health

import io.github.iampopovich.eculense.core.analytics.HealthStatus
import io.github.iampopovich.eculense.core.data.HealthCheckRecord
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.obd.Monitor
import io.github.iampopovich.eculense.core.obd.MonitorStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A finding line of the report, already rendered in the app language. */
internal data class ReportFinding(val severity: String, val title: String, val message: String)

/**
 * B6 report: one self-contained HTML page (inline CSS, light/dark via prefers-color-scheme, no scripts, no external
 * resources). Car title only (name, make, model, year): never the VIN or any location. Every text comes from [str].
 */
internal object HealthReportHtml {
    fun build(
        str: Str,
        record: HealthCheckRecord,
        carTitle: String?,
        findings: List<ReportFinding>,
        monitorName: (Monitor) -> String,
        sensorName: (String) -> String,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val date = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", locale).format(Instant.ofEpochMilli(record.takenAtMs).atZone(zone))
        val verdict = record.result.verdict
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html lang=\"").append(esc(locale.language)).append("\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<title>").append(esc(str.s(R.string.health_report_title))).append("</title><style>").append(CSS).append("</style></head><body>")
        sb.append("<h1>").append(esc(str.s(R.string.health_report_title))).append("</h1>")
        sb.append("<table class=\"meta\">")
        carTitle?.let { row(sb, str.s(R.string.health_car), it) }
        row(sb, str.s(R.string.health_date), date)
        record.odometerKm?.let { row(sb, str.s(R.string.health_mileage), str.s(R.string.health_mileage_value, it.toInt())) }
        sb.append("</table>")
        sb.append("<p class=\"verdict ").append(cls(verdict)).append("\">").append(esc(str.s(R.string.health_verdict))).append(": ")
            .append(esc(str.s(verdict.verdictRes()))).append("</p>")

        sb.append("<table class=\"items\">")
        record.result.items.forEach { item ->
            sb.append("<tr><td><span class=\"dot ").append(cls(item.status)).append("\"></span>").append(esc(str.s(item.id.titleRes()))).append("</td>")
            sb.append("<td class=\"st ").append(cls(item.status)).append("\">").append(esc(str.s(item.status.labelRes()))).append("</td>")
            val detail = listOfNotNull(item.valueText(str), item.note.res()?.let { str.s(it) }).joinToString(" · ")
            sb.append("<td>").append(esc(detail)).append("</td></tr>")
        }
        sb.append("</table>")

        sb.append("<h2>").append(esc(str.s(R.string.health_codes))).append("</h2>")
        if (record.dtcs.isEmpty()) {
            sb.append("<p>").append(esc(str.s(R.string.health_no_codes))).append("</p>")
        } else {
            sb.append("<ul>")
            record.dtcs.forEach { d ->
                val kind = when (d.kind) {
                    DtcKind.STORED -> R.string.health_item_stored
                    DtcKind.PENDING -> R.string.health_item_pending
                    DtcKind.PERMANENT -> R.string.health_item_permanent
                }
                sb.append("<li><b>").append(esc(d.code)).append("</b> · ").append(esc(str.s(kind))).append("</li>")
            }
            sb.append("</ul>")
        }
        record.freezeFrame?.takeIf { it.readings.isNotEmpty() }?.let { ff ->
            sb.append("<h2>").append(esc(str.s(R.string.health_freeze_frame))).append(ff.dtcCode?.let { " · " + esc(it) } ?: "").append("</h2><table>")
            ff.readings.forEach { r -> row(sb, sensorName(r.sensorId), String.format(locale, "%.1f %s", r.value, r.unit)) }
            sb.append("</table>")
        }
        record.readiness?.supported?.takeIf { it.isNotEmpty() }?.let { monitors ->
            sb.append("<h2>").append(esc(str.s(R.string.health_readiness))).append("</h2><table>")
            monitors.forEach { m ->
                val done = m.status == MonitorStatus.COMPLETE
                row(sb, monitorName(m.monitor), str.s(if (done) R.string.health_monitor_complete else R.string.health_monitor_incomplete), if (done) "ok" else "warn")
            }
            sb.append("</table>")
        }
        if (findings.isNotEmpty()) {
            sb.append("<h2>").append(esc(str.s(R.string.health_findings))).append("</h2><ul>")
            findings.forEach { f -> sb.append("<li><b>").append(esc(f.severity)).append(":</b> ").append(esc(f.title)).append(" — ").append(esc(f.message)).append("</li>") }
            sb.append("</ul>")
        }
        sb.append("<footer>").append(esc(str.s(R.string.health_report_footer))).append("</footer></body></html>")
        return sb.toString()
    }

    private fun row(sb: StringBuilder, k: String, v: String, cls: String? = null) {
        sb.append("<tr><th>").append(esc(k)).append("</th><td").append(cls?.let { " class=\"st $it\"" } ?: "").append(">").append(esc(v)).append("</td></tr>")
    }

    private fun cls(s: HealthStatus) = when (s) {
        HealthStatus.OK -> "ok"
        HealthStatus.ATTENTION -> "warn"
        HealthStatus.FAULT -> "bad"
        HealthStatus.NOT_SUPPORTED, HealthStatus.NOT_JUDGED -> "na"
    }

    fun esc(s: String): String = buildString(s.length) {
        s.forEach { c ->
            when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    /** Light by default, dark when the viewer prefers it; print always uses the light palette. */
    private const val CSS =
        ":root{--bg:#ffffff;--fg:#1b1b1f;--muted:#5d5e66;--line:#d6d6dc;--ok:#1b6e2d;--warn:#8a5a00;--bad:#b3261e;--na:#5d5e66}" +
            "@media (prefers-color-scheme: dark){:root{--bg:#121316;--fg:#e4e2e6;--muted:#b0b0b8;--line:#3a3b40;--ok:#7dd38f;--warn:#f2c46b;--bad:#ffb4ab;--na:#a9a9b2}}" +
            "@media print{:root{--bg:#ffffff;--fg:#000000;--muted:#444444;--line:#bbbbbb;--ok:#1b6e2d;--warn:#8a5a00;--bad:#b3261e;--na:#444444}}" +
            "body{background:var(--bg);color:var(--fg);font-family:sans-serif;margin:16px;line-height:1.4}" +
            "h1{font-size:1.5em;margin:0 0 8px}h2{font-size:1.15em;margin:20px 0 6px}" +
            "table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}" +
            "th{color:var(--muted);font-weight:normal;width:40%}.meta th{width:30%}" +
            ".verdict{font-size:1.2em;font-weight:bold;margin:14px 0}" +
            ".dot{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:8px;background:currentColor}" +
            ".ok{color:var(--ok)}.warn{color:var(--warn)}.bad{color:var(--bad)}.na{color:var(--na)}.st{font-weight:bold;white-space:nowrap}" +
            "footer{margin-top:24px;color:var(--muted);font-size:.85em}"
}
