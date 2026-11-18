package io.github.iampopovich.eculense.core.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PidsTest {
    private fun pid(code: Int) = StandardPids.byCode(code)!!

    @Test fun rpm() = assertEquals(1726.0, pid(0x0C).decodeFrom("410C1AF8".hexToBytes())!!, 0.001)
    @Test fun coolant() = assertEquals(83.0, pid(0x05).decodeFrom("41057B".hexToBytes())!!, 0.001)
    @Test fun maf() = assertEquals(5.0, pid(0x10).decodeFrom("411001F4".hexToBytes())!!, 0.001)
    @Test fun fuelTrim() = assertEquals(0.0, pid(0x06).decodeFrom("410680".hexToBytes())!!, 0.001)
    @Test fun voltage() = assertEquals(14.2, pid(0x42).decodeFrom("41423778".hexToBytes())!!, 0.001)

    @Test
    fun `reply for another pid is rejected`() {
        assertNull(pid(0x0C).decodeFrom("410D20".hexToBytes()))
    }

    @Test
    fun `ids and commands`() {
        assertEquals("01.0C", pid(0x0C).sensorId)
        assertEquals("010C", pid(0x0C).command)
    }
}
