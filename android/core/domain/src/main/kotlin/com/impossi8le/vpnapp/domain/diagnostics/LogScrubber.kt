package com.impossi8le.vpnapp.domain.diagnostics

/**
 * Удаление секретов из строки лога перед буферизацией.
 *
 * Ветка D (Task 9–10) полагается на фактическое поведение скраббера: собранный по
 * кнопке отчёт обязан содержать причину сбоя и НЕ содержать ключевого материала,
 * поэтому здесь реализована та же логика, что в Task 1/8 плана
 * (`2026-10-13-error-reporting-client.md`).
 *
 * Что вырезается: блоки PEM (лимитированные BEGIN/END), `Bearer <token>`,
 * `token=`, `device_nonce=`. Что не вырезается: обычный текст лога — он нужен
 * целиком, обрезать его значило бы терять причину сбоя.
 */
object LogScrubber {

    private const val REDACTED = "[REDACTED]"

    private val PEM_BLOCK = Regex(
        "-----BEGIN [A-Z0-9 ]+-----[\\s\\S]*?-----END [A-Z0-9 ]+-----",
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

    /**
     * Скраб потока строк с учётом МНОГОСТРОЧНЫХ блоков PEM.
     *
     * Ядро печатает `BEGIN`/тело/`END` тремя отдельными строками, поэтому
     * построчный скраб их не поймает: ключ утечёт построчно. Склеиваем в окне и
     * режем блок целиком.
     */
    fun scrubStream(lines: List<String>): List<String> {
        val joined = lines.joinToString("\n")
        val scrubbed = scrub(joined)
        return scrubbed.split("\n")
    }
}
