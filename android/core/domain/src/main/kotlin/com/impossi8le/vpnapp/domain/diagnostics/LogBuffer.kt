package com.impossi8le.vpnapp.domain.diagnostics

/**
 * Ограниченный буфер логов в памяти.
 *
 * Два лимита, а не один: строки ядра бывают короткими, а дампы большими, поэтому
 * потолок по количеству защищает от тысяч строк, а по байтам — от одной огромной.
 * Срабатывает тот, что наступит раньше.
 *
 * Не потокобезопасен намеренно: вызывается из одного места (колбэк ядра), а
 * синхронизацию добавляет владелец, если понадобится второй писатель. Примитив
 * без нужды усложнять не стоит.
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

    fun snapshot(): List<String> = lines.toList()

    fun size(): Int = lines.size

    fun clear() {
        lines.clear()
        bytes = 0
    }

    private fun trim() {
        while (lines.size > maxLines || (bytes > maxBytes && lines.size > 1)) {
            val removed = lines.removeFirst()
            bytes -= removed.length
        }
    }

    companion object {
        const val DEFAULT_MAX_LINES = 2000
        const val DEFAULT_MAX_BYTES = 262144 // 256 КБ
    }
}
