package com.impossi8le.vpnapp.domain.diagnostics

/**
 * ЗАГЛУШКА. Полная реализация живёт в ветке Task 3/8 плана
 * (`2026-10-13-error-reporting-client.md`). Здесь объявлены ровно те сигнатуры,
 * которые нужны ветке D (Task 10): [add], [snapshot], [size], [clear].
 *
 * Логи пишутся ВСЕГДА, включая релиз: иначе в релизной сборке собирать нечего.
 * Ограничение по количеству и байтам защищает память длинной сессии.
 */
class LogBuffer(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    private val lines = ArrayDeque<String>()
    private var bytes = 0

    fun add(line: String) {
        val scrubbed = LogScrubber.scrub(line)
        lines.addLast(scrubbed)
        bytes += scrubbed.length
        trim()
    }

    fun snapshot(): List<String> = LogScrubber.scrubStream(lines.toList())

    fun size(): Int = lines.size

    fun clear() {
        lines.clear()
        bytes = 0
    }

    private fun trim() {
        while (lines.size > maxLines || (bytes > maxBytes && lines.size > 1)) {
            bytes -= lines.removeFirst().length
        }
    }

    companion object {
        const val DEFAULT_MAX_LINES = 2000
        const val DEFAULT_MAX_BYTES = 262144
    }
}
