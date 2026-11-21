package io.github.iampopovich.eculense.core.obdb

/** What an extended signal means for the EV battery card (R-E). */
enum class EvRole { SOC, SOH, HV_VOLTAGE, HV_CURRENT, CELL_V_MAX, CELL_V_MIN, BATT_T_MAX, BATT_T_MIN }

/**
 * R-E (research H_EV): finds the battery signals of an OBDb signalset. OBDb `suggestedMetric` names SOC, SOH, traction
 * voltage and current; cell and temperature extremes have no metric, so they are matched by the signal names the
 * Kia/Hyundai signalsets use («Maximum cell voltage», «HV battery temperature, maximum»; Kia-Niro-EV, Kia-EV6,
 * Hyundai-IONIQ-5). Only signals of the battery ECU (type «battery», 7E4 on Kia/Hyundai) count when the signalset names
 * one, so a dashboard SOC copy of another module does not win.
 */
object EvSignals {
    fun roleOf(signal: Signal): EvRole? {
        when (signal.suggestedMetric) {
            "stateOfCharge" -> return EvRole.SOC
            "stateOfHealth" -> return EvRole.SOH
            "tractionBatteryVoltage" -> return EvRole.HV_VOLTAGE
            "tractionBatteryCurrent" -> return EvRole.HV_CURRENT
        }
        val n = signal.name.lowercase()
        return when {
            n.startsWith("maximum cell voltage") && !n.contains(" id") -> EvRole.CELL_V_MAX
            n.startsWith("minimum cell voltage") && !n.contains(" id") -> EvRole.CELL_V_MIN
            n == "hv battery temperature, maximum" -> EvRole.BATT_T_MAX
            n == "hv battery temperature, minimum" -> EvRole.BATT_T_MIN
            else -> null
        }
    }

    /** Role → the first matching signal id of [commands] (battery ECU first), in signalset order. */
    fun roles(commands: List<SignalCommand>, ecus: List<Ecu>): Map<EvRole, String> {
        val battery = ecus.filter { it.type == "battery" }.map { it.hdr.uppercase() }.toSet()
        val ordered = commands.sortedBy { if (it.hdr.uppercase() in battery) 0 else 1 }
        val out = LinkedHashMap<EvRole, String>()
        for (c in ordered) for (s in c.signals) {
            if (s.hidden || s.fmt == null) continue
            val role = roleOf(s) ?: continue
            if (role !in out) out[role] = s.id
        }
        return out
    }

    /** The signalset describes a battery-electric car: a battery ECU with a state-of-charge signal. */
    fun isEv(commands: List<SignalCommand>, ecus: List<Ecu>): Boolean {
        val battery = ecus.filter { it.type == "battery" }.map { it.hdr.uppercase() }.toSet()
        return commands.any { c -> c.hdr.uppercase() in battery && c.signals.any { roleOf(it) == EvRole.SOC } }
    }
}

/** EV card values (null = not read yet); power and cell spread are derived. */
data class EvBattery(
    val socPct: Double? = null,
    val sohPct: Double? = null,
    val voltageV: Double? = null,
    val currentA: Double? = null,
    val cellMaxV: Double? = null,
    val cellMinV: Double? = null,
    val tempMaxC: Double? = null,
    val tempMinC: Double? = null,
) {
    /** HV power, kW = V · A / 1000 (positive = the battery discharges, the OBDb Kia/Hyundai current sign). */
    val powerKw: Double? get() = if (voltageV != null && currentA != null) voltageV * currentA / 1_000 else null

    /** Spread of the cell voltages, V (max − min). */
    val cellSpreadV: Double? get() = if (cellMaxV != null && cellMinV != null) cellMaxV - cellMinV else null

    val isEmpty: Boolean get() = listOf(socPct, sohPct, voltageV, currentA, cellMaxV, cellMinV, tempMaxC, tempMinC).all { it == null }

    companion object {
        fun of(values: Map<EvRole, Double>) = EvBattery(
            values[EvRole.SOC], values[EvRole.SOH], values[EvRole.HV_VOLTAGE], values[EvRole.HV_CURRENT],
            values[EvRole.CELL_V_MAX], values[EvRole.CELL_V_MIN], values[EvRole.BATT_T_MAX], values[EvRole.BATT_T_MIN],
        )
    }
}

/** OBDb `unit` names → the unit symbols the app shows next to values (symbols, not words: no translation needed). */
object ObdbUnits {
    fun symbol(unit: String?): String = when (unit) {
        "percent" -> "%"
        "volts" -> "V"
        "millivolts" -> "mV"
        "amps" -> "A"
        "milliamps" -> "mA"
        "celsius" -> "°C"
        "kilowatts" -> "kW"
        "watts" -> "W"
        "kilowattHours" -> "kWh"
        "ampereHours" -> "Ah"
        "kilometersPerHour" -> "km/h"
        "kilometers" -> "km"
        "rpm" -> "rpm"
        "kilopascal" -> "kPa"
        "bars" -> "bar"
        "psi" -> "psi"
        "seconds" -> "s"
        "minutes" -> "min"
        "hours" -> "h"
        "degrees" -> "°"
        "kiloohms" -> "kΩ"
        "ohms" -> "Ω"
        "hertz" -> "Hz"
        "newtonMeters" -> "N·m"
        "liters" -> "L"
        "gramsPerSecond" -> "g/s"
        "metersPerSecondSquared" -> "m/s²"
        else -> ""
    }
}
