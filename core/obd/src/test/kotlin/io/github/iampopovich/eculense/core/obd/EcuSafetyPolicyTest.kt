package io.github.iampopovich.eculense.core.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-D: the read-only gate for every multi-ECU request. */
class EcuSafetyPolicyTest {
    private val policy = EcuSafetyPolicy()
    private val abs = EcuTarget(EcuAddress.can11(0x7D1), emission = false, kind = ModuleKind.ABS_ESC)
    private val engine = EcuTarget(EcuAddress.can11(0x7E0), emission = true, kind = ModuleKind.ENGINE)

    private fun denied(target: EcuTarget, command: String, purpose: RequestPurpose = RequestPurpose.READ) =
        (policy.check(target, command, purpose) as? PolicyDecision.Denied)?.reason

    @Test
    fun `only the read services pass, every other service byte is denied`() {
        for (sid in 0x00..0xFF) {
            val command = "%02X00".format(sid)
            val decision = policy.check(abs, command)
            if (sid in EcuSafetyPolicy.READ_SERVICES) {
                assertEquals(command, PolicyDecision.Allowed, decision)
            } else {
                assertTrue(command, decision is PolicyDecision.Denied)
            }
        }
        assertEquals(setOf(0x01, 0x02, 0x03, 0x07, 0x09, 0x0A, 0x18, 0x19, 0x21, 0x22, 0x1A, 0x3E), EcuSafetyPolicy.READ_SERVICES)
    }

    @Test
    fun `each explicitly dangerous service is denied, also to the engine and functionally`() {
        for (sid in listOf(0x11, 0x27, 0x28, 0x2E, 0x2F, 0x31, 0x34, 0x35, 0x36, 0x37, 0x3B, 0x85)) {
            assertTrue(sid in EcuSafetyPolicy.DENIED_SERVICES)
            for (target in listOf(abs, engine, EcuTarget.FUNCTIONAL)) {
                assertEquals("%02X".format(sid), DenyReason.SERVICE_NOT_ALLOWED, denied(target, "%02X0101".format(sid)))
            }
        }
    }

    @Test
    fun `sessions only when the caller lists them`() {
        assertEquals(DenyReason.SESSION_NOT_LISTED, denied(abs, "1003"))
        assertEquals(DenyReason.SESSION_NOT_LISTED, denied(abs, "1001"))
        val listed = EcuSafetyPolicy(allowedSessions = setOf(0x03))
        assertEquals(PolicyDecision.Allowed, listed.check(abs, "1003"))
        assertTrue(listed.check(abs, "1002") is PolicyDecision.Denied)
        assertTrue(listed.check(abs, "100300") is PolicyDecision.Denied)
    }

    @Test
    fun `clearing only for emission ECUs inside the safe-clear flow`() {
        assertEquals(DenyReason.CLEAR_NOT_ALLOWED, denied(abs, "14FFFFFF", RequestPurpose.SAFE_CLEAR))
        assertEquals(DenyReason.CLEAR_NOT_ALLOWED, denied(abs, "04", RequestPurpose.SAFE_CLEAR))
        assertEquals(DenyReason.CLEAR_NOT_ALLOWED, denied(engine, "04"))
        assertEquals(DenyReason.CLEAR_NOT_ALLOWED, denied(EcuTarget.FUNCTIONAL, "14FFFFFF"))
        assertEquals(PolicyDecision.Allowed, policy.check(engine, "04", RequestPurpose.SAFE_CLEAR))
        assertEquals(PolicyDecision.Allowed, policy.check(EcuTarget.FUNCTIONAL, "04", RequestPurpose.SAFE_CLEAR))
    }

    @Test
    fun `blocked addresses and SRS modules are denied even for a read`() {
        for (tx in listOf(0x7D2, 0x7D4)) {
            assertEquals(DenyReason.BLOCKED_ADDRESS, denied(EcuTarget(EcuAddress.can11(tx), false), "1902FF"))
            assertEquals(DenyReason.BLOCKED_ADDRESS, denied(EcuTarget(EcuAddress.can11(tx), false), "3E00"))
        }
        // The reply IDs alone are enough (a pair written the other way round).
        assertEquals(DenyReason.BLOCKED_ADDRESS, denied(EcuTarget(EcuAddress('6', "7D0", "7DA"), false), "3E00"))
        assertEquals(DenyReason.BLOCKED_ADDRESS, denied(EcuTarget(EcuAddress('6', "7D3", "7DC"), false), "3E00"))
        assertEquals(DenyReason.SRS_MODULE, denied(EcuTarget(EcuAddress.can11(0x7B0), false, ModuleKind.SRS), "3E00"))
        assertEquals(DenyReason.SRS_MODULE, denied(EcuTarget(EcuAddress('5', "58", "58"), false), "1800FF00"))
        assertEquals(ModuleKind.SRS, KnownModules.kindOf(EcuAddress('5', "5A", "5A")))
    }

    @Test
    fun `malformed requests are denied`() {
        for (c in listOf("", "1", "ATZ", "19 0", "XYZW")) assertEquals(c, DenyReason.MALFORMED, denied(abs, c))
        assertEquals(PolicyDecision.Allowed, policy.check(abs, "19 02 0D"))
    }

    @Test
    fun `require throws a typed error`() {
        val e = runCatching { policy.require(abs, "2E F1 90 00") }.exceptionOrNull() as ObdException
        assertEquals(ObdError.RequestDenied("2E F1 90 00", "7D1/7D9", "SERVICE_NOT_ALLOWED"), e.error)
    }
}
