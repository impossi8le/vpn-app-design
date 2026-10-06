package com.impossi8le.vpnapp.domain.tunnel

/**
 * Подсеть, чей трафик идёт МИМО туннеля.
 *
 * Отдельный тип, а не пара строк: это значение, которое передаётся в мост и
 * проверяется тестом на JVM, без `VpnService`.
 */
data class BypassRoute(val network: String, val prefixLength: Int)

/** `"87.240.129.0/24"` → сеть и префикс; мусор → `null`. */
fun parseBypassCidr(text: String): BypassRoute? {
    val slash = text.indexOf('/')
    if (slash <= 0 || slash == text.lastIndex) return null
    val host = text.substring(0, slash)
    val prefix = text.substring(slash + 1).toIntOrNull() ?: return null
    if (prefix !in 0..32) return null
    if (!isIpv4(host)) return null
    return BypassRoute(host, prefix)
}

/**
 * Маска → длина префикса. `"255.255.255.0"` → `24`; неровная маска → `null`.
 *
 * Неровная маска (единицы вперемешку с нулями) длиной префикса не выражается;
 * вернуть для неё число значило бы выдумать неверную подсеть.
 */
fun maskToPrefixLength(mask: String): Int? {
    val octets = mask.split('.')
    if (octets.size != 4) return null
    var bits = 0
    var seenZero = false
    for (part in octets) {
        val value = part.toIntOrNull() ?: return null
        if (value !in 0..255) return null
        for (bit in 7 downTo 0) {
            val set = (value shr bit) and 1 == 1
            if (set) {
                if (seenZero) return null // единица после нуля — маска неровная
                bits++
            } else {
                seenZero = true
            }
        }
    }
    return bits
}

/** Формат владельца: `"route 87.240.129.0 255.255.255.0 net_gateway"`. */
fun parseBypassRouteLine(line: String): BypassRoute? {
    val tokens = line.trim().split(Regex("\\s+"))
    if (tokens.size < 4) return null
    if (tokens[0] != "route") return null
    if (tokens[3] != "net_gateway") return null
    val host = tokens[1]
    if (!isIpv4(host)) return null
    val prefix = maskToPrefixLength(tokens[2]) ?: return null
    return BypassRoute(host, prefix)
}

private fun isIpv4(text: String): Boolean {
    val parts = text.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.length <= 3 &&
            part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true
    }
}
