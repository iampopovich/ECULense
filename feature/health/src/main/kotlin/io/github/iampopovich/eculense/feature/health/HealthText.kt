package io.github.iampopovich.eculense.feature.health

import io.github.iampopovich.eculense.core.analytics.HealthItem
import io.github.iampopovich.eculense.core.analytics.HealthItemId
import io.github.iampopovich.eculense.core.analytics.HealthNote
import io.github.iampopovich.eculense.core.analytics.HealthStatus

/** Resolves a string resource with arguments (Context/Resources in the app, a fake in JVM tests). */
internal typealias Str = (id: Int, args: List<Any>) -> String

internal fun Str.s(id: Int, vararg args: Any): String = this(id, args.toList())

internal fun HealthItemId.titleRes(): Int = when (this) {
    HealthItemId.STORED_CODES -> R.string.health_item_stored
    HealthItemId.PENDING_CODES -> R.string.health_item_pending
    HealthItemId.PERMANENT_CODES -> R.string.health_item_permanent
    HealthItemId.CHECK_ENGINE -> R.string.health_item_mil
    HealthItemId.READINESS -> R.string.health_item_readiness
    HealthItemId.RECENT_CLEAR -> R.string.health_item_recent_clear
    HealthItemId.COOLANT -> R.string.health_item_coolant
    HealthItemId.VOLTAGE -> R.string.health_item_voltage
    HealthItemId.FUEL_TRIMS -> R.string.health_item_trims
    HealthItemId.O2_SENSOR -> R.string.health_item_o2
    HealthItemId.IDLE -> R.string.health_item_idle
}

internal fun HealthStatus.labelRes(): Int = when (this) {
    HealthStatus.OK -> R.string.health_status_ok
    HealthStatus.ATTENTION -> R.string.health_status_attention
    HealthStatus.FAULT -> R.string.health_status_fault
    HealthStatus.NOT_SUPPORTED -> R.string.health_status_not_supported
    HealthStatus.NOT_JUDGED -> R.string.health_status_not_judged
}

internal fun HealthStatus.verdictRes(): Int = when (this) {
    HealthStatus.OK -> R.string.health_verdict_ok
    HealthStatus.ATTENTION -> R.string.health_verdict_attention
    HealthStatus.FAULT -> R.string.health_verdict_fault
    HealthStatus.NOT_SUPPORTED, HealthStatus.NOT_JUDGED -> R.string.health_verdict_none
}

internal fun HealthNote.res(): Int? = when (this) {
    HealthNote.NONE -> null
    HealthNote.RESIDUAL_HEAT -> R.string.health_note_residual_heat
    HealthNote.COLD_ENGINE -> R.string.health_note_cold
    HealthNote.ENGINE_OFF -> R.string.health_note_engine_off
    HealthNote.RESTING_VOLTAGE -> R.string.health_note_resting
    HealthNote.CHARGING_VOLTAGE -> R.string.health_note_charging
    HealthNote.CAN_ONLY -> R.string.health_note_can_only
    HealthNote.UNSTABLE_IDLE -> R.string.health_note_unstable_idle
    HealthNote.O2_LAZY -> R.string.health_note_o2_lazy
    HealthNote.MOVING -> R.string.health_note_moving
    HealthNote.CLEARED_RECENTLY -> R.string.health_note_cleared
}

/** «3», «2 incomplete», «92 °C», «14.1 V», «+4.5 %», «swing 0.70 V», «760 rpm»; null when there is no number. */
internal fun HealthItem.valueText(str: Str): String? {
    if (id == HealthItemId.CHECK_ENGINE && (status == HealthStatus.OK || status == HealthStatus.FAULT)) {
        return str.s(if (status == HealthStatus.FAULT) R.string.health_mil_on else R.string.health_mil_off)
    }
    val v = value ?: return null
    return when (id) {
        HealthItemId.STORED_CODES, HealthItemId.PENDING_CODES, HealthItemId.PERMANENT_CODES -> str.s(R.string.health_value_count, v.toInt())
        HealthItemId.READINESS -> if (v > 0) str.s(R.string.health_value_incomplete, v.toInt()) else null
        HealthItemId.COOLANT -> str.s(R.string.health_value_celsius, v)
        HealthItemId.VOLTAGE -> str.s(R.string.health_value_volts, v)
        HealthItemId.FUEL_TRIMS -> str.s(R.string.health_value_percent, v)
        HealthItemId.O2_SENSOR -> str.s(R.string.health_value_o2, v)
        HealthItemId.IDLE -> str.s(R.string.health_value_rpm, v)
        HealthItemId.CHECK_ENGINE, HealthItemId.RECENT_CLEAR -> null
    }
}

/** One line per item for screens and the AI prefill: «Coolant temperature: OK · 92 °C». */
internal fun HealthItem.line(str: Str): String =
    listOfNotNull(str.s(id.titleRes()) + ": " + str.s(status.labelRes()), valueText(str), note.res()?.let { str.s(it) }).joinToString(" · ")
