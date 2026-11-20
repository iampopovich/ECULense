package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.analytics.Vec3Sample
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** How the linear acceleration in a file was obtained. */
enum class AccelSource { LINEAR_ACCELERATION, ACCELEROMETER_MINUS_GRAVITY }

/** One recording's phone samples (wall-clock ms, device frame, m/s²). */
data class AccelRecording(val source: AccelSource, val startMs: Long, val linear: List<Vec3Sample>, val gravity: List<Vec3Sample>)

/**
 * E5-lite per-session sensor file `files/accel/<sessionId>.bin` (never in the readings table).
 *
 * Format v1, big-endian: header = magic `ECUA`, version byte, source byte ([AccelSource.ordinal]), start wall-clock
 * ms (long). Then 11-byte records: type byte ([LINEAR] or [GRAVITY]), ms since start (int), x/y/z as shorts in units
 * of 1/[SCALE] m/s² (resolution 0.002 m/s², range ±65 m/s² — a phone's linear acceleration in a car stays far below).
 * At 50 Hz plus gravity once per second that is ≈ 2.0 MB per hour (target < 3 MB). A truncated last record (process
 * killed mid-write) is ignored by the reader.
 */
class AccelFiles(val dir: File) {
    fun file(sessionId: Long): File = File(dir, "$sessionId$SUFFIX")
    fun exists(sessionId: Long): Boolean = file(sessionId).let { it.isFile && it.length() > HEADER_BYTES }

    fun delete(sessionId: Long) {
        file(sessionId).delete()
    }

    /** All session files with their ids. */
    fun list(): List<Pair<Long, File>> = dir.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(SUFFIX) }
        .mapNotNull { f -> f.name.removeSuffix(SUFFIX).toLongOrNull()?.let { it to f } }
        .sortedBy { it.first }

    fun totalBytes(): Long = list().sumOf { it.second.length() }

    /** Files whose session no longer exists (deleted with its car, a restore of an older backup). [keep] is never touched. */
    fun sweep(existing: Set<Long>, keep: Long? = null): Int =
        list().count { (id, f) -> id !in existing && id != keep && f.delete() }

    fun writer(sessionId: Long, source: AccelSource, startMs: Long): Writer {
        dir.mkdirs()
        return Writer(file(sessionId), source, startMs)
    }

    fun read(sessionId: Long): AccelRecording? = file(sessionId).takeIf { it.isFile }?.let(::read)

    /** Buffered writer; call from one thread. [close] flushes. */
    class Writer internal constructor(file: File, source: AccelSource, private val startMs: Long) : AutoCloseable {
        private val out = DataOutputStream(BufferedOutputStream(FileOutputStream(file, false), BUFFER_BYTES))
        var samples = 0L
            private set

        init {
            out.write(MAGIC)
            out.writeByte(VERSION)
            out.writeByte(source.ordinal)
            out.writeLong(startMs)
        }

        fun linear(tMs: Long, x: Float, y: Float, z: Float) = record(LINEAR, tMs, x, y, z)
        fun gravity(tMs: Long, x: Float, y: Float, z: Float) = record(GRAVITY, tMs, x, y, z)

        private fun record(type: Int, tMs: Long, x: Float, y: Float, z: Float) {
            val dt = tMs - startMs
            if (dt < 0 || dt > Int.MAX_VALUE) return
            out.writeByte(type)
            out.writeInt(dt.toInt())
            out.writeShort(pack(x))
            out.writeShort(pack(y))
            out.writeShort(pack(z))
            samples++
        }

        fun flush() = out.flush()

        override fun close() = out.close()
    }

    companion object {
        const val SUFFIX = ".bin"
        const val LINEAR = 1
        const val GRAVITY = 2
        const val SCALE = 500.0
        const val VERSION = 1
        const val RECORD_BYTES = 11
        const val HEADER_BYTES = 14
        private const val BUFFER_BYTES = 64 * 1024
        private val MAGIC = "ECUA".toByteArray()

        internal fun pack(v: Float): Int = (v * SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

        fun read(file: File): AccelRecording? = runCatching {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                val magic = ByteArray(4).also { inp.readFully(it) }
                if (!magic.contentEquals(MAGIC) || inp.readUnsignedByte() != VERSION) return null
                val source = AccelSource.entries.getOrNull(inp.readUnsignedByte()) ?: return null
                val start = inp.readLong()
                val linear = ArrayList<Vec3Sample>()
                val gravity = ArrayList<Vec3Sample>()
                val rec = ByteArray(RECORD_BYTES)
                while (true) {
                    try {
                        inp.readFully(rec)
                    } catch (_: EOFException) {
                        break
                    }
                    val type = rec[0].toInt()
                    val dt = ((rec[1].toInt() and 0xFF) shl 24) or ((rec[2].toInt() and 0xFF) shl 16) or
                        ((rec[3].toInt() and 0xFF) shl 8) or (rec[4].toInt() and 0xFF)
                    fun s(i: Int) = (((rec[i].toInt() shl 8) or (rec[i + 1].toInt() and 0xFF)).toShort()) / SCALE
                    val sample = Vec3Sample(start + dt, s(5), s(7), s(9))
                    when (type) {
                        LINEAR -> linear += sample
                        GRAVITY -> gravity += sample
                    }
                }
                AccelRecording(source, start, linear, gravity)
            }
        }.getOrNull()
    }
}
