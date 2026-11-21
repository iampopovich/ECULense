package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

/** Characteristic properties that matter for a serial-over-GATT link (subset of the Bluetooth Core spec bits). */
enum class GattProperty { READ, WRITE, WRITE_NO_RESPONSE, NOTIFY, INDICATE }

data class GattCharacteristic(val uuid: UUID, val properties: Set<GattProperty>) {
    val canNotify get() = GattProperty.NOTIFY in properties || GattProperty.INDICATE in properties
    val canWrite get() = GattProperty.WRITE in properties || GattProperty.WRITE_NO_RESPONSE in properties
}

data class GattService(val uuid: UUID, val characteristics: List<GattCharacteristic>)

/**
 * A (round 2): the platform-neutral BLE link a [GattUartTransport] talks through. Android implements it on
 * BluetoothGatt (:core:ble, strict operation queue); tests use FakeGattLink, debug builds a demo link.
 * All calls are suspending and complete when the GATT operation completed. A link is used for one connection.
 */
interface GattLink {
    /** Connects (LE transport) and negotiates the MTU. Throws an IOException ([ObdError.BleGattError]) on failure. */
    suspend fun connect()

    /** Service discovery; the result is the device's GATT table. */
    suspend fun discover(): List<GattService>

    /** Subscribes to [characteristic] (notify, or indicate when it can't notify): writes the CCCD descriptor. */
    suspend fun enableNotifications(service: UUID, characteristic: UUID)

    /** One GATT write of at most [mtu] - 3 bytes; [withResponse] = WRITE_TYPE_DEFAULT, else WRITE_NO_RESPONSE. */
    suspend fun write(service: UUID, characteristic: UUID, data: ByteArray, withResponse: Boolean)

    /**
     * Values of the subscribed characteristic in arrival order. Single collector; values are buffered from
     * [connect] on, so nothing that arrives before the collector starts (or while a write is in flight) is lost.
     * Completes after [close]; fails with an IOException when the link drops.
     */
    val notifications: Flow<ByteArray>

    /** Current ATT MTU (23 until a larger one was negotiated). */
    val mtu: Int

    suspend fun close()
}

object GattUuids {
    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    /** 16-bit SIG short form to the full 128-bit UUID. */
    fun uuid16(short: Int): UUID = UUID.fromString("%08x".format(short) + BASE_SUFFIX)

    /** The 16-bit value of a SIG-based UUID, or null for vendor 128-bit UUIDs. */
    fun shortOf(uuid: UUID): Int? {
        val text = uuid.toString()
        return if (text.endsWith(BASE_SUFFIX) && text.startsWith("0000")) text.substring(4, 8).toInt(16) else null
    }

    /** Client Characteristic Configuration descriptor (enables notifications/indications). */
    val CCCD: UUID = uuid16(0x2902)

    val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_RX_WRITE: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_TX_NOTIFY: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    /** Services worth scanning for (the advertised UUIDs of the tiers 1-4). */
    val UART_SERVICES: List<UUID> = listOf(uuid16(0xFFF0), uuid16(0x18F0), uuid16(0xFFE0), NUS_SERVICE)
}

/** Which rule picked the pair; see [GattUartSelector]. */
enum class UartTier { FFF0, VGATE_18F0, HM10_FFE0, NORDIC_UART, GENERIC }

/** The characteristic pair that carries the ELM327 byte stream. [notify] and [write] may be the same characteristic. */
data class GattUartPair(
    val service: UUID,
    val notify: UUID,
    val write: UUID,
    val writeWithoutResponse: Boolean,
    val tier: UartTier,
) {
    /** True when [services] still has this pair with the needed properties (cached pair still valid). */
    fun isIn(services: List<GattService>): Boolean {
        val chars = services.firstOrNull { it.uuid == service }?.characteristics ?: return false
        val n = chars.firstOrNull { it.uuid == notify } ?: return false
        val w = chars.firstOrNull { it.uuid == write } ?: return false
        return n.canNotify && w.canWrite && (!writeWithoutResponse || GattProperty.WRITE_NO_RESPONSE in w.properties)
    }

    /** Text form for caches: "service,notify,write,nr|r,TIER". */
    fun encode(): String = listOf(service, notify, write, if (writeWithoutResponse) "nr" else "r", tier.name).joinToString(",")

    companion object {
        fun parse(text: String): GattUartPair? = runCatching {
            val p = text.split(',')
            if (p.size != 5) return null
            GattUartPair(UUID.fromString(p[0]), UUID.fromString(p[1]), UUID.fromString(p[2]), p[3] == "nr", UartTier.valueOf(p[4]))
        }.getOrNull()
    }
}

