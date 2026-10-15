package com.impossi8le.vpnapp.domain.diagnostics

/**
 * Удаление секретов из строки лога перед буферизацией.
 *
 * Зачем именно так: логи ядра уходят на приёмный сервер, а ядро при `verb >= 3`
 * печатает тела PEM — то есть приватный ключ. Уровень понижается
 * (`ProfileSanitizer`), но полагаться только на это нельзя: ключ может попасть в
 * лог из сетевого слоя, из дампа окружения или из будущей правки профиля.
 *
 * Поэтому скраббер — вторая линия, и он работает ДО буфера: секрет не должен
 * лежать в памяти устройства вообще, иначе утечёт при дампе или отладке.
 *
 * Что вырезается: блоки PEM (лимитированные BEGIN/END), `Bearer <token>`,
 * `token=`, `device_nonce=`. Что не вырезается: обычный текст лога — он нужен
 * целиком, обрезать его значило бы терять причину сбоя.
 */
object LogScrubber {

    private const val REDACTED = "[REDACTED]"

    // Классы символов включают и строчные: заголовок `tls-auth` —
    // `OpenVPN Static key V1`, и только на `[A-Z0-9 ]` он не ловился бы.
    // Дефис (последним, чтобы не читался как диапазон) и подчёркивание нужны для
    // меток вроде `OpenVPN tls-crypt-v2 client key`.
    private val PEM_BLOCK = Regex(
        "-----BEGIN [A-Za-z0-9 _-]+-----[\\s\\S]*?-----END [A-Za-z0-9 _-]+-----",
        RegexOption.MULTILINE,
    )
    private val BEARER = Regex("(Bearer\\s+)\\S+", RegexOption.IGNORE_CASE)
    private val QUOTED_TOKEN = Regex(
        "(session_token|token|device_nonce)([\"']?\\s*[:=]\\s*[\"']?)([^\\s\"',}]+)",
        RegexOption.IGNORE_CASE,
    )

    fun scrub(line: String): String {
        var result = line.replace(PEM_BLOCK, REDACTED)
        result = result.replace(BEARER) { "${it.groupValues[1]}$REDACTED" }
        result = result.replace(QUOTED_TOKEN) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}$REDACTED"
        }
        return result
    }

    fun scrubAll(lines: List<String>): List<String> = lines.map(::scrub)
}
