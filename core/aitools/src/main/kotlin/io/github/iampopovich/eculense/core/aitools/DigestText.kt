package io.github.iampopovich.eculense.core.aitools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Сводка данных машины для модели на телефоне (она не вызывает инструменты): те же ответы инструментов, но обычным
 * текстом — короткие русские строки «Раздел: метка: значение», списки строками «- …». JSON маленькая модель
 * пересказывала пользователю дословно (в чат попадал сырой `carProfile`).
 */
internal object DigestText {
    /** Порядок и заголовки разделов сводки. */
    val SECTIONS: Map<String, String> = linkedMapOf(
        "carProfile" to "Машина",
        "trends" to "Тренды",
        "tripStats" to "Поездки",
        "dtcHistory" to "Коды ошибок",
        "findings" to "Находки анализа",
        "maintenanceDue" to "Обслуживание (ТО)",
        "listSessions" to "Последняя запись",
    )

    /** Ведущее поле элемента списка — пишется первым, без подписи. */
    private val LEAD = listOf("code", "work", "title", "month")

    /** Служебные поля, бесполезные модели без инструментов. */
    private val SKIP = setOf("id", "rule")

    private val LABELS = mapOf(
        "brand" to "марка", "model" to "модель", "generation" to "поколение", "year" to "год",
        "engine_code" to "код двигателя", "engine_l" to "объём двигателя, л", "cylinders" to "цилиндров", "fuel" to "топливо",
        "gearbox" to "коробка", "drive" to "привод", "curb_weight_kg" to "масса, кг", "tank_l" to "бак, л",
        "odometer_km" to "пробег, км", "odometer_date" to "пробег введён", "service_interval_km" to "интервалы ТО, км",
        "oil" to "масло", "spark_plugs" to "свечи", "air_filter" to "воздушный фильтр", "fuel_filter" to "топливный фильтр",
        "missing" to "не указано",
        "kind" to "тип", "events" to "раз", "first" to "впервые", "last" to "последний раз", "source" to "источник",
        "title" to "описание",
        "causes" to "причины", "symptoms" to "симптомы", "total_events" to "всего событий", "truncated" to "список сокращён",
        "computed_at" to "посчитано", "severity" to "важность", "evidence" to "данные", "no_data" to "без данных",
        "ok" to "в норме",
        "today" to "сегодня", "odometer" to "пробег", "odometer_estimated" to "пробег оценён по поездкам",
        "odometer_entered_km" to "введённый пробег, км", "odometer_entered_date" to "пробег введён", "due_now" to "пора сделать работ",
        "status" to "статус", "interval_km" to "интервал, км", "interval_months" to "интервал, мес", "last_done_date" to "сделано",
        "last_done_km" to "сделано на пробеге, км", "due_km" to "срок, км", "km_left" to "осталось, км", "due_date" to "срок",
        "days_left" to "осталось дней", "note" to "заметка",
        "start" to "начало", "min" to "минут", "readings" to "замеров",
        "period" to "период", "trips" to "поездок", "km" to "км", "fuel_l" to "топливо, л", "l_per_100km" to "расход, л/100 км",
        "driving_h" to "в движении, ч", "standing_h" to "на месте, ч", "fuel_cost" to "траты на топливо", "currency" to "валюта",
        "last_trip" to "последняя поездка", "recent_months" to "по месяцам",
    )

    private val VALUES = mapOf(
        "stored" to "сохранённый", "pending" to "ожидающий", "permanent" to "постоянный",
        "generic" to "стандартный код OBD-II", "oem" to "заводской код марки", "oem_unknown" to "заводской код, расшифровка неизвестна",
        "critical" to "критично", "warning" to "внимание", "info" to "к сведению",
        "overdue" to "просрочено", "soon" to "скоро", "ok" to "в норме", "unknown" to "неизвестно",
        "live" to "запись ECULense", "carscanner" to "импорт Car Scanner",
        "petrol" to "бензин", "diesel" to "дизель", "lpg" to "газ (пропан)", "cng" to "газ (метан)", "hybrid" to "гибрид",
        "electric" to "электро", "manual" to "механика", "automatic" to "автомат", "cvt" to "вариатор", "robot" to "робот",
        "front" to "передний", "rear" to "задний", "all" to "полный", "other" to "другое",
        "not entered; km-based intervals cannot be checked" to "не введён, интервалы по пробегу проверить нельзя",
    )

    private val NO_DATA = mapOf(
        "no car profile" to "профиль машины не заполнен",
        "no DTC events recorded" to "в истории нет ни одного кода",
        "no recordings to analyse" to "записей для анализа нет",
        "maintenance module is not installed" to "модуль ТО не подключён",
        "no sessions in this range" to "записей нет",
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

    private fun field(key: String, value: JsonElement): String? {
        val label = LABELS[key] ?: key.replace('_', ' ')
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
            is JsonObject -> value.entries.joinToString("; ") { (k, v) -> "${LABELS[k] ?: k.replace('_', ' ')}: ${(v as? JsonPrimitive)?.let(::value) ?: clean(v.toString())}" }
                .takeIf { it.isNotEmpty() }?.let { "$label — $it" }
        }
    }

    private fun value(p: JsonElement): String {
        val s = (p as? JsonPrimitive)?.content ?: p.toString()
        return clean(VALUES[s] ?: LABELS[s] ?: s)
    }

