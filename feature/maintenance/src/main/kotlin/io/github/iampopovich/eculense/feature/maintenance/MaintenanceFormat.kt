package io.github.iampopovich.eculense.feature.maintenance

import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.MaintenanceStatus
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Тексты экрана «Обслуживание»: числа с неразрывным пробелом-разделителем тысяч, всё по-русски. */
internal object MaintenanceFormat {
    private val ru = Locale.forLanguageTag("ru")
    private val dateInput = DateTimeFormatter.ofPattern("dd.MM.yyyy", ru)

    fun km(v: Number): String = String.format(ru, "%,d", v.toDouble().roundToLong()).replace('\u202F', '\u00A0').replace(' ', '\u00A0') + "\u00A0км"

    fun status(s: MaintenanceStatus): String = when (s) {
        MaintenanceStatus.OVERDUE -> "Просрочено"
        MaintenanceStatus.SOON -> "Скоро"
        MaintenanceStatus.OK -> "В норме"
        MaintenanceStatus.UNKNOWN -> "Нет данных"
    }

    fun interval(item: MaintenanceItem): String {
        val parts = listOfNotNull(item.intervalKm?.let { km(it) }, item.intervalMonths?.let { "$it\u00A0мес." })
        return if (parts.isEmpty()) "Интервал не задан" else "Каждые " + parts.joinToString(" или ")
    }

    fun lastDone(item: MaintenanceItem, zone: ZoneId): String {
        val parts = listOfNotNull(item.lastDoneAtMs?.let { date(it, zone) }, item.lastDoneKm?.let { km(it) })
        return if (parts.isEmpty()) "Ещё не отмечали, когда делали" else "Последний раз: " + parts.joinToString(", ")
    }

    /** «осталось 1 200 км · через 3 мес.», «просрочено на 300 км», «отметьте, когда делали». */
    fun remaining(d: MaintenanceDue): String {
        if (d.status == MaintenanceStatus.UNKNOWN) {
            return when {
                d.item.intervalKm == null && d.item.intervalMonths == null -> "Укажите интервал"
                d.item.lastDoneAtMs == null && d.item.lastDoneKm == null -> "Отметьте, когда делали"
                else -> "Введите пробег, чтобы считать по километрам"
            }
        }
        val parts = buildList {
            d.kmLeft?.let { left -> add(if (left <= 0) "просрочено на ${km(abs(left))}" else "осталось ${km(left)}") }
            d.daysLeft?.let { days -> add(days(days)) }
        }
        return parts.joinToString(" · ")
    }

    private fun days(d: Long): String = when {
        d == 0L -> "срок — сегодня"
        d < 0 -> if (-d > 60) "срок прошёл ${-d / 30}\u00A0мес. назад" else "срок прошёл ${-d}\u00A0дн. назад"
        d > 60 -> "через ${d / 30}\u00A0мес."
        else -> "через $d\u00A0дн."
    }

    fun date(ms: Long, zone: ZoneId): String = dateInput.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** Пробег из поля ввода: «120 000» → 120000; пусто/мусор/вне 0..5 000 000 → null. */
    fun parseKm(text: String): Long? = text.filterNot { it.isWhitespace() || it == '\u00A0' }.toLongOrNull()?.takeIf { it in 0..5_000_000 }

    fun parseInt(text: String, max: Int): Int? = text.filterNot { it.isWhitespace() || it == '\u00A0' }.toIntOrNull()?.takeIf { it in 1..max }

    /** «dd.MM.yyyy» в полдень местного времени (чтобы смена часового пояса не сдвигала дату); будущее нельзя. */
    fun parseDate(text: String, zone: ZoneId, nowMs: Long): Long? = runCatching {
        val d = LocalDate.parse(text.trim(), dateInput)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        if (d.isAfter(today)) null else d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()
}
