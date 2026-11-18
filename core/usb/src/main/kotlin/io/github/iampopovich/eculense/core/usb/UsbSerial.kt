package io.github.iampopovich.eculense.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ObdTransport
import io.github.iampopovich.eculense.core.obd.SerialPortIo
import io.github.iampopovich.eculense.core.obd.SerialTransport
import io.github.iampopovich.eculense.core.obd.TransportFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.IOException
import kotlin.coroutines.resume

/** Адаптер порта usb-serial-for-android к платформенно-независимому SerialPortIo. */
internal class AndroidUsbSerialPort(
    private val usbManager: UsbManager,
    private val driver: UsbSerialDriver,
) : SerialPortIo {
    private var port: UsbSerialPort? = null

    override fun open() {
        val connection = usbManager.openDevice(driver.device) ?: throw IOException("Нет доступа к USB-устройству")
        port = driver.ports.first().also { it.open(connection) }
    }

    override fun setBaudRate(baud: Int) =
        requirePort().setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

    override fun write(data: ByteArray, timeoutMs: Int) = requirePort().write(data, timeoutMs)

    override fun read(buffer: ByteArray, timeoutMs: Int): Int = requirePort().read(buffer, timeoutMs)

    override fun close() {
        port?.close()
        port = null
    }

    private fun requirePort() = port ?: throw IOException("USB-порт не открыт")
}

/** Системный диалог «Разрешить ECULense доступ к устройству?» — до открытия порта. */
internal class UsbPermissions(private val context: Context, private val usbManager: UsbManager) {
    suspend fun ensure(device: UsbDevice) {
        if (usbManager.hasPermission(device)) return
        val granted = suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            val intent = Intent(ACTION).setPackage(context.packageName)
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
        }
        if (!granted) throw IOException("Доступ к USB-адаптеру не разрешён")
    }

    private companion object {
        const val ACTION = "io.github.iampopovich.eculense.USB_PERMISSION"
    }
}

/** Сначала разрешение, потом открытие порта; остальное делегируется SerialTransport. */
internal class PermissionGatedTransport(
    private val ensurePermission: suspend () -> Unit,
    private val delegate: ObdTransport,
) : ObdTransport by delegate {
    override suspend fun open() {
        ensurePermission()
        delegate.open()
    }
}

internal class UsbSerialFactory(private val context: Context) : TransportFactory {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val permissions = UsbPermissions(context, usbManager)

    override val kind = KIND

    override suspend fun discover(): List<AdapterEndpoint> =
        drivers().map { driver ->
            val device = driver.device
            val name = runCatching { device.productName }.getOrNull() ?: "%04X:%04X".format(device.vendorId, device.productId)
            AdapterEndpoint(KIND, device.deviceName, "USB: $name")
        }

    override fun create(endpoint: AdapterEndpoint): ObdTransport {
        val driver = drivers().firstOrNull { it.device.deviceName == endpoint.address }
            ?: throw IOException("USB-адаптер отключён")
        return PermissionGatedTransport(
            ensurePermission = { permissions.ensure(driver.device) },
            delegate = SerialTransport(AndroidUsbSerialPort(usbManager, driver)),
        )
    }

    private fun drivers(): List<UsbSerialDriver> = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

    companion object {
        const val KIND = "usb"
    }
}

val usbModule = module {
    single(named(UsbSerialFactory.KIND)) { UsbSerialFactory(androidContext()) } bind TransportFactory::class
}