    private fun noDataText(reason: String): String = NO_DATA[reason]
        ?: when {
            reason.startsWith("maintenance list is empty") -> "список работ ТО пуст (Пит-стоп → Обслуживание)"
            reason.startsWith("no trips, recordings") -> "за период нет ни поездок, ни записей, ни ошибок"
            reason.startsWith("no trips") -> "поездок нет (они появляются после записи или импорта Car Scanner)"
            else -> "нет данных"
        }

    private fun periodText(p: String): String = when {
        p == "week" -> "неделя"
        p == "month" -> "месяц"
        p == "year" -> "год"
        p == "all" -> "всё время"
        p.endsWith(" days") -> "${p.removeSuffix(" days")} дн."
        else -> clean(p)
    }

    private val METRICS = mapOf(
        "l_per_100km" to "расход, л/100 км", "avg_trip_km" to "средняя поездка, км", "idle_share" to "доля времени на месте, %",
        "coolant_median" to "ОЖ, медиана °C", "coolant_max" to "ОЖ, максимум °C", "voltage_median" to "напряжение на ходу, медиана В",
        "voltage_min_running" to "напряжение на ходу, минимум В", "ltft1_median" to "LTFT банк 1, %", "ltft2_median" to "LTFT банк 2, %",
        "idle_rpm_median" to "обороты прогретого ХХ",
    )

    /**
     * Тренды — строкой на месяц/неделю от старых к новым, затем «Изменение» и «Мало данных». Только числа из ответа
     * инструмента [TrendsTool], подписи по-русски.
     */
    fun trends(json: JsonObject): String {
        val title = SECTIONS.getValue("trends")
        val noData = json["no_data"]
        if (json.size == 1 && noData is JsonPrimitive) return "$title: ${noDataText(noData.content)}"
        fun JsonObject.n(key: String) = (this[key] as? JsonPrimitive)?.content
        val bucket = if (json.n("bucket") == "week") "по неделям" else "по месяцам"
        val totals = json["totals"] as? JsonObject
        return buildString {
            append(title).append(" за ").append(json.n("days")).append(" дн. (").append(bucket).append(")")
            totals?.let { t ->
                append(", всего: поездок ").append(t.n("trips")).append(", ").append(t.n("km")).append(" км")
                t.n("l_per_100km")?.let { append(", расход ").append(it).append(" л/100 км") }
            }
            append(':')
            (json["buckets"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().reversed().forEach { b ->
                append("\n- ").append(b.n("label")).append(": ")
                if (b["empty"] != null) {
                    append("нет данных")
                    return@forEach
                }
                val parts = buildList {
                    b.n("trips")?.let { trips ->
                        add(buildString {
                            append("поездок ").append(trips).append(", ").append(b.n("km")).append(" км")
                            b.n("l_per_100km")?.let { append(", расход ").append(it).append(" л/100 км") }
                            b.n("idle_pct")?.let { append(", на месте ").append(it).append(" % времени") }
                            b.n("avg_trip_km")?.let { append(", средняя поездка ").append(it).append(" км") }
                        })
                    }
                    b.n("sessions")?.let { add("записей $it") }
                    b.n("coolant_median")?.let { add("ОЖ медиана $it °C, максимум ${b.n("coolant_max")} °C (замеров ${b.n("coolant_n")})") }
                    b.n("voltage_median")?.let { add("напряжение на ходу медиана $it В, минимум ${b.n("voltage_min_running")} В") }
                    val trims = listOf("ltft1_median" to "LTFT банк 1", "ltft2_median" to "LTFT банк 2", "stft1_median" to "STFT банк 1", "stft2_median" to "STFT банк 2")
                        .mapNotNull { (k, name) -> b.n(k)?.let { "$name $it %" } }
                    if (trims.isNotEmpty()) add("коррекции на прогретом: " + trims.joinToString(", "))
                    b.n("idle_rpm_median")?.let { add("обороты прогретого ХХ $it") }
                    b.n("dtc_events")?.let { ev ->
                        val codes = (b["new_codes"] as? JsonArray)?.joinToString(", ") { (it as JsonPrimitive).content }
                        val more = b.n("new_codes_more")?.let { " и ещё $it" }.orEmpty()
                        add("событий ошибок $ev" + (codes?.let { ", новые коды: $it$more" } ?: ""))
                    }
                }
                append(parts.joinToString("; "))
            }
            val changes = (json["change"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            if (changes.isNotEmpty()) {
                append("\nИзменение (первый → последний период с достаточными данными): ")
                append(
                    changes.joinToString("; ") { c ->
                        val delta = c.n("delta").orEmpty()
                        val signed = if (delta.startsWith("-")) delta else "+$delta"
                        "${METRICS[c.n("metric")] ?: clean(c.n("metric").orEmpty())}: ${c.n("was")} → ${c.n("now")} ($signed, ${c.n("from")} → ${c.n("to")})"
                    },
                )
            }
            val few = (json["few_data"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.let { m -> METRICS[m] } }
            if (few.isNotEmpty()) append("\nМало данных для сравнения: ").append(few.joinToString("; "))
            if (json["truncated"] != null) append("\nСтарые периоды не поместились.")
        }
    }

    /** Текст из данных пользователя/баз тоже не должен выглядеть как JSON. */
    private fun clean(s: String) = s.replace('"', '\'').replace('{', '(').replace('}', ')').replace('[', '(').replace(']', ')')
}