/**
 * Picks the serial characteristics of a BLE OBD adapter in tiers (round 2 research A_BLE, sources: OBDLink CX notes
 * https://support.obdlink.com/support/solutions/articles/43000746707 , Viecar 4.0
 * https://malykh.blogspot.com/2021/01/ble-elm327-viecar-40-bluetooth-40.html , eigger/espcomponents ble_elm327,
 * obd2-core ble.rs):
 * 1. FFF0: FFF1 notify, FFF2 write (OBDLink CX, Viecar 4.0, many clones);
 * 2. 18F0: 2AF0 notify, 2AF1 write (Vgate iCar / vLinker);
 * 3. FFE0: FFE1 notify + write (HM-10 style modules);
 * 4. Nordic UART 6E400001: 6E400003 notify, 6E400002 write;
 * 5. any service outside the SIG-assigned range and the OTA services with a notifiable and a writable characteristic.
 * How to calibrate: when a real adapter lands on tier 5 or on the wrong pair, read its table in nRF Connect and add a tier.
 */
object GattUartSelector {
    private val u = GattUuids::uuid16

    /** SUOTA (Dialog, used by OBDLink CX for firmware updates) and Nordic DFU: never a data channel. */
    private val OTA_SERVICES = setOf(u(0xFEF5), u(0xFE59))

    fun select(services: List<GattService>): GattUartPair? =
        tier(services, u(0xFFF0), u(0xFFF1), u(0xFFF2), UartTier.FFF0)
            ?: tier(services, u(0x18F0), u(0x2AF0), u(0x2AF1), UartTier.VGATE_18F0)
            ?: tier(services, u(0xFFE0), u(0xFFE1), u(0xFFE1), UartTier.HM10_FFE0)
            ?: tier(services, GattUuids.NUS_SERVICE, GattUuids.NUS_TX_NOTIFY, GattUuids.NUS_RX_WRITE, UartTier.NORDIC_UART)
            ?: generic(services)

    private fun tier(services: List<GattService>, service: UUID, notify: UUID, write: UUID, tier: UartTier): GattUartPair? {
        val chars = services.firstOrNull { it.uuid == service }?.characteristics ?: return null
        val n = chars.firstOrNull { it.uuid == notify && it.canNotify } ?: return null
        val w = chars.firstOrNull { it.uuid == write && it.canWrite } ?: return null
        return GattUartPair(service, n.uuid, w.uuid, GattProperty.WRITE_NO_RESPONSE in w.properties, tier)
    }

    private fun generic(services: List<GattService>): GattUartPair? = services
        .filter { s -> s.uuid !in OTA_SERVICES && GattUuids.shortOf(s.uuid)?.let { it in 0x1800..0x18FF } != true }
        .firstNotNullOfOrNull { s ->
            val n = s.characteristics.firstOrNull { it.canNotify } ?: return@firstNotNullOfOrNull null
            // Prefer a separate write characteristic (RX/TX pairs); fall back to the notifying one (single-char UARTs).
            val w = s.characteristics.firstOrNull { it.canWrite && it.uuid != n.uuid } ?: n.takeIf { it.canWrite }
                ?: return@firstNotNullOfOrNull null
            GattUartPair(s.uuid, n.uuid, w.uuid, GattProperty.WRITE_NO_RESPONSE in w.properties, UartTier.GENERIC)
        }
}

