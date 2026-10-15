package com.impossi8le.vpnapp.feature.account

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Сборка того, что уходит по кнопке.
 *
 * Отдельная функция, а не код внутри обработчика нажатия: границу приватности
 * нужно ДОКАЗАТЬ тестом. Здесь проверяется, что ключевой материал профиля в
 * собранный пакет не попадает, а причина сбоя из логов — попадает.
 */
class ReportCollectorTest {

    @Test
    fun `собранный отчёт не содержит ключей профиля`() {
        val profile = """
            client
            remote 192.0.2.10 443 udp
            <key>
            -----BEGIN PRIVATE KEY-----
            SUPERSECRETKEYDATA
            -----END PRIVATE KEY-----
            </key>
        """.trimIndent()

        val collected = ReportCollector.build(
            reportText = "Отчёт VPN-приложения",
            logs = listOf("TUN Error: tun_prop_error"),
            profileText = profile,
        )

        assertFalse(collected.config.contains("SUPERSECRETKEYDATA"), "ключ не должен уйти на сервер")
        assertTrue(collected.config.contains("remote 192.0.2.10 443 udp"), "структура нужна для разбора")
        assertTrue(collected.logs.contains("tun_prop_error"), "причина сбоя — главное в отчёте")
    }

    @Test
    fun `без профиля конфиг пустой, а не null-строка`() {
        val collected = ReportCollector.build("отчёт", listOf("лог"), profileText = null)

        assertTrue(collected.config.isEmpty(), "нет профиля — нет части в multipart")
    }

    @Test
    fun `текст отчёта проходит как есть`() {
        val text = "Отчёт VPN-приложения\nВерсия: 102 (102)"
        val collected = ReportCollector.build(text, emptyList(), null)

        assertTrue(collected.text.contains("Версия: 102"), "шаблон отчёта не переписываем")
    }
}
