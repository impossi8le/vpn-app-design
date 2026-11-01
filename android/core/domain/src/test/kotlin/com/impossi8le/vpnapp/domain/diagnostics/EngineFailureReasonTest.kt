package com.impossi8le.vpnapp.domain.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Причина сбоя — словами, понятными человеку (issue #64).
 *
 * Ядро пишет `Session invalidated: KEEPALIVE_TIMEOUT`, экран молчал. Эти строки
 * взяты из настоящих отчётов с устройства, а не придуманы.
 */
class EngineFailureReasonTest {

    @Test
    fun `таймаут сервера — «сервер не отвечает», а не «сессия прервана»`() {
        // Оба маркера в одной строке: точная причина — таймаут.
        assertEquals(
            "Сервер не отвечает",
            describeEngineFailure("Session invalidated: KEEPALIVE_TIMEOUT"),
        )
    }

    @Test
    fun `отказ авторизации назван своим именем`() {
        assertEquals(
            "Сервер отклонил доступ — проверьте подписку",
            describeEngineFailure("AUTH_FAILED"),
        )
    }

    @Test
    fun `сбой рукопожатия отделён от таймаута`() {
        assertEquals(
            "Не удалось установить защищённое соединение",
            describeEngineFailure("TLS handshake failed"),
        )
    }

    @Test
    fun `пропажа сети видна отдельно`() {
        assertEquals(
            "Нет доступа к сети",
            describeEngineFailure("UDP send exception: send: Network is unreachable"),
        )
    }

    @Test
    fun `обычная строка причины не выдумывает`() {
        assertNull(
            describeEngineFailure("event: name=CONNECTED error=false fatal=false info="),
            "молчать честнее, чем показать причину, которой нет",
        )
    }
}