package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind

internal object DtcDecoder {
    /** Первые 2 бита — система (P/C/B/U), следующие 2 — первая цифра, дальше три hex-цифры. */
    fun code(a: Int, b: Int): String {
        val system = "PCBU"[a shr 6]
        val firstDigit = (a shr 4) and 0x3
        return "%c%d%X%02X".format(system, firstDigit, a and 0x0F, b)
    }

    /** responseSid: 0x43 (сервис 03), 0x47 (07), 0x4A (0A). На CAN после SID идёт байт-счётчик. */
    fun parse(messages: List<ByteArray>, responseSid: Int, isCan: Boolean, kind: DtcKind): List<Dtc> =
        messages.flatMap { message ->
            val u = message.toUnsigned()
            if (u.isEmpty() || u[0] != responseSid) return@flatMap emptyList()
            val payload = if (isCan) u.drop(2) else u.drop(1)
            payload.chunked(2)
                .filter { it.size == 2 && (it[0] != 0 || it[1] != 0) }
                .map { Dtc(code(it[0], it[1]), kind) }
        }.distinct()
}
