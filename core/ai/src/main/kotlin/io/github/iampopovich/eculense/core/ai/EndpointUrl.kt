package io.github.iampopovich.eculense.core.ai

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/**
 * Локальная проверка адреса, который ввёл пользователь, — до любого запроса в сеть.
 * Cleartext в network security config разрешён (Ollama/LM Studio в домашней сети), поэтому именно здесь
 * не пускаем незашифрованный http к серверам в интернете: http — только loopback, частные сети и *.local.
 */
object EndpointUrl {
    sealed interface Result {
        data class Valid(val url: String) : Result
        data class Invalid(val reason: String) : Result
    }

    const val PUBLIC_HTTP =
        "Незашифрованный http:// разрешён только для локальной сети (127.0.0.1, 10.x, 172.16–31.x, 192.168.x, *.local). " +
            "Для сервера в интернете используйте https://"

    fun validate(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Invalid("Введите адрес сервера")
        if (text.any { it.isWhitespace() }) return Result.Invalid("В адресе не должно быть пробелов")
        val uri = try {
            URI(text)
        } catch (_: URISyntaxException) {
            return Result.Invalid("Некорректный адрес. Пример: https://api.openai.com")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.Invalid("Адрес должен начинаться с http:// или https://")
        // При неверном порту URI молча переходит в «registry»-режим: host = null, authority = "host:99999".
        val authority = uri.rawAuthority.orEmpty()
        val portText = authority.substringAfterLast(':', "").takeIf { ':' in authority && !authority.endsWith("]") }
        if (portText != null && portText.isNotEmpty() && (portText.toIntOrNull() ?: 0) !in 1..65535) {
            return Result.Invalid("Неверный порт в адресе")
        }
        val host = uri.host
        if (host.isNullOrBlank()) return Result.Invalid("В адресе нет имени сервера")
        if (uri.rawQuery != null || uri.rawFragment != null) return Result.Invalid("Уберите из адреса параметры (?… и #…)")
        if (scheme == "http" && !LocalNetwork.isLocalHost(host)) return Result.Invalid(PUBLIC_HTTP)
        return Result.Valid(text.trimEnd('/'))
    }
}

/** Локальный ли адрес — только по записи хоста, без DNS (имя вроде 127.0.0.1.nip.io — не локальное). */
object LocalNetwork {
    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val ipv6 = Regex("""^[0-9a-fA-F:.]+$""")

    fun isLocalHost(rawHost: String): Boolean {
        val host = rawHost.trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host.endsWith(".local") && host.length > ".local".length) return true
        ipv4.matchEntire(host)?.let { m ->
            val b = m.groupValues.drop(1).map { it.toInt() }
            if (b.any { it > 255 }) return false
            return b[0] == 127 || b[0] == 10 ||
                (b[0] == 172 && b[1] in 16..31) ||
                (b[0] == 192 && b[1] == 168) ||
                (b[0] == 169 && b[1] == 254)
        }
        if (':' in host && ipv6.matches(host)) {
            // Литерал IPv6 разбирается без обращения к DNS.
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet6Address ?: return false
            val first = address.address[0].toInt() and 0xFF
            return address.isLoopbackAddress || address.isLinkLocalAddress || (first and 0xFE) == 0xFC
        }
        return false
    }
}
