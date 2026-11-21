package io.github.iampopovich.eculense.core.obd

/**
 * R-D (multi-ECU): how the ELM327 prints the header of each reply line with headers on (ATH1), per protocol family.
 * Source: ELM327 datasheet (ELM327DSJ, pp. 38-42 «OBD Message Formats», «Multiline Responses»).
 * - CAN 11 bit: 3 hex digits of ID, then the ISO-TP PCI byte («7E8 06 41 00 BE 3F B8 13 00»); no checksum shown.
 * - CAN 29 bit: 4 bytes «18 DA F1 10» (priority, DA physical / DB functional, target, source), then the PCI byte.
 * - ISO 9141-2: 3 header bytes «48 6B ss», the data, one checksum byte (sum mod 256).
 * - ISO 14230-4 (KWP2000): format byte 0x80|length (top bits = address mode), target (F1 = tester), source, then the
 *   data (a 4th length byte when the length bits are 0) and the checksum (sum mod 256).
 * J1850 and J1939 are not supported by the module scan (no Kia/Hyundai uses them).
 */
enum class HeaderFormat {
    CAN_11, CAN_29, ISO_9141, KWP;

    val isCan: Boolean get() = this == CAN_11 || this == CAN_29

    companion object {
        /** The header format of ELM protocol number [p] (ATDPN); null = the module scan does not support it. */
        fun of(p: Char): HeaderFormat? = when (p) {
            '6', '8' -> CAN_11
            '7', '9' -> CAN_29
            '3' -> ISO_9141
            '4', '5' -> KWP
            else -> null
        }
    }
}

/**
 * One ECU on the bus. [tx] = where requests go, [rx] = the ID/source byte its replies carry (both upper-case hex).
 * CAN 11 bit: «7E1»/«7E9»; CAN 29 bit: «18DA10F1»/«18DAF110»; K-Line: the ECU's address byte for both («10»).
 */
data class EcuAddress(val protocol: Char, val tx: String, val rx: String) {
    val format: HeaderFormat? get() = HeaderFormat.of(protocol)

    /** Compact label for the UI: «7E1/7E9» on CAN, «0x10» on K-Line. */
    val label: String get() = if (format?.isCan == true) "$tx/$rx" else "0x$rx"

    companion object {
        /** The request address of an ECU that answered from [rx] (ISO 15765-4: 11 bit tx = rx - 8; 29 bit swaps). */
        fun fromReply(protocol: Char, rx: String): EcuAddress = when (HeaderFormat.of(protocol)) {
            HeaderFormat.CAN_11 -> EcuAddress(protocol, "%03X".format(rx.toInt(16) - 8), rx)
            HeaderFormat.CAN_29 -> EcuAddress(protocol, "18DA" + rx.substring(6, 8) + rx.substring(4, 6), rx)
            else -> EcuAddress(protocol, rx, rx)
        }

        /** A CAN 11-bit pair from its request ID (reply = request + 8, ISO 15765-4 and the Kia/Hyundai pairs). */
        fun can11(tx: Int, protocol: Char = '6'): EcuAddress = EcuAddress(protocol, "%03X".format(tx), "%03X".format(tx + 8))
    }
}

/**
 * One reply line with its header split off. [source] = CAN ID (3 or 8 hex) or K-Line source byte (2 hex); [target] =
 * the addressed byte (K-Line/29 bit) or null; [payload] = data bytes after the PCI (CAN) or before the checksum
 * (K-Line); [pci] = the ISO-TP PCI byte on CAN; [checksumOk] = K-Line checksum verdict (null on CAN).
 */
data class HeaderedFrame(val source: String, val target: String?, val payload: List<Int>, val pci: Int?, val checksumOk: Boolean?)

/** Reply of all ECUs to one request: assembled messages per ECU (first-seen order) and lines that were rejected. */
data class HeaderedReply(val byEcu: Map<String, List<ByteArray>>, val rejected: List<String>) {
    val ecus: Set<String> get() = byEcu.keys
}

/** K-Line checksum: the sum of all preceding bytes modulo 256 (ISO 9141-2 / ISO 14230-2; ELM327DSJ p. 38). */
object KLineChecksum {
    fun of(bytes: List<Int>): Int = bytes.sum() and 0xFF

    fun valid(frame: List<Int>): Boolean = frame.size >= 2 && of(frame.dropLast(1)) == frame.last()
}

/**
 * Splits ATH1 replies (spaces on or off — ATS1/ATS0 — both accepted) into frames and groups them per ECU, assembling
 * ISO-TP multi-frame messages per CAN ID. Frames with a wrong K-Line checksum, broken ISO-TP sequences and lines that
 * are not hex are rejected (listed in [HeaderedReply.rejected]), never decoded.
 *
 * Note on the datasheet examples: the ISO 9141 lines «48 6B 10 41 00 BE 3E B8 11 FA» / «48 6B 18 41 00 80 10 80 00
 * C0» (ELM327DSJ p. 42) carry illustrative checksums (the real sums are C9 / 1C); a real ELM327 checks the checksum
 * itself and prints «<DATA ERROR» instead. The parser therefore reports them with checksumOk = false.
 */
