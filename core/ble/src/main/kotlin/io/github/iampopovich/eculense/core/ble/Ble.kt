package io.github.iampopovich.eculense.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.AdapterScanner
import io.github.iampopovich.eculense.core.obd.BleScanRules
import io.github.iampopovich.eculense.core.obd.BleWriteLimit
import io.github.iampopovich.eculense.core.obd.GattUartPair
import io.github.iampopovich.eculense.core.obd.GattUartTransport
import io.github.iampopovich.eculense.core.obd.ObdError
import io.github.iampopovich.eculense.core.obd.ObdException
import io.github.iampopovich.eculense.core.obd.ObdTransport
import io.github.iampopovich.eculense.core.obd.ScanStart
import io.github.iampopovich.eculense.core.obd.ScannedAdapter
import io.github.iampopovich.eculense.core.obd.ScannedDevice
import io.github.iampopovich.eculense.core.obd.TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE scan for adapters while the Connect screen is visible. The scan has no ScanFilter on purpose: many BLE clones
 * advertise only a name, not their FFF0/18F0/FFE0/NUS service, so a filter by service UUID would hide them. Results are
 * classified in [BleScanRules] instead (tier service advertised or OBD-like name = likely adapter; the rest only with
 * «show all»). A scan stops by itself after the requested duration, or when the screen leaves ([stop]).
 */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) : AdapterScanner {
    override val kind = BleTransportFactory.KIND

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val devices = ConcurrentHashMap<String, ScannedDevice>()
    private val _found = MutableStateFlow<List<ScannedAdapter>>(emptyList())
    override val found: StateFlow<List<ScannedAdapter>> = _found.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    override val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timer: Job? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = add(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::add)

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            _scanning.value = false
        }
    }

    private fun add(result: ScanResult) {
        val record = result.scanRecord
        val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull()
        val address = result.device.address ?: return
        devices[address] = ScannedDevice(address, name, record?.serviceUuids.orEmpty().map { it.uuid }, result.rssi)
        _found.value = BleScanRules.toAdapters(kind, devices.values)
    }

    private fun hasScanPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    @Synchronized
    override fun start(durationMs: Long): ScanStart {
        if (_scanning.value) return ScanStart.ALREADY_RUNNING
        val adapter = manager?.adapter ?: return ScanStart.UNSUPPORTED
        if (!adapter.isEnabled) return ScanStart.BLUETOOTH_OFF
        if (!hasScanPermission()) return ScanStart.NO_PERMISSION
        val scanner = adapter.bluetoothLeScanner ?: return ScanStart.UNSUPPORTED
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            devices.clear()
            _found.value = emptyList()
            scanner.startScan(null, settings, callback)
            _scanning.value = true
            Log.i(TAG, "scan started for $durationMs ms")
            timer?.cancel()
            timer = scope.launch {
                delay(durationMs)
                stop()
            }
            ScanStart.STARTED
        } catch (e: SecurityException) {
            ScanStart.NO_PERMISSION
        } catch (e: IllegalStateException) {
            // Bluetooth turned off between the check and the call.
            ScanStart.BLUETOOTH_OFF
        }
    }

    @Synchronized
    override fun stop() {
        timer?.cancel()
        timer = null
        if (!_scanning.value) return
        _scanning.value = false
        runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(callback) }
        Log.i(TAG, "scan stopped, ${devices.size} devices")
    }

    private companion object {
        const val TAG = "EcuBle"
    }
}

/**
 * Bluetooth LE adapters (label «Bluetooth LE»). Devices come from [BleScanner] (the Connect screen's BLE section), so
 * [discover] lists nothing here; a remembered endpoint (auto-connect) connects without a scan. The characteristic pair
 * chosen on the first connection is cached per address for the process lifetime (reconnects skip the selection; Android
 * still has to discover services before characteristics can be used).
 */
class BleTransportFactory(private val context: Context) : TransportFactory {
    private val pairs = ConcurrentHashMap<String, GattUartPair>()

    override val kind = KIND

    override suspend fun discover(): List<AdapterEndpoint> = emptyList()

    override fun create(endpoint: AdapterEndpoint): ObdTransport {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw ObdException(ObdError.NoBluetooth)
        val address = endpoint.address
        return GattUartTransport(
            AndroidGattLink(context.applicationContext, adapter, address),
            cachedPair = pairs[address],
            onPairChosen = { pairs[address] = it },
            writeLimit = BleWriteLimit.CONSERVATIVE,
        )
    }

    companion object {
        const val KIND = "ble"
    }
}

val bleModule = module {
    single(named(BleTransportFactory.KIND)) { BleTransportFactory(androidContext()) } bind TransportFactory::class
    single<AdapterScanner> { BleScanner(androidContext()) }
}
