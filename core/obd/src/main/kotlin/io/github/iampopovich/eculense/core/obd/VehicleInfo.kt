package io.github.iampopovich.eculense.core.obd

/** Mode 09 identification (B7): VIN, calibration IDs (PID 04) and calibration verification numbers (PID 06, hex). */
data class VehicleInfo(
    val vin: String? = null,
    val calibrationIds: List<String> = emptyList(),
    val cvns: List<String> = emptyList(),
)

/**
 * Pure decoder of mode 09 replies with headers off.
 * - CAN: one ISO-TP message "49 pid NN data..." (NN = number of data items; some ECUs omit it for the VIN).
 * - K-Line / J1850: one line per 4 data bytes, "49 pid SEQ d1 d2 d3 d4", SEQ counting from 1; lines may arrive out of
 *   order. They are sorted by SEQ and must be continuous, otherwise the reply is rejected.
 */
object VehicleInfoDecoder {
    private val VIN = Regex("[A-HJ-NPR-Z0-9]{17}")

    /** Data bytes of the reply to "09 [pid]" without SID, PID, count or sequence bytes; null when incomplete. */
    fun payload(messages: List<ByteArray>, pid: Int, isCan: Boolean): List<Int>? {
        val replies = messages.map { it.toUnsigned() }.filter { it.size >= 3 && it[0] == 0x49 && it[1] == pid }
        if (replies.isEmpty()) return null
        if (isCan) {
            val u = replies.maxBy { it.size }
            return u.drop(2)
        }
        val bySeq = replies.associateBy { it[2] }
        val seqs = bySeq.keys.sorted()
        if (seqs.first() != 1 || seqs != (1..seqs.size).toList()) return null
        return seqs.flatMap { bySeq.getValue(it).drop(3) }
    }

    /** VIN: 17 characters, leading 0x00 padding and a CAN count byte (0x01) are dropped. */
    fun vin(messages: List<ByteArray>, isCan: Boolean): String? {
        var data = payload(messages, 0x02, isCan) ?: return null
        if (isCan && data.size == 18 && data[0] == 0x01) data = data.drop(1)
        val text = data.dropWhile { it == 0x00 }.map { it.toChar() }.joinToString("").trim()
        return text.uppercase().takeIf { VIN.matches(it) }
    }

    /** CALIDs: 16 ASCII characters each, padded with 0x00. */
    fun calibrationIds(messages: List<ByteArray>, isCan: Boolean): List<String> {
        val data = items(messages, 0x04, isCan, 16) ?: return emptyList()
        return data.chunked(16).map { chunk ->
            chunk.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()
        }.filter { it.isNotEmpty() }
    }

    /** CVNs: 4 bytes each, shown as 8 hex digits. */
    fun cvns(messages: List<ByteArray>, isCan: Boolean): List<String> {
        val data = items(messages, 0x06, isCan, 4) ?: return emptyList()
        return data.chunked(4).filter { it.size == 4 }.map { c -> c.joinToString("") { "%02X".format(it) } }
    }

    /** On CAN the first data byte is the item count: keep it only when it does not match the length. */
    private fun items(messages: List<ByteArray>, pid: Int, isCan: Boolean, itemSize: Int): List<Int>? {
        val data = payload(messages, pid, isCan) ?: return null
        if (!isCan) return data
        val count = data.firstOrNull() ?: return null
        val rest = data.drop(1)
        return if (count > 0 && rest.size >= count * itemSize) rest.take(count * itemSize) else rest
    }
}
