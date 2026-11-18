package io.github.iampopovich.eculense.core.ailocal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitEstimatorTest {
    private val gb = 1L shl 30
    private val mb = 1L shl 20

    private fun phone(ramGb: Double, freeGb: Double = 100.0, arm64: Boolean = true) = DeviceProfile(
        totalRamBytes = (ramGb * gb).toLong(),
        availRamBytes = (ramGb * gb / 3).toLong(),
        socManufacturer = "Тестовая",
        socModel = "T1",
        abis = if (arm64) listOf("arm64-v8a", "armeabi-v7a") else listOf("armeabi-v7a"),
        freeStorageBytes = (freeGb * gb).toLong(),
        gpu = true,
        npu = null,
        aicore = false,
        sdk = 36,
    )

    private val e2b = 2_588_147_712L
    private val e4b = 3_659_530_240L
    private val small = 289 * mb

    @Test
    fun `flagship with 11 GB handles both Gemma 4 models`() {
        assertEquals(Fit.OK, FitEstimator.estimate(e2b, phone(10.9)).fit)
        assertEquals(Fit.OK, FitEstimator.estimate(e4b, phone(10.9)).fit)
    }

    @Test
    fun `3_6 GB phone is told Gemma 4 E2B will most likely not work and why`() {
        val r = FitEstimator.estimate(e2b, phone(3.6))
        assertEquals(Fit.UNLIKELY, r.fit)
        assertTrue(r.reasons.joinToString(), r.reasons.any { "ОЗУ" in it })
    }

    @Test
    fun `small model fits a 3_6 GB phone`() {
        assertEquals(Fit.OK, FitEstimator.estimate(small, phone(3.6)).fit)
    }

    @Test
    fun `borderline memory is tight`() {
        assertEquals(Fit.TIGHT, FitEstimator.estimate(e2b, phone(6.0)).fit)
    }

    @Test
    fun `not enough storage beats enough memory`() {
        val r = FitEstimator.estimate(e2b, phone(12.0, freeGb = 2.0))
        assertEquals(Fit.UNLIKELY, r.fit)
        assertTrue(r.reasons.joinToString(), r.reasons.any { "места" in it })
    }

    @Test
    fun `storage is not needed again when the model is already downloaded`() {
        assertEquals(Fit.OK, FitEstimator.estimate(e2b, phone(12.0, freeGb = 0.5), downloaded = true).fit)
    }

    @Test
    fun `no arm64 means the runtime cannot load at all`() {
        val r = FitEstimator.estimate(small, phone(8.0, arm64 = false))
        assertEquals(Fit.UNLIKELY, r.fit)
        assertTrue(r.reasons.any { "arm64" in it })
    }

    @Test
    fun `Gallery minimum memory caps the estimate - below it at most tight, far below unlikely`() {
        // 14T Pro reports 10.9 GiB: Gemma 4 E2B (min 8) stays OK, E4B (min 12) becomes tight with Gallery's reason
        assertEquals(Fit.OK, FitEstimator.estimate(e2b, phone(10.9), minRamGb = 8).fit)
        val e4bOnFlagship = FitEstimator.estimate(e4b, phone(10.9), minRamGb = 12)
        assertEquals(Fit.TIGHT, e4bOnFlagship.fit)
        assertTrue(e4bOnFlagship.reasons.any { "12 ГБ" in it && "Gallery" in it })
        // 3.6 GB phone vs a 1 GB model the Gallery wants 6 GB for
        assertEquals(Fit.UNLIKELY, FitEstimator.estimate(584_417_280L, phone(3.6), minRamGb = 6).fit)
        // no minimum known: plain heuristic
        assertEquals(Fit.OK, FitEstimator.estimate(e4b, phone(10.9), minRamGb = null).fit)
    }

    @Test
    fun `unknown size gives an honest unknown estimate`() {
        assertEquals(Fit.UNKNOWN, FitEstimator.estimate(null, phone(8.0)).fit)
    }
}
