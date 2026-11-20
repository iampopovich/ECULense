package io.github.iampopovich.eculense.feature.datapacks

import java.net.URLEncoder

/** C11: what kind of GitHub issue the user opens from «About». */
internal enum class ReportKind { PROBLEM, IDEA }

/**
 * Facts attached to a report. Only coarse, non-identifying data: no VIN, no car name, no location, no adapter
 * address or Bluetooth name, no traffic log.
 */
internal data class ReportFacts(
    val appVersion: String,
    val flavor: String?,
    val androidRelease: String,
    val sdkInt: Int,
    val deviceModel: String,
    val carMake: String?,
    val carModel: String?,
    val carYear: Int?,
    /** Adapter type only: «bt», «wifi», «usb», «demo»…; never its address or name. */
    val adapterKind: String?,
    val adapterVersion: String?,
    /** ATDPN protocol number and bus. */
    val protocol: String?,
    val connection: String,
)

internal data class IssueDraft(val title: String, val body: String, val labels: String)

/** Pure builder of the prefilled GitHub issue; the screen shows [IssueDraft.body] verbatim before opening the browser. */
internal object ProblemReport {
    const val NEW_ISSUE_URL = "https://github.com/iampopovich/ECULense/issues/new"

    fun draft(kind: ReportKind, f: ReportFacts): IssueDraft {
        val intro = when (kind) {
            ReportKind.PROBLEM -> "### What happened\n\n\n### Steps to reproduce\n1. \n\n### Expected\n\n"
            ReportKind.IDEA -> "### Idea\n\n\n### Why it helps\n\n"
        }
        val car = listOfNotNull(f.carMake, f.carModel, f.carYear?.toString()).joinToString(" ").ifBlank { "not set" }
        val facts = listOf(
            "App: ${f.appVersion}" + (f.flavor?.let { " ($it)" } ?: ""),
            "Android: ${f.androidRelease} (API ${f.sdkInt})",
            "Device: ${f.deviceModel}",
            "Car: $car",
            "Adapter: " + (listOfNotNull(f.adapterVersion, f.adapterKind?.let { "($it)" }).joinToString(" ").ifBlank { "unknown" }),
            "Protocol: ${f.protocol ?: "unknown"}",
            "Connection: ${f.connection}",
        )
        val body = intro + "---\n" + facts.joinToString("\n") { "- $it" } + "\n"
        return when (kind) {
            ReportKind.PROBLEM -> IssueDraft("Problem: ", body, "bug")
            ReportKind.IDEA -> IssueDraft("Idea: ", body, "enhancement")
        }
    }

    fun url(d: IssueDraft): String =
        "$NEW_ISSUE_URL?title=${enc(d.title)}&body=${enc(d.body)}&labels=${enc(d.labels)}"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
