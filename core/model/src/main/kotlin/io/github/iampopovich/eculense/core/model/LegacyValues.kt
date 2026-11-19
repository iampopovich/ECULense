package io.github.iampopovich.eculense.core.model

/**
 * Russian values that versions before localization wrote into the DB. They are only MATCHED (to recognize the
 * default car and template maintenance works in existing data) and never shown: the UI uses string resources.
 * This file is a permanent entry in the Cyrillic allowlist.
 */
object LegacyValues {
    const val DEFAULT_CAR_NAME_RU = "Мой автомобиль"

    /** Template maintenance titles as stored by the old template. */
    val MAINTENANCE_TITLES_RU: Map<String, MaintenanceKind> = mapOf(
        "Масло и масляный фильтр двигателя" to MaintenanceKind.OIL_AND_FILTER,
        "Воздушный фильтр" to MaintenanceKind.AIR_FILTER,
        "Свечи зажигания" to MaintenanceKind.SPARK_PLUGS,
        "Топливный фильтр" to MaintenanceKind.FUEL_FILTER,
        "Охлаждающая жидкость" to MaintenanceKind.COOLANT,
        "Тормозная жидкость" to MaintenanceKind.BRAKE_FLUID,
        "Ремень/цепь ГРМ" to MaintenanceKind.TIMING_BELT,
        "Масло вариатора" to MaintenanceKind.CVT_OIL,
        "Масло АКПП" to MaintenanceKind.AUTOMATIC_TRANSMISSION_OIL,
    )

    /** Template notes as stored by the old template. */
    val MAINTENANCE_NOTES_RU: Map<String, MaintenanceNote> = mapOf(
        "В тяжёлых условиях (пробки, короткие поездки) — чаще" to MaintenanceNote.OIL_SEVERE_CONDITIONS,
        "30 000–60 000 км в зависимости от типа свечей (никель/иридий)" to MaintenanceNote.SPARK_PLUGS_TYPE,
        "Интервал — по регламенту двигателя: укажите его" to MaintenanceNote.TIMING_INTERVAL,
    )
}
