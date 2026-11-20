package io.github.iampopovich.eculense.core.obd

object FakeCar {
    val replies: Map<String, String> = mapOf(
        "ATZ" to "ELM327 v1.5",
        "ATE0" to "OK", "ATL0" to "OK", "ATS0" to "OK", "ATH0" to "OK", "ATSP0" to "OK",
        "0100" to "SEARCHING...\r4100BE3FA813",
        "0120" to "412080000000",
        "ATDPN" to "A6",
        "ATRV" to "13.9V",
        "010C" to "410C1AF8",
        "010D" to "410D20",
        "03" to "430201330300",
        "07" to "4300",
        "0A" to "4A00",
        "04" to "44",
        "0902" to "014\r0:490201314434\r1:47503030523535\r2:42313233343536",
    )
}
