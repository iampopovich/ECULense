package io.github.iampopovich.eculense.core.obd

internal object SupportedPids {
    /** 4 байта ответа на 01 00/20/40… → номера поддерживаемых PID (base+1 … base+32). */
    fun decode(base: Int, data: IntArray): Set<Int> {
        require(data.size == 4) { "Маска — 4 байта, пришло ${data.size}" }
        val bits = (data[0].toLong() shl 24) or (data[1].toLong() shl 16) or (data[2].toLong() shl 8) or data[3].toLong()
        return (1..32).filter { i -> (bits shr (32 - i)) and 1L == 1L }.map { base + it }.toSet()
    }
}
