package com.impossi8le.vpnapp.feature.account

/**
 * Входные данные для отчёта поддержке.
 *
 * Отдельная структура, а не длинный список аргументов: у неё есть ГРАНИЦА
 * ПРИВАТНОСТИ, и эту границу видно по полям. [telegramId] и [sessionToken]
 * лежат в контексте вызова (экран их видит), но в текст отчёта НЕ попадают
 * никогда — см. [buildDiagnostics]. Они вынесены сюда явно, чтобы тест мог
 * ДОКАЗАТЬ, что они не утекают, а не полагаться на «вроде не печатали».
 *
 * Всё, что попадает в отчёт, — либо публичное (версия сборки, модель телефона),
 * либо техническое и обезличенное (состояние туннеля, id и имя подключения).
 * Ни ключей, ни содержимого `.ovpn`, ни токена, ни Telegram ID.
 */
data class DiagnosticsInput(
    val versionName: String,
    val versionCode: Int,
    val gitSha: String,
    val androidRelease: String,
    val deviceModel: String,
    val tunnelState: String,
    val configId: String?,
    val configName: String?,
    val serverHostPort: String?,
    val timestampIso: String,
    // --- Граница приватности: в отчёт не выводятся ---
    /** Телеграм-ID: персональные данные, в отчёт не идут. */
    val telegramId: String? = null,
    /** Токен сессии: секрет доступа, в отчёт не идёт. */
    val sessionToken: String? = null,
)

/**
 * Текст диагностического отчёта для поддержки.
 *
 * Чистая функция: тот же вход даёт тот же текст на любой машине, поэтому её
 * проверяют тестом без Android. Время приходит снаружи ([DiagnosticsInput.timestampIso]),
 * а не берётся из часов, — иначе тест был бы недетерминирован.
 *
 * **Граница приватности — whitelist, а не blacklist.** Здесь перечислено ровно
 * то, что МОЖНО печатать. Полей [DiagnosticsInput.telegramId] и
 * [DiagnosticsInput.sessionToken] в теле функции нет вовсе — их нельзя случайно
 * «дописать» в шаблон, потому что они не читаются. Содержимое `.ovpn` и любой
 * ключевой материал не передаются даже на вход: их тут негде взять.
 *
 * Отсутствующие значения — честное «нет», а не пустая строка: «Подключение: »
 * читалось бы как обрыв, а «нет» говорит «данных нет».
 */
fun buildDiagnostics(input: DiagnosticsInput): String = buildString {
    appendLine("Отчёт VPN-приложения")
    appendLine("Собран: ${input.timestampIso}")
    appendLine("Версия: ${input.versionName} (${input.versionCode})")
    appendLine("Сборка git: ${input.gitSha}")
    appendLine("Android: ${input.androidRelease}")
    appendLine("Модель: ${input.deviceModel}")
    appendLine("Состояние туннеля: ${input.tunnelState}")
    append(
        if (input.configId != null && input.configName != null) {
            "Подключение: ${input.configId} — ${input.configName}\n"
        } else {
            "Подключение: нет\n"
        },
    )
    append("Сервер: ${input.serverHostPort ?: "неизвестно"}")
}
