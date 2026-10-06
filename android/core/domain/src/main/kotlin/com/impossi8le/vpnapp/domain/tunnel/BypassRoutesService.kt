package com.impossi8le.vpnapp.domain.tunnel

/**
 * Источник списка обходов.
 *
 * Возвращает список, а не `Result`: неудача и пустой список означают одно —
 * обходов нет. Подключение от этого не блокируется, защита не ломается, и
 * различать «сервер молчит» и «обходов нет» вызывающему нечего.
 */
interface BypassRoutesService {
    suspend fun routes(): List<BypassRoute>
}
