package io.github.iampopovich.eculense.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import io.github.iampopovich.eculense.core.obd.GattCharacteristic
import io.github.iampopovich.eculense.core.obd.GattLink
import io.github.iampopovich.eculense.core.obd.GattProperty
import io.github.iampopovich.eculense.core.obd.GattService
import io.github.iampopovich.eculense.core.obd.GattUuids
import io.github.iampopovich.eculense.core.obd.ObdError
import io.github.iampopovich.eculense.core.obd.ObdIoException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * [GattLink] on the platform BluetoothGatt. Android allows one outstanding GATT operation per connection; a second
 * call while one runs fails silently, so every operation goes through [ops] (one at a time) and waits for its own
 * callback ([Pending] carries the operation kind; stray callbacks of another kind are ignored).
 *
 * - connect: `connectGatt(autoConnect = false, TRANSPORT_LE)`; status 133 (GATT_ERROR, common on first connects and on
 *   clones) and other failures retry [CONNECT_ATTEMPTS] times with backoff 0.5 / 1 / 2 s, closing the BluetoothGatt
 *   in between (a leaked gatt keeps the next connect at 133).
 * - MTU: `requestMtu(517)` once right after connecting. Android 14+ requests 517 on the first call and ignores later
 *   ones (https://developer.android.com/about/versions/14/behavior-changes-all#mtu-set-to-517); a failure keeps 23.
 * - Service Changed (API 31+) and any disconnect end the notification flow with an IOException: the connection
 *   manager then reconnects with a fresh link and a fresh discovery.
 * - onCharacteristicChanged: API 33+ calls both the new and the deprecated callback; only one of them is used.
 * Permissions (BLUETOOTH_CONNECT on API 31+) are requested by the Connect screen before connecting.
 */
@SuppressLint("MissingPermission")
internal class AndroidGattLink(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val address: String,
    private val opTimeoutMs: Long = 10_000,
    private val connectTimeoutMs: Long = 15_000,
) : GattLink {
    private enum class Kind { CONNECT, DISCOVER, MTU, DESCRIPTOR, WRITE }

    private class Pending(val kind: Kind, val result: CompletableDeferred<Int>)

    private val ops = Mutex()

    @Volatile
    private var pending: Pending? = null

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var connected = false

    @Volatile
    private var closed = false

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val notifications: Flow<ByteArray> = channel.receiveAsFlow()

    @Volatile
    override var mtu: Int = 23
        private set

    private fun finish(kind: Kind, status: Int) {
        val p = pending ?: return
        if (p.kind == kind) p.result.complete(status)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connection state $newState status $status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                finish(Kind.CONNECT, status)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasConnected = connected
                connected = false
                // A pending operation of any kind fails now (connect gets the real status, e.g. 133).
                pending?.result?.complete(if (status == BluetoothGatt.GATT_SUCCESS) STATUS_DISCONNECTED else status)
                if (wasConnected) channel.close(ObdIoException(ObdError.BleDisconnected))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(Kind.DISCOVER, status)

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@AndroidGattLink.mtu = mtu
            Log.i(TAG, "mtu $mtu status $status")
            finish(Kind.MTU, status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = finish(Kind.DESCRIPTOR, status)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = finish(Kind.WRITE, status)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            channel.trySend(value.copyOf())
        }

        @Deprecated("API < 33 path")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            c.value?.let { channel.trySend(it.copyOf()) }
        }

        override fun onServiceChanged(g: BluetoothGatt) {
            Log.i(TAG, "service changed: dropping the link to rediscover")
            channel.close(ObdIoException(ObdError.BleDisconnected))
        }
    }

    /** Runs one GATT operation: [start] issues it (false = rejected), then waits for the callback of [kind]. */
    private suspend fun op(kind: Kind, name: String, timeoutMs: Long = opTimeoutMs, start: () -> Boolean) = ops.withLock {
        if (closed) throw ObdIoException(ObdError.BleDisconnected)
        val p = Pending(kind, CompletableDeferred())
        pending = p
        try {
            if (!start()) throw ObdIoException(ObdError.BleGattError(name, STATUS_REJECTED))
            val status = withTimeoutOrNull(timeoutMs) { p.result.await() } ?: throw ObdIoException(ObdError.BleGattError(name, STATUS_TIMEOUT))
            if (status != BluetoothGatt.GATT_SUCCESS) throw ObdIoException(ObdError.BleGattError(name, status))
        } finally {
            pending = null
        }
    }

    override suspend fun connect() {
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw ObdIoException(ObdError.BleGattError("connect", STATUS_REJECTED), e)
        }
        var last: Exception? = null
        for (attempt in 1..CONNECT_ATTEMPTS) {
            try {
                op(Kind.CONNECT, "connect", connectTimeoutMs) {
                    gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    gatt != null
                }
                last = null
                break
            } catch (e: ObdIoException) {
                last = e
                Log.w(TAG, "connect attempt $attempt failed: ${e.message}")
                runCatching { gatt?.close() }
                gatt = null
                if (attempt < CONNECT_ATTEMPTS) delay(CONNECT_BACKOFF_MS shl (attempt - 1))
            }
        }
        last?.let { throw it }
        // Larger MTU once; failure is fine (writes are cut to 20 bytes by default anyway).
        runCatching { op(Kind.MTU, "mtu") { gatt?.requestMtu(REQUESTED_MTU) == true } }
            .onFailure { Log.i(TAG, "mtu request failed: ${it.message}") }
    }

    override suspend fun discover(): List<GattService> {
        op(Kind.DISCOVER, "discover", DISCOVER_TIMEOUT_MS) { gatt?.discoverServices() == true }
        val g = gatt ?: throw ObdIoException(ObdError.BleDisconnected)
        return g.services.orEmpty().map { s ->
            GattService(s.uuid, s.characteristics.orEmpty().map { c -> GattCharacteristic(c.uuid, properties(c.properties)) })
        }
    }

    private fun characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic =
        gatt?.getService(service)?.getCharacteristic(characteristic) ?: throw ObdIoException(ObdError.BleNoUartService)

    override suspend fun enableNotifications(service: UUID, characteristic: UUID) {
        val g = gatt ?: throw ObdIoException(ObdError.BleDisconnected)
        val c = characteristic(service, characteristic)
        if (!g.setCharacteristicNotification(c, true)) throw ObdIoException(ObdError.BleGattError("notify", STATUS_REJECTED))
        // Some clones notify without a CCCD descriptor: then setCharacteristicNotification alone is all we can do.
        val cccd = c.getDescriptor(GattUuids.CCCD) ?: return
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        op(Kind.DESCRIPTOR, "notify") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
    }

    override suspend fun write(service: UUID, characteristic: UUID, data: ByteArray, withResponse: Boolean) {
        val g = gatt ?: throw ObdIoException(ObdError.BleDisconnected)
        if (!connected) throw ObdIoException(ObdError.BleDisconnected)
        val c = characteristic(service, characteristic)
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        // The stack can report "busy" right after a no-response write: retry a few times before giving up.
        var tries = 0
        while (true) {
            try {
                op(Kind.WRITE, "write") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
                    } else {
                        c.writeType = type
                        @Suppress("DEPRECATION")
                        c.value = data
                        @Suppress("DEPRECATION")
                        g.writeCharacteristic(c)
                    }
                }
                return
            } catch (e: ObdIoException) {
                val busy = (e.error as? ObdError.BleGattError)?.status == STATUS_REJECTED && connected
                if (!busy || ++tries >= WRITE_BUSY_RETRIES) throw e
                delay(WRITE_BUSY_DELAY_MS)
            }
        }
    }

    override suspend fun close() {
        closed = true
        pending?.result?.complete(STATUS_DISCONNECTED)
        val g = gatt
        gatt = null
        runCatching { g?.disconnect() }
        runCatching { g?.close() }
        connected = false
        channel.close()
    }

    companion object {
        private const val TAG = "EcuBle"
        const val REQUESTED_MTU = 517
        const val CONNECT_ATTEMPTS = 3
        const val CONNECT_BACKOFF_MS = 500L
        const val DISCOVER_TIMEOUT_MS = 15_000L
        const val WRITE_BUSY_RETRIES = 5
        const val WRITE_BUSY_DELAY_MS = 20L

        /** Our own codes next to Android's GATT statuses (which are 0..0xFF). */
        const val STATUS_REJECTED = -1
        const val STATUS_TIMEOUT = -2
        const val STATUS_DISCONNECTED = -3

        fun properties(bits: Int): Set<GattProperty> = buildSet {
            if (bits and BluetoothGattCharacteristic.PROPERTY_READ != 0) add(GattProperty.READ)
            if (bits and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add(GattProperty.WRITE)
            if (bits and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add(GattProperty.WRITE_NO_RESPONSE)
            if (bits and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add(GattProperty.NOTIFY)
            if (bits and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add(GattProperty.INDICATE)
        }
    }
}
