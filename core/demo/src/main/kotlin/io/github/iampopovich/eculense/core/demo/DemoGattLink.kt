package io.github.iampopovich.eculense.core.demo

import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.EndpointLabel
import io.github.iampopovich.eculense.core.obd.GattCharacteristic
import io.github.iampopovich.eculense.core.obd.GattLink
import io.github.iampopovich.eculense.core.obd.GattProperty
import io.github.iampopovich.eculense.core.obd.GattService
import io.github.iampopovich.eculense.core.obd.GattUartTransport
import io.github.iampopovich.eculense.core.obd.GattUuids
import io.github.iampopovich.eculense.core.obd.ObdError
import io.github.iampopovich.eculense.core.obd.ObdIoException
import io.github.iampopovich.eculense.core.obd.ObdTransport
import io.github.iampopovich.eculense.core.obd.TransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

/**
 * A simulated BLE adapter: [DemoTransport] behind a GATT table like the Viecar 4.0 (FFF0: FFF1 notify, FFF2 write).
 * Replies come back as notifications of at most [chunk] bytes (ATT MTU 23 - 3) cut at random points, so the whole
 * app flow exercises [GattUartTransport] (buffering, prompt split across notifications) on the phone.
 */
class DemoGattLink(
    private val delegate: ObdTransport,
    private val random: Random = Random.Default,
    private val chunk: Int = 20,
    scope: CoroutineScope? = null,
) : GattLink {
    private val ownsScope = scope == null
    private val scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    private var pump: Job? = null

    @Volatile
    private var closed = false

    override val notifications: Flow<ByteArray> = channel.receiveAsFlow()
    override val mtu: Int = 23

    override suspend fun connect() {
        delegate.open()
        pump = scope.launch {
            try {
                while (true) split(delegate.read()).forEach { channel.send(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                channel.close(ObdIoException(ObdError.BleDisconnected, e))
            }
        }
    }

    override suspend fun discover(): List<GattService> = TABLE

    override suspend fun enableNotifications(service: UUID, characteristic: UUID) = Unit

    override suspend fun write(service: UUID, characteristic: UUID, data: ByteArray, withResponse: Boolean) {
        if (closed) throw ObdIoException(ObdError.BleDisconnected)
        require(data.size <= mtu - 3) { "GATT write of ${data.size} bytes exceeds MTU $mtu" }
        delegate.write(data)
    }

    /** Random cut points, each piece 1..[chunk] bytes. */
    internal fun split(bytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val size = minOf(bytes.size - offset, 1 + random.nextInt(chunk))
            out += bytes.copyOfRange(offset, offset + size)
            offset += size
        }
        return out
    }

    override suspend fun close() {
        closed = true
        pump?.cancel()
        channel.close()
        runCatching { delegate.close() }
        if (ownsScope) scope.cancel()
    }

    companion object {
        val TABLE = listOf(
            GattService(GattUuids.uuid16(0x180A), listOf(GattCharacteristic(GattUuids.uuid16(0x2A29), setOf(GattProperty.READ)))),
            GattService(
                GattUuids.uuid16(0xFFF0),
                listOf(
                    GattCharacteristic(GattUuids.uuid16(0xFFF1), setOf(GattProperty.READ, GattProperty.NOTIFY)),
                    GattCharacteristic(GattUuids.uuid16(0xFFF2), setOf(GattProperty.WRITE, GattProperty.WRITE_NO_RESPONSE)),
                ),
            ),
        )
    }
}

/** «Demo BLE»: the demo car over the BLE code path (debug builds only, nothing persisted: kind starts with "demo"). */
class BleDemoTransportFactory(
    private val clock: () -> Long = System::currentTimeMillis,
    private val latencyMs: (String) -> Long = DemoTransport.Companion::defaultLatency,
    /** Scope of the notification pump; tests pass their own so virtual time stays consistent. */
    private val scope: CoroutineScope? = null,
) : TransportFactory {
    override val kind = KIND
    override suspend fun discover() = listOf(ENDPOINT)
    override fun create(endpoint: AdapterEndpoint): ObdTransport =
        GattUartTransport(DemoGattLink(DemoTransport(clock, latencyMs), scope = scope))

    companion object {
        const val KIND = "demo-ble"
        val ENDPOINT = AdapterEndpoint(KIND, "Virtual ELM327 · BLE FFF0 · 20-byte notifications", "Demo BLE (simulation)", EndpointLabel.DEMO_BLE)
    }
}
