package io.github.iampopovich.eculense.core.analyticstext

import io.github.iampopovich.eculense.core.analytics.SensorIds
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.model.MaintenanceKind
import io.github.iampopovich.eculense.core.model.MaintenanceNote

/** Generated: display names of the sensors used in findings (unknown ids are shown as is). */
internal val SENSOR_NAME_RES: Map<String, Int> = mapOf(
    "01.04" to R.string.sensor_engine_load,
    SensorIds.COOLANT to R.string.sensor_coolant,
    SensorIds.STFT1 to R.string.sensor_stft1,
    SensorIds.LTFT1 to R.string.sensor_ltft1,
    SensorIds.STFT2 to R.string.sensor_stft2,
    SensorIds.LTFT2 to R.string.sensor_ltft2,
    SensorIds.RPM to R.string.sensor_rpm,
    SensorIds.SPEED to R.string.sensor_speed,
    "01.0E" to R.string.sensor_timing_advance,
    SensorIds.IAT to R.string.sensor_iat,
    SensorIds.MAF to R.string.sensor_maf,
    "01.11" to R.string.sensor_throttle,
    SensorIds.MIL_DISTANCE to R.string.sensor_mil_distance,
    SensorIds.MODULE_VOLTAGE to R.string.sensor_module_voltage,
    SensorIds.ELM_VOLTAGE to R.string.sensor_elm_voltage,
)

internal fun MaintenanceKind.resId(): Int = when (this) {
    MaintenanceKind.OIL_AND_FILTER -> R.string.maint_kind_oil_and_filter
    MaintenanceKind.AIR_FILTER -> R.string.maint_kind_air_filter
    MaintenanceKind.SPARK_PLUGS -> R.string.maint_kind_spark_plugs
    MaintenanceKind.FUEL_FILTER -> R.string.maint_kind_fuel_filter
    MaintenanceKind.COOLANT -> R.string.maint_kind_coolant
    MaintenanceKind.BRAKE_FLUID -> R.string.maint_kind_brake_fluid
    MaintenanceKind.TIMING_BELT -> R.string.maint_kind_timing_belt
    MaintenanceKind.CVT_OIL -> R.string.maint_kind_cvt_oil
    MaintenanceKind.AUTOMATIC_TRANSMISSION_OIL -> R.string.maint_kind_automatic_transmission_oil
}

internal fun MaintenanceNote.resId(): Int = when (this) {
    MaintenanceNote.OIL_SEVERE_CONDITIONS -> R.string.maint_note_oil_severe_conditions
    MaintenanceNote.SPARK_PLUGS_TYPE -> R.string.maint_note_spark_plugs_type
    MaintenanceNote.TIMING_INTERVAL -> R.string.maint_note_timing_interval
}

internal fun Severity.resId(): Int = when (this) {
    Severity.CRITICAL -> R.string.severity_critical
    Severity.WARNING -> R.string.severity_warning
    Severity.INFO -> R.string.severity_info
    Severity.NO_DATA -> R.string.severity_no_data
    Severity.OK -> R.string.severity_ok
}
