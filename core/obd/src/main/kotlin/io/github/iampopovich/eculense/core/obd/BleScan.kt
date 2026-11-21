package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** One device seen by a BLE scan. [name] from the advertisement (may be null), [services] = advertised service UUIDs. */
data class ScannedDevice(val address: String, val name: String?, val services: List<UUID>, val rssi: Int?)

/** A scanned device as an adapter row: [likelyObd] = advertises a UART tier service or has an OBD-like name. */
data class ScannedAdapter(val endpoint: AdapterEndpoint, val likelyObd: Boolean, val rssi: Int?)

enum class ScanStart { STARTED, ALREADY_RUNNING, BLUETOOTH_OFF, NO_PERMISSION, UNSUPPORTED, FAILED }

/**
 * A: adapters that are found by an active, time-limited scan (BLE) instead of a paired-device list. Optional (the
 * Connect screen shows its section only when one is registered). Scans run only while the Connect screen is visible.
 */
interface AdapterScanner {
    val kind: String

    /** Devices of the current/last scan: likely OBD adapters first, then by signal strength. */
    val found: StateFlow<List<ScannedAdapter>>

    val scanning: StateFlow<Boolean>

    /** Starts a scan that stops by itself after [durationMs]. */
    fun start(durationMs: Long = DEFAULT_SCAN_MS): ScanStart

    fun stop()

    companion object {
        /**
         * 10 s: long enough for adapters advertising at 1-2 s intervals to show up several times, short enough to stay
         * well inside Android's limit of 5 scan starts per 30 s (round 2 research A_BLE). How to calibrate: if a real
         * adapter shows up late, note the time to first result in logcat (EcuBle) and lengthen.
         */
        const val DEFAULT_SCAN_MS = 10_000L
    }
}

/** Scan result rules (pure, tested on the JVM). */
object BleScanRules {
    /**
     * Name fragments of BLE OBD adapters seen in the round 2 research and in common clones (Vgate iCar/vLinker,
     * OBDLink CX, Viecar, Konnwei, Rokodil ScanX, generic "OBDII"/"ELM327"). Matching is case-insensitive.
     */
    private val NAME_HINTS = listOf("OBD", "ELM", "VLINK", "V-LINK", "VGATE", "ICAR", "VIECAR", "KONNWEI", "SCANX", "ROKODIL", "CX")

    fun isLikelyObd(device: ScannedDevice): Boolean {
        if (device.services.any { it in GattUuids.UART_SERVICES }) return true
        val name = device.name?.uppercase() ?: return false
        return NAME_HINTS.any { hint -> if (hint == "CX") name.startsWith("OBDLINK") || name == "CX" else name.contains(hint) }
    }

    /** Rows for the UI: one per address (latest wins), likely adapters first, then strongest signal, then name. */
    fun toAdapters(kind: String, devices: Collection<ScannedDevice>): List<ScannedAdapter> = devices
        .groupBy { it.address }.values.map { it.last() }
        .map { d -> ScannedAdapter(AdapterEndpoint(kind, d.address, d.name?.takeIf { it.isNotBlank() } ?: d.address), isLikelyObd(d), d.rssi) }
        .sortedWith(compareBy<ScannedAdapter>({ !it.likelyObd }, { -(it.rssi ?: -127) }, { it.endpoint.label }))

    /**
     * Dual-mode adapters (Viecar 4.0: SPP + BLE) show up both as a paired classic device and as a BLE device. True
     * when [bleName] equals the name of a paired classic adapter (case-insensitive) or shares an OBD name prefix with it.
     */
    fun alsoPairedAsClassic(bleName: String, classicNames: Collection<String>): Boolean {
        val n = bleName.trim().uppercase()
        if (n.isEmpty()) return false
        return classicNames.any { c ->
            val k = c.trim().uppercase()
            k == n || (k.length >= 4 && n.length >= 4 && (k.startsWith(n) || n.startsWith(k)) && NAME_HINTS.any { it != "CX" && k.contains(it) })
        }
    }
}