object HeaderedResponseParser {
    private val hex = Regex("^[0-9A-F]+$")

    /** One line → frame, or null when it is not a frame of [format]. */
    fun frame(line: String, format: HeaderFormat): HeaderedFrame? {
        val s = line.replace(" ", "").uppercase()
        if (!s.matches(hex)) return null
        return when (format) {
            HeaderFormat.CAN_11 -> canFrame(s, idLength = 3)
            HeaderFormat.CAN_29 -> canFrame(s, idLength = 8)
            HeaderFormat.ISO_9141 -> kLineFrame(s, kwp = false)
            HeaderFormat.KWP -> kLineFrame(s, kwp = true)
        }
    }

    private fun canFrame(s: String, idLength: Int): HeaderedFrame? {
        if (s.length < idLength + 2 || (s.length - idLength) % 2 != 0) return null
        val id = s.substring(0, idLength)
        val bytes = s.substring(idLength).chunked(2).map { it.toInt(16) }
        val target = if (idLength == 8) id.substring(4, 6) else null
        return HeaderedFrame(id, target, bytes.drop(1), bytes[0], checksumOk = null)
    }

    private fun kLineFrame(s: String, kwp: Boolean): HeaderedFrame? {
        if (s.length % 2 != 0) return null
        val bytes = s.chunked(2).map { it.toInt(16) }
        if (bytes.size < 5) return null // 3 header bytes + at least one data byte + checksum
        val ok = KLineChecksum.valid(bytes)
        if (!kwp) return HeaderedFrame("%02X".format(bytes[2]), "%02X".format(bytes[1]), bytes.subList(3, bytes.size - 1), null, ok)
        val length = bytes[0] and 0x3F
        val headerLength = if (length == 0) 4 else 3
        val dataLength = if (length == 0) bytes[3] else length
        if (bytes.size != headerLength + dataLength + 1) return HeaderedFrame("%02X".format(bytes[2]), "%02X".format(bytes[1]), emptyList(), null, false)
        return HeaderedFrame("%02X".format(bytes[2]), "%02X".format(bytes[1]), bytes.subList(headerLength, headerLength + dataLength), null, ok)
    }

    /** Reply lines of one request (ElmResult.Data lines or raw text lines) → messages per ECU. */
    fun parse(lines: List<String>, format: HeaderFormat): HeaderedReply {
        val byEcu = LinkedHashMap<String, MutableList<ByteArray>>()
        val rejected = mutableListOf<String>()
        val pending = HashMap<String, Assembly>()
        for (line in lines.map { it.trim() }.filter { it.isNotEmpty() }) {
            val f = frame(line, format)
            if (f == null || f.checksumOk == false) {
                rejected += line
                continue
            }
            if (!format.isCan) {
                if (f.payload.isEmpty()) rejected += line else byEcu.getOrPut(f.source) { mutableListOf() } += f.payload.toBytes()
                continue
            }
            val pci = f.pci ?: continue
            when (pci shr 4) {
                0x0 -> {
                    val n = pci and 0x0F
                    if (n == 0 || n > f.payload.size) rejected += line else byEcu.getOrPut(f.source) { mutableListOf() } += f.payload.take(n).toBytes()
                }
                0x1 -> {
                    if (f.payload.isEmpty()) {
                        rejected += line
                        continue
                    }
                    val length = ((pci and 0x0F) shl 8) or f.payload[0]
                    pending[f.source]?.let { rejected += it.lines }
                    pending[f.source] = Assembly(length, f.payload.drop(1).toMutableList(), 1, mutableListOf(line))
                }
                0x2 -> {
                    val a = pending[f.source]
                    if (a == null || (pci and 0x0F) != (a.next and 0x0F)) {
                        rejected += line
                        a?.let { rejected += it.lines; pending.remove(f.source) }
                        continue
                    }
                    a.data += f.payload
                    a.lines += line
                    a.next++
                    if (a.data.size >= a.length) {
                        byEcu.getOrPut(f.source) { mutableListOf() } += a.data.take(a.length).toBytes()
                        pending.remove(f.source)
                    }
                }
                else -> rejected += line // flow control or unknown PCI: not data
            }
        }
        pending.values.forEach { rejected += it.lines } // cut multi-frame messages
        return HeaderedReply(byEcu, rejected)
    }

    private class Assembly(val length: Int, val data: MutableList<Int>, var next: Int, val lines: MutableList<String>)

    private fun List<Int>.toBytes(): ByteArray = ByteArray(size) { this[it].toByte() }
}
