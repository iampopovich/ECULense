package io.github.iampopovich.eculense.core.ailocal

import java.net.URL

/** Hugging Face token header: only for huggingface.co itself, never for the CDN a download redirects to. */
internal object HfAuth {
    private const val HOST = "huggingface.co"

    fun isHfHost(url: String): Boolean = runCatching { URL(url).host }.getOrNull() == HOST

    fun headers(url: String, token: String?): Map<String, String> =
        if (token.isNullOrBlank() || !isHfHost(url)) emptyMap() else mapOf("Authorization" to "Bearer $token")
}
