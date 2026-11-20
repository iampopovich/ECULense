package io.github.iampopovich.eculense.core.model

/**
 * D1: one fill-up. [odometerKm] is the dashboard reading at the pump (null: not entered, the fill-up then can't anchor a
 * full-to-full interval). [fullTank]: filled to the brim. [missedPrevious]: at least one fill-up before this one was not
 * logged, so the chain of litres since the previous full tank is broken.
 */
data class FuelFillUp(
    val id: Long = 0,
    val carId: Long,
    val atMs: Long,
    val odometerKm: Long?,
    val litres: Double,
    val pricePerL: Double?,
    val total: Double?,
    val fullTank: Boolean = true,
    val missedPrevious: Boolean = false,
    val station: String? = null,
    val note: String? = null,
    /** Where the row came from: null = typed in the app, otherwise «csv:<hash>» (re-import does not duplicate). */
    val externalId: String? = null,
)

/** D2: what an expense was for. Fuel is not here: fill-ups are the fuel expenses. */
enum class ExpenseCategory { SERVICE, REPAIR, PARTS, INSURANCE, TAX, PARKING, WASH, OTHER }

/** D2: one expense. [maintenanceItemId]: the maintenance work it paid for (SERVICE/REPAIR close that work). */
data class Expense(
    val id: Long = 0,
    val carId: Long,
    val atMs: Long,
    val category: ExpenseCategory,
    val amount: Double,
    val odometerKm: Long? = null,
    val note: String? = null,
    val maintenanceItemId: Long? = null,
)

/** The three numbers of a fill-up: two are typed, the third follows (litres × price = total). */
object FillUpMath {
    /**
     * Completes litres/price/total from any two of them; null when fewer than two are positive. When all three are given,
     * the total is kept as typed (it is what the receipt says) and the price is recomputed from it.
     */
    fun complete(litres: Double?, pricePerL: Double?, total: Double?): Triple<Double, Double, Double>? {
        val l = litres?.takeIf { it > 0 }
        val p = pricePerL?.takeIf { it > 0 }
        val t = total?.takeIf { it > 0 }
        return when {
            l != null && t != null -> Triple(l, t / l, t)
            l != null && p != null -> Triple(l, p, l * p)
            p != null && t != null -> Triple(t / p, p, t)
            else -> null
        }
    }
}
