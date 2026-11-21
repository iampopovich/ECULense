package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException
import java.util.UUID

/**
 * GATT tables of real BLE OBD adapters (round 2 research, A_BLE): OBDLink CX notes
 * (https://support.obdlink.com/support/solutions/articles/43000746707-obdlink-cx-adapter-notes), Viecar 4.0
 * (https://malykh.blogspot.com/2021/01/ble-elm327-viecar-40-bluetooth-40.html), Vgate iCar/vLinker service list,
 * HM-10 modules (FFE0/FFE1) and the Nordic UART Service. Only UUIDs and properties; values are illustrative.
 */
object GattTables {
    private val R = setOf(GattProperty.READ)
    private val N = setOf(GattProperty.NOTIFY)
    private val W = setOf(GattProperty.WRITE)
    private val WN = setOf(GattProperty.WRITE, GattProperty.WRITE_NO_RESPONSE)
    private val RWNN = setOf(GattProperty.READ, GattProperty.WRITE_NO_RESPONSE, GattProperty.NOTIFY)

    fun uuid16(short: Int): UUID = GattUuids.uuid16(short)
    private fun c(short: Int, props: Set<GattProperty>) = GattCharacteristic(uuid16(short), props)
    private fun s(short: Int, vararg chars: GattCharacteristic) = GattService(uuid16(short), chars.toList())

    private val GAP = s(0x1800, c(0x2A00, R), c(0x2A01, R))
    private val GATT = s(0x1801, c(0x2A05, setOf(GattProperty.INDICATE)))
    private val DEVICE_INFO = s(0x180A, c(0x2A29, R), c(0x2A24, R), c(0x2A26, R))

    /** OBDLink CX: device info, FFF0 serial (FFF2 accepts write with and without response), Dialog SUOTA FEF5. */
    val OBDLINK_CX = listOf(
        GAP, GATT, DEVICE_INFO,
        s(0xFFF0, c(0xFFF1, N), c(0xFFF2, WN)),
        GattService(
            uuid16(0xFEF5),
            listOf(
                GattCharacteristic(UUID.fromString("8082caa8-41a6-4021-91c6-56f9b954cc34"), setOf(GattProperty.READ, GattProperty.WRITE)),
                GattCharacteristic(UUID.fromString("724249f0-5ec3-4b5f-8804-42345af08651"), setOf(GattProperty.READ, GattProperty.WRITE)),
                GattCharacteristic(UUID.fromString("5f78df94-798c-46f5-990a-b3eb6a065c88"), setOf(GattProperty.READ, GattProperty.NOTIFY)),
            ),
        ),
    )

