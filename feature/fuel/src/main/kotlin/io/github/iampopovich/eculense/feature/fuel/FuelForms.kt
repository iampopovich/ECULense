package io.github.iampopovich.eculense.feature.fuel

import io.github.iampopovich.eculense.core.model.Expense
import io.github.iampopovich.eculense.core.model.ExpenseCategory
import io.github.iampopovich.eculense.core.model.FillUpMath
import io.github.iampopovich.eculense.core.model.FuelFillUp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** Number input and formatting of the fuel and expense forms (input patterns don't depend on the app language). */
internal object FuelFormat {
    private val dateInput = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT)

    fun date(ms: Long, zone: ZoneId): String = dateInput.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** «dd.MM.yyyy» at local noon; future dates are refused. */
    fun parseDate(text: String, zone: ZoneId, nowMs: Long): Long? = runCatching {
        val d = LocalDate.parse(text.trim(), dateInput)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        if (d.isAfter(today)) null else d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    /** «35,5» / «35.5» / «1 234,5» → number; blank → null (the caller tells blank from garbage). */
    fun parseNumber(text: String): Double? =
        text.trim().replace("\u00A0", "").replace(" ", "").replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    fun parseKm(text: String): Long? = text.filterNot { it.isWhitespace() || it == '\u00A0' }.toLongOrNull()?.takeIf { it in 0..5_000_000 }

    fun number(v: Double, decimals: Int): String = String.format(Locale.getDefault(), "%,.${decimals}f", v).replace('\u202F', '\u00A0')

    /** For prefilling a field: plain digits with a dot, no grouping. */
    fun plain(v: Double, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals}f", v).let { if ('.' in it) it.trimEnd('0').trimEnd('.') else it }

    fun money(v: Double, currency: String?): String = "${number(v, if (v >= 100) 0 else 2)} ${currency.orEmpty()}".trim()
}

internal enum class FillUpError { DATE, ODOMETER, LITRES, PRICE, TOTAL, NEED_TWO }

/** The fill-up form: two of litres/price/total are typed, the third is computed (the receipt total wins if all three are). */
internal data class FillUpForm(
    val id: Long = 0,
    val date: String = "",
    val odometer: String = "",
    val litres: String = "",
    val price: String = "",
    val total: String = "",
    val fullTank: Boolean = true,
    val missedPrevious: Boolean = false,
    val station: String = "",
    val externalId: String? = null,
) {
    private fun opt(text: String): Pair<Double?, Boolean> {
        if (text.isBlank()) return null to true
        val v = FuelFormat.parseNumber(text)
        return v to (v != null && v > 0)
    }

    fun errors(zone: ZoneId, nowMs: Long): Set<FillUpError> = buildSet {
        if (FuelFormat.parseDate(date, zone, nowMs) == null) add(FillUpError.DATE)
        if (odometer.isNotBlank() && FuelFormat.parseKm(odometer) == null) add(FillUpError.ODOMETER)
        val (l, lOk) = opt(litres)
        val (p, pOk) = opt(price)
        val (t, tOk) = opt(total)
        if (!lOk || (l != null && l > 500)) add(FillUpError.LITRES)
        if (!pOk) add(FillUpError.PRICE)
        if (!tOk) add(FillUpError.TOTAL)
        if (lOk && pOk && tOk && FillUpMath.complete(l, p, t) == null) add(FillUpError.NEED_TWO)
    }

    /** The computed triple (litres, price, total) when two numbers are valid. */
    fun computed(): Triple<Double, Double, Double>? =
        FillUpMath.complete(FuelFormat.parseNumber(litres), FuelFormat.parseNumber(price), FuelFormat.parseNumber(total))

    fun toFillUp(carId: Long, zone: ZoneId, nowMs: Long): FuelFillUp? {
        if (errors(zone, nowMs).isNotEmpty()) return null
        val (l, p, t) = computed() ?: return null
        return FuelFillUp(
            id = id, carId = carId, atMs = FuelFormat.parseDate(date, zone, nowMs)!!, odometerKm = FuelFormat.parseKm(odometer),
            litres = l, pricePerL = p, total = t, fullTank = fullTank, missedPrevious = missedPrevious,
            station = station.trim().ifBlank { null }, externalId = externalId,
        )
    }

    companion object {
        /** New fill-up: today, odometer from the current estimate, the car's fuel price. */
        fun new(nowMs: Long, zone: ZoneId, odometerKm: Double?, fuelPrice: Double?) = FillUpForm(
            date = FuelFormat.date(nowMs, zone),
            odometer = odometerKm?.roundToLong()?.toString().orEmpty(),
            price = fuelPrice?.let { FuelFormat.plain(it, 2) }.orEmpty(),
        )

        fun of(f: FuelFillUp, zone: ZoneId) = FillUpForm(
            id = f.id, date = FuelFormat.date(f.atMs, zone), odometer = f.odometerKm?.toString().orEmpty(),
            litres = FuelFormat.plain(f.litres, 2), price = f.pricePerL?.let { FuelFormat.plain(it, 2) }.orEmpty(),
            total = f.total?.let { FuelFormat.plain(it, 2) }.orEmpty(), fullTank = f.fullTank, missedPrevious = f.missedPrevious,
            station = f.station.orEmpty(), externalId = f.externalId,
        )
    }
}

internal enum class ExpenseError { DATE, AMOUNT, ODOMETER }

internal data class ExpenseForm(
    val id: Long = 0,
    val category: ExpenseCategory = ExpenseCategory.SERVICE,
    val date: String = "",
    val amount: String = "",
    val odometer: String = "",
    val note: String = "",
    val maintenanceItemId: Long? = null,
) {
    /** Only service and repair close a maintenance work; other categories don't offer the link. */
    val canLink: Boolean get() = category in io.github.iampopovich.eculense.core.data.ExpenseRepository.CLOSES_MAINTENANCE

    fun errors(zone: ZoneId, nowMs: Long): Set<ExpenseError> = buildSet {
        if (FuelFormat.parseDate(date, zone, nowMs) == null) add(ExpenseError.DATE)
        val a = FuelFormat.parseNumber(amount)
        if (a == null || a <= 0 || a > 100_000_000) add(ExpenseError.AMOUNT)
        if (odometer.isNotBlank() && FuelFormat.parseKm(odometer) == null) add(ExpenseError.ODOMETER)
    }

    fun toExpense(carId: Long, zone: ZoneId, nowMs: Long): Expense? {
        if (errors(zone, nowMs).isNotEmpty()) return null
        return Expense(
            id = id, carId = carId, atMs = FuelFormat.parseDate(date, zone, nowMs)!!, category = category,
            amount = FuelFormat.parseNumber(amount)!!, odometerKm = FuelFormat.parseKm(odometer), note = note.trim().ifBlank { null },
            maintenanceItemId = maintenanceItemId.takeIf { canLink },
        )
    }

    companion object {
        fun new(nowMs: Long, zone: ZoneId) = ExpenseForm(date = FuelFormat.date(nowMs, zone))

        fun of(e: Expense, zone: ZoneId) = ExpenseForm(
            id = e.id, category = e.category, date = FuelFormat.date(e.atMs, zone), amount = FuelFormat.plain(e.amount, 2),
            odometer = e.odometerKm?.toString().orEmpty(), note = e.note.orEmpty(), maintenanceItemId = e.maintenanceItemId,
        )
    }
}
