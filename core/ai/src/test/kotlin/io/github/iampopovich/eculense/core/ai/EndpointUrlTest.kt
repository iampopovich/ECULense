package io.github.iampopovich.eculense.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointUrlTest {
    private fun invalid(raw: String): String {
        val result = EndpointUrl.validate(raw)
        assertTrue("$raw → $result", result is EndpointUrl.Result.Invalid)
        return (result as EndpointUrl.Result.Invalid).reason
    }

    private fun valid(raw: String) {
        val result = EndpointUrl.validate(raw)
        assertTrue("$raw → $result", result is EndpointUrl.Result.Valid)
    }

    @Test
    fun `valid urls are trimmed and lose the trailing slash`() {
        assertEquals(EndpointUrl.Result.Valid("https://openrouter.ai"), EndpointUrl.validate("  https://openrouter.ai/ "))
        assertEquals(EndpointUrl.Result.Valid("http://127.0.0.1:8089/v1"), EndpointUrl.validate("http://127.0.0.1:8089/v1"))
        assertEquals(EndpointUrl.Result.Valid("http://192.168.1.10:1234/v1"), EndpointUrl.validate("http://192.168.1.10:1234/v1//"))
    }

    @Test
    fun `garbage is rejected before any request`() {
        assertEquals("Введите адрес сервера", invalid("  "))
        assertTrue(invalid("openrouter.ai/api/v1").contains("http://"))
        assertTrue(invalid("localhost:8080").contains("http://"))
        assertTrue(invalid("ftp://example.com").contains("http://"))
        assertTrue(invalid("asdf qwer").contains("пробел"))
        assertTrue(invalid("http://").isNotBlank())
        assertTrue(invalid("http://:8080/v1").contains("сервер"))
        assertTrue(invalid("http://host:99999").contains("порт"))
        assertTrue(invalid("https://api.openai.com/v1?x=1").contains("параметр"))
        assertTrue(invalid("https://exa mple.com").isNotBlank())
    }

    @Test
    fun `plain http is allowed for loopback, private ranges, link-local and dot-local hosts`() {
        listOf(
            "http://127.0.0.1:11434",
            "http://127.8.9.10",
            "http://localhost:8089/v1",
            "http://LOCALHOST",
            "http://[::1]:11434",
            "http://10.0.2.2:1234/v1",
            "http://10.255.255.255",
            "http://172.16.0.1:8000/v1",
            "http://172.31.255.254",
            "http://192.168.1.5:1234/v1",
            "http://169.254.10.20:11434",
            "http://[fe80::1]:11434",
            "http://[fd12:3456::1]:8000/v1",
            "http://ollama.local:11434",
            "http://my-pc.local./v1",
        ).forEach(::valid)
    }

    @Test
    fun `plain http to a public host is rejected with an https hint`() {
        listOf(
            "http://example.com",
            "http://api.openai.com/v1",
            "http://8.8.8.8",
            "http://172.32.0.1",
            "http://172.15.255.255",
            "http://192.169.1.1",
            "http://11.0.0.1",
            "http://[2001:db8::1]",
            "http://local",
            "http://evil.local.example.com",
            "http://127.0.0.1.nip.io",
        ).forEach { url ->
            val reason = invalid(url)
            assertTrue("$url → $reason", reason.contains("https://"))
            assertTrue("$url → $reason", reason.contains("локальн"))
        }
        // https is allowed to anywhere.
        valid("https://example.com/v1")
        valid("https://8.8.8.8")
    }

    @Test
    fun `local host detection never resolves names`() {
        assertTrue(LocalNetwork.isLocalHost("192.168.0.1"))
        assertFalse(LocalNetwork.isLocalHost("192.168.0.1.example.com"))
        assertFalse(LocalNetwork.isLocalHost("256.1.1.1"))
        assertFalse(LocalNetwork.isLocalHost("10.1.1"))
        assertFalse(LocalNetwork.isLocalHost(""))
    }
}