/**
 * How large one GATT write may be.
 * - [CONSERVATIVE]: min(MTU, 23) - 3 = 20 bytes. Default for every adapter until proven otherwise: some clones accept a
 *   517 MTU on Android 14+ (the stack requests 517 on the first requestMtu call) and then truncate longer writes
 *   (round 2 research A_BLE, https://developer.android.com/about/versions/14/behavior-changes-all#mtu-set-to-517).
 *   ELM327 commands are well under 20 bytes, so this costs nothing in normal polling.
 * - [NEGOTIATED]: MTU - 3, for adapters confirmed to handle it.
 * How to calibrate: on a real adapter send a long AT command (e.g. ATSH + ATCRA in one traffic log) with NEGOTIATED and
 * check the echo is complete; only then whitelist that adapter.
 */
enum class BleWriteLimit {
    CONSERVATIVE, NEGOTIATED;

    fun chunkSize(mtu: Int): Int = when (this) {
        CONSERVATIVE -> minOf(mtu, ATT_MIN_MTU) - ATT_HEADER
        NEGOTIATED -> maxOf(mtu, ATT_MIN_MTU) - ATT_HEADER
    }

    companion object {
        const val ATT_MIN_MTU = 23
        const val ATT_HEADER = 3
    }
}

/**
 * ELM327 over a GATT serial pair. Notifications go into a buffer; [read] waits until at least one chunk arrived,
 * [readAvailable] takes what is there without waiting; [write] cuts the command into chunks of [writeLimit] and uses
 * WRITE_NO_RESPONSE when the characteristic supports it.
 * [cachedPair] (from a previous connection to the same device) is reused when the device still has it, otherwise the
 * tiers of [GattUartSelector] decide; [onPairChosen] receives the pair in use. Android still has to discover services
 * before characteristics can be used, so discovery always runs; the cache only skips the selection.
 */
class GattUartTransport(
    private val link: GattLink,
    private val cachedPair: GattUartPair? = null,
    private val onPairChosen: (GattUartPair) -> Unit = {},
    private val writeLimit: BleWriteLimit = BleWriteLimit.CONSERVATIVE,
) : ObdTransport {
    // Unconfined: the collector runs in the thread that delivers the notification (binder thread or test scheduler).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    private var collector: Job? = null

    @Volatile
    private var pair: GattUartPair? = null

    @Volatile
    private var linkDown = false

    /** The pair in use (null before [open]). */
    val uartPair: GattUartPair? get() = pair

    override suspend fun open() {
        try {
            link.connect()
            val services = link.discover()
            val chosen = cachedPair?.takeIf { it.isIn(services) } ?: GattUartSelector.select(services)
                ?: throw ObdIoException(ObdError.BleNoUartService)
            pair = chosen
            collector = scope.launch {
                try {
                    link.notifications.collect { chunks.send(it) }
                    linkDown = true
                    chunks.close(ObdIoException(ObdError.BleDisconnected))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    linkDown = true
                    chunks.close(e as? IOException ?: ObdIoException(ObdError.BleDisconnected, e))
                }
            }
            link.enableNotifications(chosen.service, chosen.notify)
            onPairChosen(chosen)
        } catch (e: Exception) {
            runCatching { close() }
            throw e
        }
    }

    override suspend fun write(data: ByteArray) {
        val p = pair ?: throw ObdIoException(ObdError.NotOpen)
        if (linkDown) throw ObdIoException(ObdError.BleDisconnected)
        val size = writeLimit.chunkSize(link.mtu).coerceAtLeast(1)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + size, data.size)
            try {
                link.write(p.service, p.write, data.copyOfRange(offset, end), withResponse = !p.writeWithoutResponse)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw if (e is ObdIoException) e else ObdIoException(ObdError.BleDisconnected, e)
            }
            offset = end
        }
    }

    override suspend fun read(): ByteArray = chunks.receive()

    override suspend fun readAvailable(): ByteArray? = chunks.tryReceive().getOrNull()

    override suspend fun close() {
        collector?.cancel()
        scope.cancel()
        chunks.close()
        link.close()
    }
}
