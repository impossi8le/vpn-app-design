package com.impossi8le.vpnapp.feature.auth

/**
 * Проверка входящей ссылки входа.
 *
 * **Зачем это вообще нужно.** Вход идёт по кастомной схеме `vpnapp://login`, а
 * кастомную схему на Android не «закрепляет» за приложением никто: любой APK
 * может объявить её у себя. Полноценная защита — Android App Links (`https://`
 * плюс `assetlinks.json`), но она требует домена, которого пока нет.
 *
 * Что остаётся, когда домена нет: **не полагаться на схему как на доказательство**.
 * Ссылка не должна сообщать приложению ничего, что давало бы сессию. Проверяем,
 * что пришедший код совпадает с тем, который мы сами только что сгенерировали:
 * чужой или устаревший код отбрасывается.
 *
 * Чего это НЕ даёт: перехватить ссылку и показать фишинговый экран чужое
 * приложение всё ещё может. Но получить из этого сессию — нет: `secret`
 * остаётся на устройстве и в ссылке не передаётся (контракт §1).
 */
object DeepLinkGuard {

    /** Схема и хост, которые мы объявили в манифесте. */
    const val SCHEME = "vpnapp"
    const val HOST = "login"

    /**
     * Код из ссылки, если она нам подходит.
     *
     * @param uri входящая ссылка из намерения.
     * @param expectedCode код операции, начатой ЭТИМ приложением. `null`, если
     *   операция ещё не начата или уже завершена — тогда ссылку принимать некуда.
     */
    fun publicCodeFrom(uri: String?, expectedCode: String?): String? {
        if (uri == null || expectedCode == null) return null
        if (!uri.startsWith("$SCHEME://$HOST")) return null

        val code = extractParam(uri, "code") ?: return null

        // Совпадение с ожидаемым кодом — единственное, что делает ссылку
        // осмысленной. Без этого сторонний APK, объявивший нашу схему, мог бы
        // подсунуть свой код и увести пользователя в чужую операцию входа.
        return if (code == expectedCode) code else null
    }

    /**
     * Разбор параметра из URI без внешних зависимостей.
     *
     * Намеренно не `android.net.Uri`: эта проверка должна быть проверяема
     * юнит-тестом на JVM, а не только на устройстве.
     */
    private fun extractParam(uri: String, name: String): String? {
        val query = uri.substringAfter('?', missingDelimiterValue = "").ifEmpty { return null }
        return query.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == name }
            ?.get(1)
            ?.takeIf { it.isNotEmpty() }
    }
}
