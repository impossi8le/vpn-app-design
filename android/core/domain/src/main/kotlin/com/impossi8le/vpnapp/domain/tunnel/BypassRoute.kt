package com.impossi8le.vpnapp.domain.tunnel

/**
 * Подсеть, чей трафик идёт МИМО туннеля, и её человеческое имя.
 *
 * Отдельный тип, а не пара строк: это значение, которое передаётся в мост и
 * проверяется тестом на JVM, без `VpnService`.
 *
 * [name] — подпись записи: домен сервиса (`vk.com`) для обходов из каталога или
 * слово, введённое пользователем для своего обхода. `null` — имени нет, и в
 * списке виден только голый CIDR. Имя НЕ участвует в сравнении подсетей: две
 * записи с одним адресом и разными именами — это одна и та же подсеть, и
 * [mergeRoutes] оставляет одну из них ([BypassRoute] — data-класс, поэтому
 * равенство включает [name], и по одному адресу полагаться на `==` нельзя).
 */
data class BypassRoute(
    val network: String,
    val prefixLength: Int,
    val name: String? = null,
)

/** `"87.240.129.0/24"` → сеть и префикс; мусор → `null`. */
fun parseBypassCidr(text: String): BypassRoute? {
    val slash = text.indexOf('/')
    if (slash <= 0 || slash == text.lastIndex) return null
    val host = text.substring(0, slash)
    val prefix = text.substring(slash + 1).toIntOrNull() ?: return null
    // Отклоняем слишком широкие префиксы (0…7). Запись обхода НЕ может значить
    // «пусти мимо туннеля весь интернет»: `0.0.0.0/0` (или опечатка `/1`) — это
    // ПОЛНАЯ утечка трафика, причём молчаливая: туннель поднят, а идёт мимо него
    // всё. Наименьшее осмысленное выделение в этой задаче куда крупнее /8,
    // поэтому границы — 8..32; всё шире — не подсеть, а ошибка в данных.
    if (prefix !in 8..32) return null
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
    // Слишком широкая маска (меньше 8 бит) — та же полная утечка, что и `/0` в
    // [parseBypassCidr]: `0.0.0.0` значило бы «весь интернет мимо туннеля».
    // Отдаём `null`, а не число: иначе вызывающий принял бы это за настоящий обход.
    if (bits < 8) return null
    return bits
}

private fun isIpv4(text: String): Boolean {
    val parts = text.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.length <= 3 &&
            part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true
    }
}
