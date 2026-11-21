package io.github.iampopovich.eculense.core.obdb

/** A decoded OBDb signal: [value] after scaling; [label] = the `map` entry of the raw value, if any. */
data class SignalValue(val value: Double, val label: String? = null)

/**
 * R-E: decodes OBDb signalset v3 `fmt` fields (schema github.com/OBDb/.schemas signals.json):
 * - the payload is the reply after the positive echo (SID + 0x40 and the PID: 1 byte for 01/21, 2 bytes for 22);
 * - `bix` = first bit, counted from the most significant bit of payload byte 0; `len` bits, big-endian, unless
 *   `blsb` (the bytes of a byte-aligned value come least significant first);
 * - `sign` = two's complement of `len` bits; value = raw · mul / div + add;
 * - values outside `min`..`max` (scaled) are rejected, ≤ `nullmin` / ≥ `nullmax` (scaled) mean «no value»;
 * - `map` gives a label for the raw value.
 * Verified against the OBDb test cases (Kia-Niro-EV, Kia-Sorento) in ObdbTestVectorsTest.
 */
object SignalDecoder {
    /** The data bytes after the echo of [command], or null when [message] is not its positive reply. */
    fun payload(command: SignalCommand, message: ByteArray): ByteArray? {
        val sid = command.service.toIntOrNull(16) ?: return null
        val pid = command.pid.chunked(2).map { it.toIntOrNull(16) ?: return null }
        val u = message.map { it.toInt() and 0xFF }
        if (u.size < 1 + pid.size || u[0] != sid + 0x40) return null
        if (u.subList(1, 1 + pid.size) != pid) return null
        return message.copyOfRange(1 + pid.size, message.size)
    }

    fun decode(fmt: SignalFormat, payload: ByteArray): SignalValue? {
        if (fmt.len <= 0 || fmt.len > 64 || fmt.bix < 0) return null
        if (fmt.bix + fmt.len > payload.size * 8) return null
        var raw = 0L
        if (fmt.blsb && fmt.len > 8 && fmt.bix % 8 == 0 && fmt.len % 8 == 0) {
            val first = fmt.bix / 8
            for (i in (fmt.len / 8 - 1) downTo 0) raw = (raw shl 8) or (payload[first + i].toLong() and 0xFF)
        } else {
            for (bit in fmt.bix until fmt.bix + fmt.len) {
                val b = (payload[bit / 8].toInt() shr (7 - bit % 8)) and 1
                raw = (raw shl 1) or b.toLong()
            }
        }
        val label = fmt.map[raw]
        if (fmt.signed && fmt.len < 64 && raw and (1L shl (fmt.len - 1)) != 0L) raw -= 1L shl fmt.len
        val value = raw * fmt.mul / fmt.div + fmt.add
        if (fmt.nullMin != null && value <= fmt.nullMin) return null
        if (fmt.nullMax != null && value >= fmt.nullMax) return null
        if (fmt.min != null && value < fmt.min - EPSILON) return null
        if (fmt.max != null && value > fmt.max + EPSILON) return null
        return SignalValue(value, label)
    }

    /** Every signal of [command] found in [messages] (the first positive reply). */
    fun decodeAll(command: SignalCommand, messages: List<ByteArray>): Map<Signal, SignalValue> {
        val data = messages.firstNotNullOfOrNull { payload(command, it) } ?: return emptyMap()
        return command.signals.mapNotNull { s -> s.fmt?.let { f -> decode(f, data)?.let { s to it } } }.toMap()
    }

    private const val EPSILON = 1e-9
}
