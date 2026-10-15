package com.impossi8le.vpnapp.domain.tunnel

/**
 * Маршрут обхода для адреса нашего API-сервера.
 *
 * **Зачем.** Без него трафик приложения к собственному API уходит в туннель. На
 * устройстве это выглядит так: при поднятом VPN `curl` к нашему API не отвечает
 * до таймаута, а с выключенным VPN отвечает за 0.09 с. Последствие тяжёлое и
 * тихое — метрики и отчёты об ошибках не доходят вообще, причём ровно тогда,
 * когда они нужнее всего: когда что-то сломалось и туннель поднят.
 *
 * **Почему разбор имени, а не константа.** `sslip.io` кодирует адрес в самом
 * имени: `194-87-252-181.sslip.io` → `194.87.252.181`. При переезде сервера
 * меняется только `API_BASE_URL`, а исключение поедет за ним само. Константу
 * пришлось бы править в двух местах и однажды забыть во втором.
 *
 * **Почему `null`, а не догадка.** Обычный домен (`vpnbotfree.ru`) адреса не
 * содержит, а резолвить здесь негде — функция чистая и в тесте не должна ходить
 * в сеть. Исключить неверный адрес хуже, чем не исключить: мимо туннеля ушёл бы
 * чужой трафик, а нужный всё равно остался бы в туннеле.
 *
 * Возвращает `BypassRoute` с префиксом `/32` — ровно один адрес. Шире нельзя:
 * `/24` увёл бы мимо туннеля 254 чужих адреса.
 */
fun apiServerRouteOf(baseUrl: String): BypassRoute? {
    val host = hostOf(baseUrl) ?: return null
    val ip = ipFromSslipHost(host) ?: return null
    return BypassRoute(network = ip, prefixLength = 32, name = API_SERVER_LABEL)
}

/** Подпись записи: пользователь должен понимать, что это за строка в списке. */
const val API_SERVER_LABEL = "Сервер приложения"

/** Хост из URL: без схемы, порта, пути и учётных данных. `null` — не разобрать. */
private fun hostOf(baseUrl: String): String? {
    val afterScheme = baseUrl.substringAfter("://", missingDelimiterValue = "")
    if (afterScheme.isEmpty()) return null
    // Учётные данные и порт отбрасываем: нужен только хост.
    val hostPort = afterScheme.substringBefore('/').substringAfter('@')
    val host = hostPort.substringBefore(':')
    return host.ifEmpty { null }
}

/**
 * IP из имени вида `194-87-252-181.sslip.io`.
 *
 * Требуем РОВНО четыре октета и ровно домен `sslip.io`: `a.194-87-252-181.sslip.io`
 * кодирует другой адрес, и принять его значило бы исключить из туннеля чужой
 * хост. Октеты вне 0…255 тоже отвергаем — иначе `BypassRoute` понёс бы мусор.
 */
private fun ipFromSslipHost(host: String): String? {
    val suffix = ".sslip.io"
    if (!host.endsWith(suffix)) return null
    val encoded = host.removeSuffix(suffix)
    val octets = encoded.split('-')
    if (octets.size != 4) return null
    val numbers = octets.map { it.toIntOrNull() ?: return null }
    if (numbers.any { it !in 0..255 }) return null
    return numbers.joinToString(".")
}
