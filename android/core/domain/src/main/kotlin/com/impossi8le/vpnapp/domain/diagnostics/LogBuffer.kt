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
    /**
     * Строки «как пришли» — с построчным скрабом, но БЕЗ склейки блоков.
     *
     * Слитые строки хранить нельзя: многострочный PEM виден только целиком, и
     * склейку делает [snapshot] — там, где буфер отдаёт наружу готовый лог.
     */
    private val raw = ArrayDeque<String>()
    private var bytes = 0

    fun add(line: String) {
        val scrubbed = LogScrubber.scrub(line)
        raw.addLast(scrubbed)
        bytes += scrubbed.length
        trim()
    }

    /**
     * Отдаём лог после склейки многострочных блоков: только здесь видно, что
     * `BEGIN` и `END` — части одного секрета.
     */
    fun snapshot(): List<String> = LogScrubber.scrubStream(raw.toList())

    fun size(): Int = raw.size

    fun clear() {
        raw.clear()
        bytes = 0
    }

    private fun trim() {
        while (raw.size > maxLines || (bytes > maxBytes && raw.size > 1)) {
            val removed = raw.removeFirst()
            bytes -= removed.length
        }
    }

    companion object {
        const val DEFAULT_MAX_LINES = 2000
        const val DEFAULT_MAX_BYTES = 262144 // 256 КБ
    }
}
