package io.github.iampopovich.eculense.core.carscanner

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BrcWriter {
    private val out = ByteArrayOutputStream()

    fun header(vin: String = "", car: String = "Тестовая", profile: String = "Kia OBD-II / EOBD", adapter: String = "BT Test [00:11:22:33:44:55]", startTicks: Long) = apply {
        string(MAGIC); int(2); string(vin); string(car); string(profile); string(adapter); long(startTicks)
    }
    fun sensor(id: Int, full: String, short: String = full, format: Int) = apply { int(SENSOR_DEF); int(id); string(full); string(short); int(format) }
    fun point(seconds: Double, id: Int, value: Double) = apply { int(DATA_POINT); double(seconds); int(id); double(value) }
    fun gps(seconds: Double, lat: Double, lon: Double) = apply { int(GPS); double(seconds); double(lat); double(lon) }
    fun dtc(ticks: Long, json: String) = apply { int(DTC_EVENT); long(ticks); string(json) }
    fun raw(bytes: ByteArray) = apply { out.write(bytes) }
    fun bytes(): ByteArray = out.toByteArray()

    private fun le(size: Int, fill: ByteBuffer.() -> Unit) = out.write(ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array())
    private fun int(v: Int) = le(4) { putInt(v) }
    private fun long(v: Long) = le(8) { putLong(v) }
    private fun double(v: Double) = le(8) { putDouble(v) }

    /** Строка .NET BinaryWriter: длина в байтах 7-битными группами, затем UTF-8. */
    private fun string(s: String) {
        val bytes = s.encodeToByteArray()
        var n = bytes.size
        while (n >= 0x80) {
            out.write((n and 0x7F) or 0x80)
            n = n ushr 7
        }
        out.write(n)
        out.write(bytes)
    }
}
