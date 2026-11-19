package io.github.iampopovich.eculense.core.obd

internal object SupportedPids {
    /** 4 bytes of the response to 01 00/20/40… → supported PID numbers (base+1 … base+32). */
    fun decode(base: Int, data: IntArray): Set<Int> {
        require(data.size == 4) { "mask must be 4 bytes, got ${data.size}" }
        val bits = (data[0].toLong() shl 24) or (data[1].toLong() shl 16) or (data[2].toLong() shl 8) or data[3].toLong()
        return (1..32).filter { i -> (bits shr (32 - i)) and 1L == 1L }.map { base + it }.toSet()
    }
}
