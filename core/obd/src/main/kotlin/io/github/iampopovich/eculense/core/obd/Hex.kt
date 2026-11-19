package io.github.iampopovich.eculense.core.obd

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length: $this" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

internal fun ByteArray.toUnsigned(): List<Int> = map { it.toInt() and 0xFF }

private val lengthLine = Regex("^[0-9A-F]{3}$")
private val frameLine = Regex("^[0-9A-F]:[0-9A-F]+$")
private val hexLine = Regex("^([0-9A-F]{2})+$")

/** ELM327 response (headers off) → one byte array per ECU message. */
internal fun elmMessages(lines: List<String>): List<ByteArray> {
    val isMultiFrame = lines.size > 1 && lines[0].matches(lengthLine) && lines.drop(1).all { it.matches(frameLine) }
    if (isMultiFrame) {
        val length = lines[0].toInt(16)
        val hex = lines.drop(1).joinToString("") { it.substringAfter(':') }
        return listOf(hex.hexToBytes().copyOf(length))
    }
    return lines.filter { it.matches(hexLine) }.map { it.hexToBytes() }
}
