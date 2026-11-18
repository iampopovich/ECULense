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
        "dtcHistory" to "Коды ошибок",
        "findings" to "Находки анализа",
        "maintenanceDue" to "Обслуживание (ТО)",
        "listSessions" to "Последняя запись",
    )

    /** Ведущее поле элемента списка — пишется первым, без подписи. */
    private val LEAD = listOf("code", "work", "title")

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
        ?: if (reason.startsWith("maintenance list is empty")) "список работ ТО пуст (Пит-стоп → Обслуживание)" else "нет данных"

    /** Текст из данных пользователя/баз тоже не должен выглядеть как JSON. */
    private fun clean(s: String) = s.replace('"', '\'').replace('{', '(').replace('}', ')').replace('[', '(').replace(']', ')')
}
