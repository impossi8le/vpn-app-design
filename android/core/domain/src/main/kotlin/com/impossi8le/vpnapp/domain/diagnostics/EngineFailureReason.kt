package com.impossi8le.vpnapp.domain.diagnostics

/**
 * Причина сбоя соединения — из строки журнала ядра, человеческим языком.
 *
 * **Зачем.** Ядро уже знает, почему сессия кончилась (`Session invalidated:
 * KEEPALIVE_TIMEOUT`), но пользователь этого не видит: экран возвращался к
 * «Не подключено» и подсказке «нажмите Подключить», как будто ничего не
 * происходило. Со стороны это «кнопка не работает».
 *
 * **Почему здесь, а не в сервисе.** Это чистое решение над текстом, и оно
 * проверяется тестом. Сервис лишь передаёт строку, экран — показывает готовый
 * результат; ни один из них не разбирает формулировки ядра сам.
 *
 * Возвращает `null`, если строка ни о чём не говорит: тогда лучше промолчать,
 * чем показать выдуманную причину.
 */
fun describeEngineFailure(line: String): String? = when {
    // Порядок важен: «Session invalidated: KEEPALIVE_TIMEOUT» содержит оба
    // маркера, а точная причина — таймаут.
    line.contains("KEEPALIVE_TIMEOUT", ignoreCase = true) ->
        "Сервер не отвечает"

    // Именно эта строка — самый частый отказ на живых устройствах: сервер не
    // ответил ни на один пакет, и ядро перебирает адреса по кругу.
    line.contains("Server poll timeout", ignoreCase = true) ->
        "Сервер не отвечает"

    line.contains("AUTH_FAILED", ignoreCase = true) ->
        "Сервер отклонил доступ — проверьте подписку"

    // Согласование параметров data-channel не удалось. Встречено на живом
    // устройстве вместе с потерей сети, поэтому и формулировка про соединение,
    // а не про сервер.
    line.contains("KEV_NEGOTIATE_ERROR", ignoreCase = true) ->
        "Сервер не смог согласовать соединение — попробуйте другой конфиг"

    line.contains("TLS handshake failed", ignoreCase = true) ||
        line.contains("TLS Error", ignoreCase = true) ||
        line.contains("tls-error", ignoreCase = true) ->
        "Не удалось установить защищённое соединение"

    line.contains("Session invalidated", ignoreCase = true) ->
        "Соединение прервано сервером"

    line.contains("Network is unreachable", ignoreCase = true) ->
        "Нет доступа к сети"

    else -> null
}