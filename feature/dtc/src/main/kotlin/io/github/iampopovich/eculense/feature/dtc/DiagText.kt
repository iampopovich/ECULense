package io.github.iampopovich.eculense.feature.dtc

import androidx.annotation.StringRes
import io.github.iampopovich.eculense.core.obd.MidGroup
import io.github.iampopovich.eculense.core.obd.Monitor
import io.github.iampopovich.eculense.core.obd.MonitorStatus
import io.github.iampopovich.eculense.core.obd.RecentClearVerdict
import io.github.iampopovich.eculense.core.obdtext.ObdText
import java.util.Locale

/** Resource ids for the typed diagnostics results of :core:obd (shown only by this feature). */
internal object DiagText {
    /** Monitor names live in :core:obdtext (the health report shows them too). */
    @StringRes
    fun monitor(m: Monitor): Int = ObdText.monitorRes(m)

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
        MidGroup.CATALYST -> io.github.iampopovich.eculense.core.obdtext.R.string.obd_monitor_catalyst
        MidGroup.EGR_VVT -> io.github.iampopovich.eculense.core.obdtext.R.string.obd_monitor_egr_vvt
        MidGroup.EVAP -> io.github.iampopovich.eculense.core.obdtext.R.string.obd_monitor_evap
        MidGroup.OXYGEN_HEATER -> io.github.iampopovich.eculense.core.obdtext.R.string.obd_monitor_o2_heater
        MidGroup.HEATED_CATALYST -> io.github.iampopovich.eculense.core.obdtext.R.string.obd_monitor_heated_catalyst
        MidGroup.SECONDARY_AIR -> io.github.iampopovich.eculense.core.obdtext.R.string.obd_monitor_secondary_air
        MidGroup.FUEL_SYSTEM -> io.github.iampopovich.eculense.core.obdtext.R.string.obd_monitor_fuel_system
        MidGroup.BOOST -> io.github.iampopovich.eculense.core.obdtext.R.string.obd_monitor_boost
        MidGroup.NOX -> io.github.iampopovich.eculense.core.obdtext.R.string.obd_monitor_nox
        MidGroup.MISFIRE -> io.github.iampopovich.eculense.core.obdtext.R.string.obd_monitor_misfire
        MidGroup.PM_FILTER -> io.github.iampopovich.eculense.core.obdtext.R.string.obd_monitor_pm_filter
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
