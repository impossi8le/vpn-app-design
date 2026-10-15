package com.impossi8le.vpnapp.domain.diagnostics

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Логи ядра в стиле реального вывода проходят через буфер без утечки.
 *
 * Ядро при высоком `verb` печатает тела PEM в составе строки лога. Тест повторяет
 * эту форму, чтобы буфер гарантировал отсутствие ключа даже если `verb` однажды
 * снова поднимут.
 */
class LogBufferScrubIntegrationTest {

    @Test
    fun `дамп ключа ядра не оставляет секрета в буфере`() {
        val buffer = LogBuffer()
        buffer.add("2026-10-13 09:20:11 WARNING: 'link-mtu' is used inconsistently")
        buffer.add("-----BEGIN OpenVPN Static key V1-----")
        buffer.add("deadbeefdeadbeefdeadbeefdeadbeef")
        buffer.add("-----END OpenVPN Static key V1-----")
        buffer.add("2026-10-13 09:20:12 TUN Error: tun_prop_error: route conflict")

        val text = buffer.snapshot().joinToString("\n")

        assertFalse(text.contains("deadbeef"), "ключ tls-auth не должен остаться")
        assertFalse(text.contains("BEGIN OpenVPN Static key"), "маркер блока тоже скрываем")
        assertTrue(text.contains("tun_prop_error"), "причина сбоя обязана остаться — ради неё всё и делается")
    }
}
