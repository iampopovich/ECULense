package io.github.iampopovich.eculense.feature.dtc

import androidx.annotation.StringRes
import io.github.iampopovich.eculense.core.obd.MidGroup
import io.github.iampopovich.eculense.core.obd.Monitor
import io.github.iampopovich.eculense.core.obd.MonitorStatus
import io.github.iampopovich.eculense.core.obd.RecentClearVerdict
import java.util.Locale

/** Resource ids for the typed diagnostics results of :core:obd (shown only by this feature). */
internal object DiagText {
    @StringRes
    fun monitor(m: Monitor): Int = when (m) {
        Monitor.MISFIRE -> R.string.dtc_monitor_misfire
        Monitor.FUEL_SYSTEM -> R.string.dtc_monitor_fuel_system
        Monitor.COMPONENTS -> R.string.dtc_monitor_components
        Monitor.CATALYST -> R.string.dtc_monitor_catalyst
        Monitor.HEATED_CATALYST -> R.string.dtc_monitor_heated_catalyst
        Monitor.EVAPORATIVE_SYSTEM -> R.string.dtc_monitor_evap
        Monitor.SECONDARY_AIR -> R.string.dtc_monitor_secondary_air
        Monitor.AC_REFRIGERANT -> R.string.dtc_monitor_ac
        Monitor.OXYGEN_SENSOR -> R.string.dtc_monitor_o2
        Monitor.OXYGEN_SENSOR_HEATER -> R.string.dtc_monitor_o2_heater
        Monitor.EGR -> R.string.dtc_monitor_egr
        Monitor.NMHC_CATALYST -> R.string.dtc_monitor_nmhc
        Monitor.NOX_SCR -> R.string.dtc_monitor_nox
        Monitor.BOOST_PRESSURE -> R.string.dtc_monitor_boost
        Monitor.EXHAUST_GAS_SENSOR -> R.string.dtc_monitor_exhaust_sensor
        Monitor.PM_FILTER -> R.string.dtc_monitor_pm_filter
        Monitor.EGR_VVT -> R.string.dtc_monitor_egr_vvt
    }

    @StringRes
    fun status(s: MonitorStatus): Int = when (s) {
        MonitorStatus.COMPLETE -> R.string.dtc_monitor_complete
        MonitorStatus.INCOMPLETE -> R.string.dtc_monitor_incomplete
        MonitorStatus.NOT_SUPPORTED -> R.string.dtc_monitor_not_supported
    }

    @StringRes
    fun verdict(v: RecentClearVerdict): Int = when (v) {
        RecentClearVerdict.LIKELY_CLEARED -> R.string.dtc_usedcar_likely
        RecentClearVerdict.POSSIBLY_CLEARED -> R.string.dtc_usedcar_possibly
        RecentClearVerdict.NO_SIGNS -> R.string.dtc_usedcar_no_signs
        RecentClearVerdict.UNKNOWN -> R.string.dtc_usedcar_unknown
    }

    @StringRes
    fun verdictExplain(v: RecentClearVerdict): Int? = when (v) {
        RecentClearVerdict.LIKELY_CLEARED -> R.string.dtc_usedcar_likely_explain
        RecentClearVerdict.POSSIBLY_CLEARED -> R.string.dtc_usedcar_possibly_explain
        RecentClearVerdict.NO_SIGNS -> R.string.dtc_usedcar_no_signs_explain
        RecentClearVerdict.UNKNOWN -> null
    }

    @StringRes
    fun group(g: MidGroup): Int = when (g) {
        MidGroup.OXYGEN_SENSOR -> R.string.mode06_group_o2
        MidGroup.CATALYST -> R.string.dtc_monitor_catalyst
        MidGroup.EGR_VVT -> R.string.dtc_monitor_egr_vvt
        MidGroup.EVAP -> R.string.dtc_monitor_evap
        MidGroup.OXYGEN_HEATER -> R.string.dtc_monitor_o2_heater
        MidGroup.HEATED_CATALYST -> R.string.dtc_monitor_heated_catalyst
        MidGroup.SECONDARY_AIR -> R.string.dtc_monitor_secondary_air
        MidGroup.FUEL_SYSTEM -> R.string.dtc_monitor_fuel_system
        MidGroup.BOOST -> R.string.dtc_monitor_boost
        MidGroup.NOX -> R.string.dtc_monitor_nox
        MidGroup.MISFIRE -> R.string.dtc_monitor_misfire
        MidGroup.PM_FILTER -> R.string.dtc_monitor_pm_filter
        MidGroup.OTHER -> R.string.mode06_group_other
    }

    /** Plain helper outside composables (lint NonObservableLocale): up to [decimals] digits, no trailing zeros; none from 100 up. */
    fun number(v: Double, decimals: Int = 1): String {
        val digits = if (kotlin.math.abs(v) >= 100) 0 else decimals
        val text = String.format(Locale.getDefault(), "%.${digits}f", v)
        val sep = text.indexOfAny(charArrayOf('.', ','))
        return if (sep < 0) text else text.trimEnd('0').trimEnd('.', ',')
    }
}
