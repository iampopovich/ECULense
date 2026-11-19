package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.model.MaintenanceItem
import io.github.iampopovich.eculense.core.model.MaintenanceKind
import io.github.iampopovich.eculense.core.model.MaintenanceNote

/**
 * English names of template maintenance works and notes, for the AI (tool answers are English). The UI uses the
 * string resources of :core:analyticstext with the same English text (values) and Russian (values-ru).
 */
object MaintenanceEnglish {
    fun name(kind: MaintenanceKind): String = when (kind) {
        MaintenanceKind.OIL_AND_FILTER -> "Engine oil and filter"
        MaintenanceKind.AIR_FILTER -> "Air filter"
        MaintenanceKind.SPARK_PLUGS -> "Spark plugs"
        MaintenanceKind.FUEL_FILTER -> "Fuel filter"
        MaintenanceKind.COOLANT -> "Coolant"
        MaintenanceKind.BRAKE_FLUID -> "Brake fluid"
        MaintenanceKind.TIMING_BELT -> "Timing belt/chain"
        MaintenanceKind.CVT_OIL -> "CVT fluid"
        MaintenanceKind.AUTOMATIC_TRANSMISSION_OIL -> "Automatic transmission fluid"
    }

    fun note(note: MaintenanceNote): String = when (note) {
        MaintenanceNote.OIL_SEVERE_CONDITIONS -> "More often in severe conditions (traffic jams, short trips)"
        MaintenanceNote.SPARK_PLUGS_TYPE -> "30,000–60,000 km depending on the plug type (nickel/iridium)"
        MaintenanceNote.TIMING_INTERVAL -> "The interval follows the engine’s service schedule: enter it"
    }

    /** The user's own title wins; a template work without one gets the English name of its kind. */
    fun title(item: MaintenanceItem): String = item.title.ifBlank { item.kind?.let(::name).orEmpty() }

    fun note(item: MaintenanceItem): String? = item.note?.takeIf { it.isNotBlank() } ?: item.noteKind?.let(::note)
}
