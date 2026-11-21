package io.github.iampopovich.eculense.core.ai

import io.github.iampopovich.eculense.core.model.LocalNetwork
import java.net.URI
import java.net.URISyntaxException

/**
 * Local check of the address entered by the user, before any network request.
 * Cleartext is allowed in the network security config (Ollama/LM Studio on the home network), so this is where
 * unencrypted http to internet servers is blocked: http only for loopback, private networks and *.local.
 */
object EndpointUrl {
    sealed interface Result {
        data class Valid(val url: String) : Result
        data class Invalid(val problem: UrlProblem) : Result {
            val reason: String get() = problem.message
        }
    }

    fun validate(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Invalid(UrlProblem.EMPTY)
        if (text.any { it.isWhitespace() }) return Result.Invalid(UrlProblem.WHITESPACE)
        val uri = try {
            URI(text)
        } catch (_: URISyntaxException) {
            return Result.Invalid(UrlProblem.MALFORMED)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.Invalid(UrlProblem.SCHEME)
        // With an invalid port, URI silently switches to «registry» mode: host = null, authority = "host:99999".
        val authority = uri.rawAuthority.orEmpty()
        val portText = authority.substringAfterLast(':', "").takeIf { ':' in authority && !authority.endsWith("]") }
        if (portText != null && portText.isNotEmpty() && (portText.toIntOrNull() ?: 0) !in 1..65535) {
            return Result.Invalid(UrlProblem.PORT)
        }
        val host = uri.host
        if (host.isNullOrBlank()) return Result.Invalid(UrlProblem.NO_HOST)
        if (uri.rawQuery != null || uri.rawFragment != null) return Result.Invalid(UrlProblem.QUERY)
        if (scheme == "http" && !LocalNetwork.isLocalHost(host)) return Result.Invalid(UrlProblem.PUBLIC_HTTP)
        return Result.Valid(text.trimEnd('/'))
    }
}

/**
 * Why an entered address was rejected. The UI maps each value to a string resource; [message] is an English
 * technical text for logs and tests, never shown.
 */
enum class UrlProblem(val message: String) {
    EMPTY("server address is empty"),
    WHITESPACE("address contains whitespace"),
    MALFORMED("malformed address, e.g. https://api.openai.com"),
    SCHEME("address must start with http:// or https://"),
    PORT("invalid port"),
    NO_HOST("no host name"),
    QUERY("address must not contain ?query or #fragment"),
    PUBLIC_HTTP(
        "plain http:// is allowed only for the local network (127.0.0.1, 10.x, 172.16-31.x, 192.168.x, *.local); " +
            "use https:// for internet servers",
    ),
}
