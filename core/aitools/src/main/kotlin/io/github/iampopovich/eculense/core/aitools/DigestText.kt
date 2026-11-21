package io.github.iampopovich.eculense.core.aitools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Car data digest for the on-phone model (it doesn't call tools): the same tool responses, but as plain
 * text: short English lines «Section: label: value», lists as «- …» lines. Only the model reads it; the answer
 * language is set by the system prompt. Given JSON, the small model retold it to the user verbatim (raw `carProfile`
 * leaked into the chat).
 */
internal object DigestText {
    /** Order and headings of digest sections. */
    val SECTIONS: Map<String, String> = linkedMapOf(
        "carProfile" to "Car",
        "trends" to "Trends",
        "tripStats" to "Trips",
        "dtcHistory" to "Trouble codes",
        "moduleDtcs" to "Trouble codes of other modules",
        "findings" to "Analysis findings",
        "maintenanceDue" to "Maintenance",
        "listSessions" to "Latest recording",
        "fuelLog" to "Fuel log",
        "expenses" to "Expenses",
        "readinessHistory" to "OBD readiness",
        "healthChecks" to "Quick health checks",
    )

    /** Leading field of a list item: written first, without a label. */
    private val LEAD = listOf("code", "work", "title", "month")

    /** Internal fields useless to a model without tools. */
    private val SKIP = setOf("id", "rule")

    /** Labels that differ from the JSON key with «_» replaced by spaces. */
    private val LABELS = mapOf(
        "engine_code" to "engine code", "engine_l" to "engine displacement, l", "curb_weight_kg" to "curb weight, kg", "tank_l" to "tank, l",
        "odometer_km" to "odometer, km", "odometer_date" to "odometer entered on", "service_interval_km" to "service intervals, km",
        "missing" to "not specified",
        "kind" to "type", "events" to "times", "first" to "first seen", "last" to "last seen", "source" to "origin",
        "severity" to "level", "start" to "started",
        "title" to "description", "total_events" to "total occurrences", "truncated" to "list shortened",
        "computed_at" to "computed at", "evidence" to "data", "no_data" to "without data",
        "ok" to "ok", "odometer_estimated" to "odometer estimated from trips",
        "odometer_entered_km" to "entered odometer, km", "odometer_entered_date" to "odometer entered on", "due_now" to "works due now",
        "interval_km" to "interval, km", "interval_months" to "interval, months", "last_done_date" to "last done",
        "last_done_km" to "last done at, km", "due_km" to "due at, km", "km_left" to "km left", "due_date" to "due on",
        "min" to "minutes", "readings" to "readings",
        "fuel_l" to "fuel, l", "l_per_100km" to "consumption, l/100 km",
        "driving_h" to "driving, h", "standing_h" to "standing, h", "fuel_cost" to "fuel spending",
        "last_trip" to "last trip", "recent_months" to "by month",
        "latest_date" to "latest reading", "stored_emission_dtcs" to "stored emission codes", "obd_inspection_ready" to "OBD inspection ready",
        "hours_since_clear" to "hours since clear", "km_with_mil_on" to "km with the lamp on", "hours_with_mil_on" to "hours with the lamp on",
    )

    private val VALUES = mapOf(
        "generic" to "generic OBD-II code", "oem" to "manufacturer-specific code", "oem_unknown" to "manufacturer-specific code, meaning unknown",
        "live" to "ECULense recording", "carscanner" to "Car Scanner import",
        "lpg" to "LPG (propane)", "cng" to "CNG (methane)", "all" to "all-wheel",
    )

    private val NO_DATA = mapOf(
        "no car profile" to "car profile is not filled in",
        "no DTC events recorded" to "no trouble codes in the history",
        "no recordings to analyse" to "no recordings to analyse",
        "maintenance module is not installed" to "maintenance module is not installed",
        "no sessions in this range" to "no recordings",
    )

    fun section(name: String, json: JsonObject): String {
        val title = SECTIONS[name] ?: name
        val noData = json["no_data"]
        if (json.size == 1 && noData is JsonPrimitive) return "$title: ${noDataText(noData.content)}"
        val head = mutableListOf<String>()
        val items = mutableListOf<String>()
        json.forEach { (key, value) ->
            if (value is JsonArray && value.isNotEmpty() && value.all { it is JsonObject }) {
                value.forEach { items += "- " + item(it as JsonObject) }
            } else {
                field(key, value)?.let { head += it }
            }
        }
        return buildString {
            append(title).append(':')
            if (head.isNotEmpty()) append(' ').append(head.joinToString(", "))
            items.forEach { append('\n').append(it) }
        }
    }

    private fun item(obj: JsonObject): String {
        val lead = LEAD.firstOrNull { obj[it] is JsonPrimitive }
        val rest = obj.filterKeys { it != lead && it !in SKIP }.mapNotNull { (k, v) -> field(k, v) }
        val leadText = lead?.let { value(obj.getValue(it)) }
        return listOfNotNull(leadText, rest.joinToString(", ").takeIf { it.isNotEmpty() }).joinToString(" — ")
    }

    private fun label(key: String) = LABELS[key] ?: key.replace('_', ' ')

    private fun field(key: String, value: JsonElement): String? {
        val label = label(key)
        if (key == "period" && value is JsonPrimitive) return "$label: ${periodText(value.content)}"
        return when (value) {
            is JsonNull -> null
            is JsonPrimitive -> when (value.booleanOrNull.takeIf { !value.isString }) {
                true -> label
                false -> null
                null -> "$label: ${value(value)}"
            }
            is JsonArray -> value.filterIsInstance<JsonPrimitive>().takeIf { it.isNotEmpty() }
                ?.joinToString("; ") { value(it) }?.let { "$label: $it" }
            is JsonObject -> value.entries.joinToString("; ") { (k, v) -> "${label(k)}: ${(v as? JsonPrimitive)?.let(::value) ?: clean(v.toString())}" }
                .takeIf { it.isNotEmpty() }?.let { "$label — $it" }
        }
    }

    private fun value(p: JsonElement): String {
        val s = (p as? JsonPrimitive)?.content ?: p.toString()
        // Enum-like tokens (before_clear, health_check) read as words.
        val mapped = VALUES[s] ?: LABELS[s] ?: if (ENUM_TOKEN.matches(s)) s.replace('_', ' ') else s
        return clean(mapped)
    }

    private val ENUM_TOKEN = Regex("[a-z]+(_[a-z0-9]+)+")

    private fun noDataText(reason: String): String = NO_DATA[reason]
        ?: when {
            reason.startsWith("maintenance list is empty") -> "the maintenance list is empty (Pit stop → Maintenance)"
            reason.startsWith("no trips, recordings") -> "no trips, recordings or trouble codes in the period"
            reason.startsWith("no trips") -> "no trips (they appear after a recording or a Car Scanner import)"
            else -> "no data"
        }

    private fun periodText(p: String): String = when {
        p == "all" -> "all time"
        p.endsWith(" days") -> "last $p"
        else -> clean(p)
    }

    private val METRICS = mapOf(
        "l_per_100km" to "consumption, l/100 km", "avg_trip_km" to "average trip, km", "idle_share" to "share of time standing, %",
        "coolant_median" to "coolant median, °C", "coolant_max" to "coolant maximum, °C", "voltage_median" to "voltage while running, median V",
        "voltage_min_running" to "voltage while running, minimum V", "ltft1_median" to "LTFT bank 1, %", "ltft2_median" to "LTFT bank 2, %",
        "idle_rpm_median" to "warm idle rpm",
    )

    /**
     * Trends: one line per month/week from oldest to newest, then «Change» and «Too little data». Only numbers from the
     * [TrendsTool] response.
     */
    fun trends(json: JsonObject): String {
        val title = SECTIONS.getValue("trends")
        val noData = json["no_data"]
        if (json.size == 1 && noData is JsonPrimitive) return "$title: ${noDataText(noData.content)}"
        fun JsonObject.n(key: String) = (this[key] as? JsonPrimitive)?.content
        val bucket = if (json.n("bucket") == "week") "by week" else "by month"
        val totals = json["totals"] as? JsonObject
        return buildString {
            append(title).append(" for the last ").append(json.n("days")).append(" days (").append(bucket).append(")")
            totals?.let { t ->
                append(", total: trips ").append(t.n("trips")).append(", ").append(t.n("km")).append(" km")
                t.n("l_per_100km")?.let { append(", consumption ").append(it).append(" l/100 km") }
            }
            append(':')
            (json["buckets"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().reversed().forEach { b ->
                append("\n- ").append(b.n("label")).append(": ")
                if (b["empty"] != null) {
                    append("no data")
                    return@forEach
                }
                val parts = buildList {
                    b.n("trips")?.let { trips ->
                        add(buildString {
                            append("trips ").append(trips).append(", ").append(b.n("km")).append(" km")
                            b.n("l_per_100km")?.let { append(", consumption ").append(it).append(" l/100 km") }
                            b.n("idle_pct")?.let { append(", standing ").append(it).append(" % of the time") }
                            b.n("avg_trip_km")?.let { append(", average trip ").append(it).append(" km") }
                        })
                    }
                    b.n("sessions")?.let { add("recordings $it") }
                    b.n("coolant_median")?.let { add("coolant median $it °C, maximum ${b.n("coolant_max")} °C (readings ${b.n("coolant_n")})") }
                    b.n("voltage_median")?.let { add("voltage while running median $it V, minimum ${b.n("voltage_min_running")} V") }
                    val trims = listOf("ltft1_median" to "LTFT bank 1", "ltft2_median" to "LTFT bank 2", "stft1_median" to "STFT bank 1", "stft2_median" to "STFT bank 2")
                        .mapNotNull { (k, name) -> b.n(k)?.let { "$name $it %" } }
                    if (trims.isNotEmpty()) add("warm fuel trims: " + trims.joinToString(", "))
                    b.n("idle_rpm_median")?.let { add("warm idle rpm $it") }
                    b.n("dtc_events")?.let { ev ->
                        val codes = (b["new_codes"] as? JsonArray)?.joinToString(", ") { (it as JsonPrimitive).content }
                        val more = b.n("new_codes_more")?.let { " and $it more" }.orEmpty()
                        add("trouble code occurrences $ev" + (codes?.let { ", new codes: $it$more" } ?: ""))
                    }
                }
                append(parts.joinToString("; "))
            }
            val changes = (json["change"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            if (changes.isNotEmpty()) {
                append("\nChange (first → last period with enough data): ")
                append(
                    changes.joinToString("; ") { c ->
                        val delta = c.n("delta").orEmpty()
                        val signed = if (delta.startsWith("-")) delta else "+$delta"
                        "${METRICS[c.n("metric")] ?: clean(c.n("metric").orEmpty())}: ${c.n("was")} → ${c.n("now")} ($signed, ${c.n("from")} → ${c.n("to")})"
                    },
                )
            }
            val few = (json["few_data"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.let { m -> METRICS[m] } }
            if (few.isNotEmpty()) append("\nToo little data to compare: ").append(few.joinToString("; "))
            if (json["truncated"] != null) append("\nOlder periods did not fit.")
        }
    }

    /** Text from user data/databases must not look like JSON either. */
    private fun clean(s: String) = s.replace('"', '\'').replace('{', '(').replace('}', ')').replace('[', '(').replace(']', ')')
}