    /** Vgate iCar/vLinker BLE: alert/link-loss/tx-power services, 18F0 serial (2AF0 notify, 2AF1 write), vendor E781... */
    val VGATE = listOf(
        GAP, GATT,
        s(0x1803, c(0x2A06, setOf(GattProperty.READ, GattProperty.WRITE))),
        s(0x1802, c(0x2A06, setOf(GattProperty.WRITE_NO_RESPONSE))),
        s(0x1811, c(0x2A47, R), c(0x2A46, N), c(0x2A44, W)),
        s(0x1804, c(0x2A07, R)),
        s(0x18F0, c(0x2AF0, setOf(GattProperty.NOTIFY, GattProperty.INDICATE)), c(0x2AF1, WN)),
        GattService(
            UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"),
            listOf(GattCharacteristic(UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"), setOf(GattProperty.READ, GattProperty.WRITE, GattProperty.WRITE_NO_RESPONSE, GattProperty.NOTIFY, GattProperty.INDICATE))),
        ),
    )

    /** Viecar 4.0 (dual mode, BLE side): FFF0 with FFF1 notify, FFF2 write. */
    val VIECAR = listOf(GAP, GATT, DEVICE_INFO, s(0xFFF0, c(0xFFF1, setOf(GattProperty.READ, GattProperty.NOTIFY)), c(0xFFF2, W)))

    /** HM-10 style modules: one characteristic FFE1 for notify and write. */
    val HM10 = listOf(GAP, GATT, s(0xFFE0, c(0xFFE1, RWNN)))

    /** Nordic UART Service. */
    val NORDIC_UART = listOf(
        GAP, GATT,
        GattService(
            GattUuids.NUS_SERVICE,
            listOf(GattCharacteristic(GattUuids.NUS_RX_WRITE, WN), GattCharacteristic(GattUuids.NUS_TX_NOTIFY, N)),
        ),
    )

    /** An unknown vendor UART: none of the known tiers, one writable + notifiable pair in a custom service. */
    val UNKNOWN_VENDOR = listOf(
        GAP, GATT, DEVICE_INFO,
        GattService(
            UUID.fromString("0000abf0-0000-1000-8000-00805f9b34fb"),
            listOf(
                GattCharacteristic(UUID.fromString("0000abf2-0000-1000-8000-00805f9b34fb"), setOf(GattProperty.READ, GattProperty.NOTIFY)),
                GattCharacteristic(UUID.fromString("0000abf1-0000-1000-8000-00805f9b34fb"), setOf(GattProperty.WRITE_NO_RESPONSE)),
            ),
        ),
    )

    /** A fitness band: only standard services, no serial pair. */
    val NOT_AN_ADAPTER = listOf(GAP, GATT, DEVICE_INFO, s(0x180F, c(0x2A19, setOf(GattProperty.READ, GattProperty.NOTIFY))))
}

/**
 * Scripted BLE ELM327 behind a [GattLink]: commands written to the write characteristic are answered from [replies]
 * (FakeCar style, "reply\r\r>"), split into notifications by [split] (default 20 bytes = ATT MTU 23 - 3).
 * Replies are emitted inside write(), i.e. before the write call returns (real adapters do that too).
 */
class FakeGattLink(
    private val services: List<GattService> = GattTables.VIECAR,
    override var mtu: Int = 23,
    private val replies: Map<String, String> = FakeCar.replies,
    var split: (ByteArray) -> List<ByteArray> = { bytes -> bytes.toList().chunked(20).map { it.toByteArray() } },
    /** connect() fails this many times with a GATT error before it succeeds (status 133 retries). */
    private var connectFailures: Int = 0,
    /** Adapter answer time; > 0 lets polling advance virtual time in tests. */
    var latencyMs: Long = 0,
) : GattLink {
    data class Write(val characteristic: UUID, val bytes: ByteArray, val withResponse: Boolean)

    val writes = mutableListOf<Write>()
    val sentCommands = mutableListOf<String>()
    var enabled: Pair<UUID, UUID>? = null
        private set
    var discoverCalls = 0
        private set
    var connected = false
        private set
    var closed = false
        private set

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    private val pending = StringBuilder()

    override val notifications: Flow<ByteArray> = channel.receiveAsFlow()

    override suspend fun connect() {
        if (connectFailures > 0) {
            connectFailures--
            throw ObdIoException(ObdError.BleGattError("connect", 133))
        }
        connected = true
    }

    override suspend fun discover(): List<GattService> {
        discoverCalls++
        return services
    }

    override suspend fun enableNotifications(service: UUID, characteristic: UUID) {
        enabled = service to characteristic
    }

    override suspend fun write(service: UUID, characteristic: UUID, data: ByteArray, withResponse: Boolean) {
        if (closed || channel.isClosedForSend) throw ObdIoException(ObdError.BleDisconnected)
        writes += Write(characteristic, data, withResponse)
        pending.append(data.decodeToString())
        while (true) {
            val end = pending.indexOf("\r")
            if (end < 0) break
            val command = pending.substring(0, end).trim()
            pending.delete(0, end + 1)
            if (command.isEmpty()) continue
            sentCommands += command
            if (latencyMs > 0) delay(latencyMs)
            emit("${replies[command] ?: "?"}\r\r>".encodeToByteArray())
        }
    }

    /** Sends [bytes] as notifications, split by [split]. */
    fun emit(bytes: ByteArray) {
        split(bytes).forEach { channel.trySend(it) }
    }

    /** The radio link dropped: the notification flow fails with an IOException, writes fail. */
    fun drop() {
        channel.close(IOException("GATT link lost"))
    }

    override suspend fun close() {
        closed = true
        channel.close()
    }
}
