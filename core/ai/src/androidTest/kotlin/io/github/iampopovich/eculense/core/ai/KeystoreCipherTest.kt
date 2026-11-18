package io.github.iampopovich.eculense.core.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreCipherTest {
    private val cipher = KeystoreCipher(alias = "test_alias")

    @Test
    fun roundTrip() {
        val encrypted = cipher.encrypt("sk-secret")
        assertNotEquals("sk-secret", encrypted)
        assertEquals("sk-secret", cipher.decrypt(encrypted))
    }
}
