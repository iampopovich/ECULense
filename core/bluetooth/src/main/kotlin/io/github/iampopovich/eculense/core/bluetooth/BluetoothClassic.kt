package io.github.iampopovich.eculense.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ObdTransport
import io.github.iampopovich.eculense.core.obd.StreamTransport
import io.github.iampopovich.eculense.core.obd.TransportFactory
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Разрешение BLUETOOTH_CONNECT запрашивает экран подключения до вызова discover()/connect(). */
@SuppressLint("MissingPermission")
internal class BluetoothClassicTransport(
    private val adapter: BluetoothAdapter,
    private val address: String,
) : StreamTransport() {
    private var socket: BluetoothSocket? = null

    override fun openStreams(): Pair<InputStream, OutputStream> {
        adapter.cancelDiscovery()
        val s = adapter.getRemoteDevice(address).createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        s.connect()
        socket = s
        return s.inputStream to s.outputStream
    }

    override fun closeStreams() {
        socket?.close()
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

@SuppressLint("MissingPermission")
internal class BluetoothClassicFactory(context: Context) : TransportFactory {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val obdHints = listOf("OBD", "ELM", "VLINK", "V-LINK", "VGATE", "ICAR", "KONNWEI", "VIECAR")

    override val kind = KIND

    override suspend fun discover(): List<AdapterEndpoint> {
        val a = adapter ?: return emptyList()
        if (!a.isEnabled) return emptyList()
        return a.bondedDevices.orEmpty()
            .map { AdapterEndpoint(KIND, it.address, it.name ?: it.address) }
            .sortedByDescending { e -> obdHints.any { e.label.uppercase().contains(it) } }
    }

    override fun create(endpoint: AdapterEndpoint): ObdTransport =
        BluetoothClassicTransport(requireNotNull(adapter) { "На устройстве нет Bluetooth" }, endpoint.address)

    companion object {
        const val KIND = "bt"
    }
}

val bluetoothModule = module {
    single(named(BluetoothClassicFactory.KIND)) { BluetoothClassicFactory(androidContext()) } bind TransportFactory::class
}
