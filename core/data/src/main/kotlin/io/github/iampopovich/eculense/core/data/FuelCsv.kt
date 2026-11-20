package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.FillUpMath
import io.github.iampopovich.eculense.core.model.FuelFillUp
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale
import kotlin.math.roundToLong

/** Result of reading a fuel CSV: rows ready for [FuelRepository.import], plus what could not be read. */
data class FuelCsvResult(val format: FuelCsvFormat, val rows: List<FuelFillUp>, val badLines: Int)

enum class FuelCsvFormat {
    /** Fuelio export/backup: sections «## Vehicle», «## Log», … (layout checked against Fuelio's own sample and importers). */
    FUELIO,
    /** Any CSV with a header row whose columns are recognized by name (date, odometer, litres, price/total, full). */
    GENERIC,
    UNKNOWN,
}

/**
 * D1 import. Fuelio «## Log» columns: Data, Odo (km|mi), Fuel (litres|us gallons|…), Full, Price (optional) = total paid,
 * l/100km (optional), latitude, longitude (never read), City, Notes, Missed, TankNumber, FuelType, VolumePrice, StationID,
 * ExcludeDistance, UniqueId, TankCalc. Dates follow «ImportCSVDateFormat» of the «## Vehicle» section. Miles and gallons
 * are converted. A second tank (TankNumber 2) comes in as partial fills so it never closes a full-to-full interval.
 * Rows get externalId «csv:<hash of car, date, odometer, litres>» so a repeated import skips them.
 */
object FuelCsv {
    private const val MI_KM = 1.609344
    private const val US_GAL_L = 3.785411784
    private const val IMP_GAL_L = 4.54609

    fun parse(text: String, carId: Long, zone: ZoneId = ZoneId.systemDefault()): FuelCsvResult {
        val lines = text.removePrefix("\uFEFF").lines().filter { it.isNotBlank() }
        return if (lines.any { cells(it).firstOrNull()?.trim() == "## Log" }) fuelio(lines, carId, zone) else generic(lines, carId, zone)
    }

    private fun fuelio(lines: List<String>, carId: Long, zone: ZoneId): FuelCsvResult {
        var section = ""
        var vehicleHeader: List<String>? = null
        var dateFormat: String? = null
        var header: List<String>? = null
        val rows = mutableListOf<FuelFillUp>()
        var bad = 0
        for (line in lines) {
            val c = cells(line)
            val first = c.firstOrNull()?.trim().orEmpty()
            if (first.startsWith("## ")) {
                section = first.removePrefix("## ").trim()
                header = null
                continue
            }
            when (section) {
                "Vehicle" -> if (vehicleHeader == null) vehicleHeader = c else {
                    val i = vehicleHeader.indexOfFirst { it.equals("ImportCSVDateFormat", true) }
                    if (i >= 0) dateFormat = c.getOrNull(i)?.takeIf { it.isNotBlank() }
                }
                "Log" -> {
                    val h = header
                    if (h == null) {
                        header = c
                        continue
                    }
                    val row = fuelioRow(h, c, carId, dateFormat, zone)
                    if (row == null) bad++ else rows += row
                }
            }
        }
        return FuelCsvResult(FuelCsvFormat.FUELIO, rows, bad)
    }

    private fun fuelioRow(h: List<String>, c: List<String>, carId: Long, fmt: String?, zone: ZoneId): FuelFillUp? {
        fun col(prefix: String) = h.indexOfFirst { it.trim().startsWith(prefix, true) }
        fun at(i: Int) = c.getOrNull(i)?.trim().orEmpty()
        val odoHeader = h.getOrNull(col("Odo")).orEmpty().lowercase()
        val fuelHeader = h.getOrNull(col("Fuel (")).orEmpty().lowercase()
        val atMs = date(at(col("Data")), fmt, zone) ?: return null
        val odo = number(at(col("Odo")))?.let { if ("(mi" in odoHeader) it * MI_KM else it }
        val litresRaw = number(at(col("Fuel ("))) ?: return null
        val litres = when {
            "us gal" in fuelHeader -> litresRaw * US_GAL_L
            "gal" in fuelHeader -> litresRaw * IMP_GAL_L
            else -> litresRaw
        }
        val unit = when {
            "us gal" in fuelHeader -> US_GAL_L
            "gal" in fuelHeader -> IMP_GAL_L
            else -> 1.0
        }
        val total = number(at(col("Price")))
        val perUnit = number(at(col("VolumePrice")))?.let { it / unit }
        val (l, p, t) = FillUpMath.complete(litres, perUnit, total) ?: Triple(litres, perUnit, total)
        val secondTank = at(col("TankNumber")).let { it.isNotEmpty() && it != "1" }
        return row(
            carId, atMs, odo, l ?: return null, p, t,
            full = at(col("Full")) == "1" && !secondTank,
            missed = at(col("Missed")) == "1",
            station = at(col("City")).ifBlank { null },
            note = at(col("Notes")).ifBlank { null },
        )
    }

