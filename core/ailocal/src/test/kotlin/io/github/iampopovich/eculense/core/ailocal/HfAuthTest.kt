package io.github.iampopovich.eculense.core.ailocal

import io.github.iampopovich.eculense.core.datapacks.PackAuth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HfAuthTest {
    @Test
    fun `token goes only to huggingface dot co`() {
        assertEquals(mapOf("Authorization" to "Bearer hf_test"), HfAuth.headers("https://huggingface.co/a/b/resolve/x/m.litertlm", "hf_test"))
        assertEquals(emptyMap<String, String>(), HfAuth.headers("https://cdn-lfs.huggingface.co/x", "hf_test"))
        assertEquals(emptyMap<String, String>(), HfAuth.headers("https://huggingface.co/a", null))
        assertEquals(emptyMap<String, String>(), HfAuth.headers("not a url", "hf_test"))
        assertTrue(HfAuth.isHfHost("https://huggingface.co/x"))
        assertFalse(HfAuth.isHfHost("https://evil.example/huggingface.co"))
    }

    @Test
    fun `pack auth is suspending, the token is read without blocking`() = runTest {
        var reads = 0
        val auth = PackAuth { url -> if (HfAuth.isHfHost(url)) HfAuth.headers(url, "hf_test".also { reads++ }) else emptyMap() }
        assertEquals(1, auth.headersFor("https://huggingface.co/x").size)
        assertEquals(0, auth.headersFor("https://example.org/x").size)
        assertEquals(1, reads)
    }
}
