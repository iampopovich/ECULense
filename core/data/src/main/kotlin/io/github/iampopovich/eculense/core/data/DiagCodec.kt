package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.FreezeFrame
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.obd.ClearedCounters
import io.github.iampopovich.eculense.core.obd.Ignition
import io.github.iampopovich.eculense.core.obd.Monitor
import io.github.iampopovich.eculense.core.obd.MonitorState
import io.github.iampopovich.eculense.core.obd.MonitorStatus
import io.github.iampopovich.eculense.core.obd.Readiness

/**
 * Compact text forms of the diagnostic snapshots stored in Room (B3/B4): one column instead of a table per value.
 * Freeze frame: "dtc=P0133;01.0C=812.5|rpm;01.0D=63|km/h". Readiness: "ignition=SPARK;MISFIRE=COMPLETE;...".
 * Counters: "warmUps=3;km=50;min=40;milMin=0;milKm=0". Unknown keys and broken entries are skipped on read.
 */
object DiagCodec {
    fun encode(frame: FreezeFrame): String = buildList {
        frame.dtcCode?.let { add("dtc=$it") }
        frame.readings.forEach { add("${it.sensorId}=${it.value}|${it.unit}") }
    }.joinToString(";")

    fun decodeFreezeFrame(text: String?, atMs: Long = 0): FreezeFrame? {
        if (text.isNullOrBlank()) return null
        var dtc: String? = null
        val readings = mutableListOf<SensorReading>()
        for (entry in text.split(';')) {
            val key = entry.substringBefore('=', "")
            val rest = entry.substringAfter('=', "")
            if (key.isEmpty()) continue
            if (key == "dtc") {
                dtc = rest
                continue
            }
            val value = rest.substringBefore('|').toDoubleOrNull() ?: continue
            readings += SensorReading(key, value, rest.substringAfter('|', ""), atMs)
        }
        return FreezeFrame(dtc, readings)
    }

    fun encode(readiness: Readiness): String =
        (listOf("ignition=${readiness.ignition.name}") + readiness.monitors.map { "${it.monitor.name}=${it.status.name}" }).joinToString(";")

    fun decodeReadiness(text: String?, milOn: Boolean?, dtcCount: Int?): Readiness? {
        if (text.isNullOrBlank()) return null
        val pairs = text.split(';').map { it.substringBefore('=') to it.substringAfter('=', "") }
        val ignition = pairs.firstOrNull { it.first == "ignition" }?.second
            ?.let { runCatching { Ignition.valueOf(it) }.getOrNull() } ?: Ignition.SPARK
        val monitors = pairs.mapNotNull { (k, v) ->
            val m = runCatching { Monitor.valueOf(k) }.getOrNull() ?: return@mapNotNull null
            val s = runCatching { MonitorStatus.valueOf(v) }.getOrNull() ?: return@mapNotNull null
            MonitorState(m, s)
        }
        return Readiness(milOn ?: false, dtcCount ?: 0, ignition, monitors)
    }

    fun encode(c: ClearedCounters): String = listOfNotNull(
        c.warmUpsSinceClear?.let { "warmUps=$it" },
        c.kmSinceClear?.let { "km=$it" },
        c.minutesSinceClear?.let { "min=$it" },
        c.minutesWithMil?.let { "milMin=$it" },
        c.kmWithMil?.let { "milKm=$it" },
    ).joinToString(";")

    fun decodeCounters(text: String?): ClearedCounters? {
        if (text == null) return null
        val m = text.split(';').associate { it.substringBefore('=') to it.substringAfter('=', "").toIntOrNull() }
        return ClearedCounters(m["warmUps"], m["km"], m["min"], m["milMin"], m["milKm"])
    }
}
