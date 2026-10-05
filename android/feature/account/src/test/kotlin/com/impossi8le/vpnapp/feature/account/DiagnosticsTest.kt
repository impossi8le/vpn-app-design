package com.impossi8le.vpnapp.feature.account

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Отчёт поддержке обязан содержать версию/состояние и НЕ содержать секретов.
 *
 * Тест держит две вещи разом. Первая: в отчёт действительно попадает то, по
 * чему поддержка понимает сборку и состояние (версия, SHA, состояние туннеля) —
 * иначе кнопка «отправить отчёт» отправляла бы бесполезный текст. Вторая,
 * важнее: в текст НЕ протекают Telegram ID, токен сессии и ключевой материал —
 * эти значения переданы на вход и обязаны быть отброшены.
 *
 * Строки-секреты выбраны непохожими на остальной текст, чтобы совпадение было
 * однозначным, а не случайным.
 */
class DiagnosticsTest {

    private val secretTelegramId = "TG_ID_999_SHOULD_NOT_LEAK"
    private val secretToken = "SESSION_TOKEN_999_SHOULD_NOT_LEAK"

    private fun input() = DiagnosticsInput(
        versionName = "1.2.3",
        versionCode = 456,
        gitSha = "abc1234",
        androidRelease = "14",
        deviceModel = "Pixel 7",
        tunnelState = "Disconnected",
        configId = "cfg-42",
        configName = "Франкфурт",
        serverHostPort = "de1.example.com:1194",
        timestampIso = "2026-10-06T12:00:00Z",
        telegramId = secretTelegramId,
        sessionToken = secretToken,
    )

    @Test
    fun `отчёт содержит версию, SHA и состояние туннеля`() {
        val report = buildDiagnostics(input())

        assertTrue(report.contains("1.2.3"), "версия обязана быть в отчёте")
        assertTrue(report.contains("456"), "versionCode обязана быть в отчёте")
        assertTrue(report.contains("abc1234"), "git SHA обязан быть в отчёте")
        assertTrue(report.contains("Disconnected"), "состояние туннеля обязано быть в отчёте")
        assertTrue(report.contains("cfg-42"), "id подключения обязан быть в отчёте")
    }

    @Test
    fun `отчёт не содержит Telegram ID и токен сессии`() {
        val report = buildDiagnostics(input())

        assertFalse(
            report.contains(secretTelegramId),
            "Telegram ID — персональные данные, в отчёт попадать не должны",
        )
        assertFalse(
            report.contains(secretToken),
            "токен сессии — секрет доступа, в отчёт попадать не должен",
        )
    }

    @Test
    fun `отсутствие подключения печатается как нет, а не пустой строкой`() {
        val report = buildDiagnostics(
            input().copy(configId = null, configName = null, serverHostPort = null),
        )

        assertTrue(report.contains("Подключение: нет"))
        assertTrue(report.contains("Сервер: неизвестно"))
    }
}
