package com.impossi8le.vpnapp.domain.diagnostics

import java.io.File

/**
 * Очередь на JSONL-файле.
 *
 * Формат — одна строка на событие: дописывание дешёвое, а частично записанная
 * строка отбрасывается при чтении, поэтому обрыв питания не рождает битый файл,
 * из-за которого очередь не прочитается вовсе.
 *
 * Вытеснение — по количеству строк, а не по времени: `maxEntries` проще и
 * предсказуемее, а срок хранения задаёт сервер.
 */
class FileReportQueue(
    private val file: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : ReportQueue {

    override suspend fun enqueue(json: String) {
        withFileLock { writeAll((readAll() + json).takeLast(maxEntries)) }
    }

    override suspend fun peek(): List<String> = withFileLock { readAll() }

    override suspend fun remove(ids: List<String>) {
        if (ids.isEmpty()) return
        val wanted = ids.toSet()
        withFileLock { writeAll(readAll().filterNot { line -> idOf(line) in wanted }) }
    }

    override suspend fun size(): Int = withFileLock { readAll().size }

    override suspend fun clear() {
        withFileLock { writeAll(emptyList()) }
    }

    /**
     * Блокировка общая НА ФАЙЛ, а не на объект.
     *
     * Экземпляров очереди в приложении два: запись из UI (`AppGraph.telemetryQueue`)
     * и чтение с удалением в воркере (`TelemetryGraph.from`). Они смотрят в один и
     * тот же файл. Без общей блокировки два read-modify-write накладываются —
     * и событие, записанное UI, стирается записью воркера: очередь теряет данные
     * молча, а потеря выглядит как «событий не было».
     *
     * Ключ — канонический путь, поэтому блокировка работает независимо от того,
     * какой экземпляр её берёт и как собран путь к файлу.
     */
    private inline fun <T> withFileLock(block: () -> T): T =
        synchronized(lockFor(file)) { block() }

    private fun readAll(): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    /**
     * Пишем через временный файл: обрыв посреди записи не должен оставить очередь
     * пустой. Атомарный переезд — тот же приём, что в `FileProfileStore`.
     */
    private fun writeAll(lines: List<String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(lines.joinToString("\n"))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    /** Достать `event_id` из строки очереди; неизвестное значение вытеснять нельзя. */
    private fun idOf(line: String): String? =
        Regex("\"event_id\"\\s*:\\s*\"([^\"]+)\"").find(line)?.groupValues?.get(1)

    companion object {
        const val DEFAULT_MAX_ENTRIES = 500

        /**
         * Замки по каноническому пути файла.
         *
         * `ComputeIfAbsent` вместо простого поля: два экземпляра получают ОДИН
         * объект-замок, поэтому блокировка общая. Канонический путь — чтобы
         * разные написания одного файла (`./q.jsonl` и `q.jsonl`) не дали двух
         * разных замков. `computeIfAbsent` на `ConcurrentHashMap` атомарен, так
         * что два потока не создадут по замку.
         */
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

        private fun lockFor(file: File): Any {
            val key = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
            return locks.computeIfAbsent(key) { Any() }
        }
    }
}
