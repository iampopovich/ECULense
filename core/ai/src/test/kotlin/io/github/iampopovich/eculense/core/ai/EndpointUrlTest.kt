package io.github.iampopovich.eculense.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointUrlTest {
    private fun invalid(raw: String): String {
        val result = EndpointUrl.validate(raw)
        assertTrue("$raw → $result", result is EndpointUrl.Result.Invalid)
        return (result as EndpointUrl.Result.Invalid).reason
    }

    @Test
    fun `valid urls are trimmed and lose the trailing slash`() {
        assertEquals(EndpointUrl.Result.Valid("https://openrouter.ai/api/v1"), EndpointUrl.validate("  https://openrouter.ai/api/v1/ "))
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
}
