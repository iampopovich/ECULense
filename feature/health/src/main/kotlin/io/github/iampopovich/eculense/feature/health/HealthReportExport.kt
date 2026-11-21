package io.github.iampopovich.eculense.feature.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import io.github.iampopovich.eculense.core.data.ExportFiles
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.analyticstext.AnalyticsText
import io.github.iampopovich.eculense.core.data.AnalyticsRepository
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.HealthCheckRecord
import io.github.iampopovich.eculense.core.obdtext.ObdText
import io.github.iampopovich.eculense.core.ui.CarNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Own subclass: one FileProvider class per authority, so features never clash. */
class HealthReportFileProvider : FileProvider()

/** Builds the HTML of a check in the app language and shares or prints it. */
internal class HealthReportExport(
    private val cars: CarRepository,
    private val analytics: AnalyticsRepository?,
) {
    suspend fun html(context: Context, record: HealthCheckRecord): String {
        val res = context.resources
        val str: Str = { id, args -> res.getString(id, *args.toTypedArray()) }
        val car = record.carId?.let { id -> cars.cars().firstOrNull { it.id == id } }
        // Name, make, model, year only: the VIN never goes into a report.
        val title = car?.let { c ->
            listOfNotNull(CarNames.display(context, c.name), listOfNotNull(c.brand, c.model, c.year?.toString()).joinToString(" ").ifBlank { null })
                .joinToString(" · ")
        }
        val findings = record.carId?.let { id -> runCatching { analytics?.findings(id) }.getOrNull() }.orEmpty()
            .map { it.finding }
            .filter { it.severity == Severity.WARNING || it.severity == Severity.CRITICAL }
            .sortedByDescending { it.severity.rank }
            .take(MAX_FINDINGS)
            .map { ReportFinding(AnalyticsText.severity(context, it.severity), AnalyticsText.title(context, it), AnalyticsText.message(context, it)) }
        val locale = res.configuration.locales[0]
        return HealthReportHtml.build(
            str, record, title, findings,
            monitorName = { res.getString(ObdText.monitorRes(it)) },
            sensorName = { ObdText.sensorName(it).asString(context) },
            locale = locale,
        )
    }

    /** Writes cache/reports/health-<time>.html (previous reports deleted) and returns its content:// Uri. */
    suspend fun file(context: Context, record: HealthCheckRecord): Uri = withContext(Dispatchers.IO) {
        val file = ExportFiles.fresh(File(context.cacheDir, "reports"), "health-${record.takenAtMs}.html")
        file.writeText(html(context, record))
        FileProvider.getUriForFile(context, "${context.packageName}.health.files", file)
    }

    suspend fun share(context: Context, record: HealthCheckRecord) {
        val uri = file(context, record)
        val send = Intent(Intent.ACTION_SEND).setType("text/html").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.health_share_chooser)))
    }

    /**
     * Android print dialog («Save as PDF» is one of its printers) through a WebView print adapter. The WebView must
     * live until the page finished loading, so the caller keeps it in [hold].
     */
    suspend fun print(context: Context, record: HealthCheckRecord, hold: (WebView?) -> Unit) {
        val html = html(context, record)
        val name = context.getString(R.string.health_report_title)
        val web = WebView(context)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val pm = context.getSystemService(PrintManager::class.java)
                pm?.print(name, view.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
                hold(null)
            }
        }
        hold(web)
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private companion object {
        const val MAX_FINDINGS = 5
    }
}