    private val GENERIC_DATE = listOf("date", "data", "дата")
    private val GENERIC_ODO = listOf("odo", "mileage", "пробег", "одометр")
    private val GENERIC_LITRES = listOf("litre", "liter", "fuel", "volume", "литр", "объем", "объём")
    private val GENERIC_TOTAL = listOf("total", "cost", "amount", "sum", "сумма", "стоимость")
    private val GENERIC_PRICE = listOf("price", "цена")
    private val GENERIC_FULL = listOf("full", "полн")

    private fun generic(lines: List<String>, carId: Long, zone: ZoneId): FuelCsvResult {
        val header = lines.firstOrNull()?.let(::cells)?.map { it.trim().lowercase(Locale.ROOT) }
            ?: return FuelCsvResult(FuelCsvFormat.UNKNOWN, emptyList(), 0)
        fun find(keys: List<String>, not: List<String> = emptyList()) =
            header.indexOfFirst { h -> keys.any { it in h } && not.none { it in h } }
        val iDate = find(GENERIC_DATE)
        val iLitres = find(GENERIC_LITRES, not = GENERIC_PRICE + GENERIC_TOTAL + listOf("type", "тип"))
        if (iDate < 0 || iLitres < 0) return FuelCsvResult(FuelCsvFormat.UNKNOWN, emptyList(), lines.size - 1)
        val iOdo = find(GENERIC_ODO)
        val iTotal = find(GENERIC_TOTAL)
        val iPrice = find(GENERIC_PRICE, not = GENERIC_TOTAL)
        val iFull = find(GENERIC_FULL)
        val rows = mutableListOf<FuelFillUp>()
        var bad = 0
        for (line in lines.drop(1)) {
            val c = cells(line).map { it.trim() }
            val atMs = date(c.getOrNull(iDate).orEmpty(), null, zone)
            val litres = number(c.getOrNull(iLitres).orEmpty())
            if (atMs == null || litres == null || litres <= 0) {
                bad++
                continue
            }
            val total = if (iTotal >= 0) number(c.getOrNull(iTotal).orEmpty()) else null
            val price = if (iPrice >= 0) number(c.getOrNull(iPrice).orEmpty()) else null
            val (l, p, t) = FillUpMath.complete(litres, price, total) ?: Triple(litres, price, total)
            val fullCell = if (iFull >= 0) c.getOrNull(iFull).orEmpty().lowercase(Locale.ROOT) else "1"
            val full = fullCell in setOf("1", "true", "yes", "y", "да", "+")
            rows += row(carId, atMs, if (iOdo >= 0) number(c.getOrNull(iOdo).orEmpty()) else null, l, p, t, full, missed = false, station = null, note = null)
        }
        return FuelCsvResult(FuelCsvFormat.GENERIC, rows, bad)
    }

    private fun row(
        carId: Long, atMs: Long, odo: Double?, litres: Double, price: Double?, total: Double?, full: Boolean, missed: Boolean,
        station: String?, note: String?,
    ): FuelFillUp {
        val km = odo?.takeIf { it > 0 }?.roundToLong()
        val key = "$carId|$atMs|${km ?: ""}|${String.format(Locale.ROOT, "%.3f", litres)}"
        return FuelFillUp(
            carId = carId, atMs = atMs, odometerKm = km, litres = litres, pricePerL = price, total = total, fullTank = full,
            missedPrevious = missed, station = station, note = note, externalId = "csv:" + sha1(key),
        )
    }

    /** Splits one CSV line (comma or semicolon), honoring double quotes. */
    internal fun cells(line: String): List<String> {
        val sep = if (line.count { it == ';' } > line.count { it == ',' }) ';' else ','
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == sep && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    /** «1 234,5», «1234.5», «1,234.5» → 1234.5; blank or garbage → null. */
    internal fun number(s: String): Double? {
        var t = s.trim().replace("\u00A0", "").replace(" ", "")
        if (t.isEmpty()) return null
        if (t.contains(',') && t.contains('.')) t = t.replace(",", "") else t = t.replace(',', '.')
        return t.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private val FALLBACK_DATES = listOf("yyyy-MM-dd", "dd.MM.yyyy", "dd/MM/yyyy", "MM/dd/yyyy", "yyyy/MM/dd")

    internal fun date(s: String, declared: String?, zone: ZoneId): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        for (p in listOfNotNull(declared) + FALLBACK_DATES) {
            parseWith(t, p, zone)?.let { return it }
        }
        return null
    }

    private fun parseWith(t: String, pattern: String, zone: ZoneId): Long? = runCatching {
        // Optional time part after the date: «2016-06-18 10:23» and «2016-06-18 10:23:15» both read.
        val f: DateTimeFormatter = DateTimeFormatterBuilder().appendPattern(pattern)
            .optionalStart().appendLiteral(' ').appendPattern("HH:mm").optionalStart().appendPattern(":ss").optionalEnd().optionalEnd()
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 12).toFormatter(Locale.ROOT)
        val parsed = f.parse(t)
        val dt = if (parsed.isSupported(ChronoField.MINUTE_OF_HOUR)) LocalDateTime.from(parsed) else LocalDate.from(parsed).atTime(12, 0)
        dt.atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
}
