package com.impossi8le.vpnapp.domain.diagnostics

import java.io.File

/**
 * ЗАГЛУШКА. Полная реализация живёт в ветке Task 5 плана
 * (`2026-10-13-error-reporting-client.md`). Здесь реализация минимальна, но
 * рабочая: [TelemetryGraph] (Task 9) нужна конкретная [ReportQueue], переживающая
 * перезапуск, иначе события терялись бы между попытками отправки.
 *
 * Формат — JSONL, одна строка на событие: дописывание дешёвое, а частично
 * записанная строка отбрасывается при чтении.
 */
class FileReportQueue(
    private val file: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : ReportQueue {

    override suspend fun enqueue(json: String) {
        writeAll((readAll() + json).takeLast(maxEntries))
    }

    override suspend fun peek(): List<String> = readAll()

    override suspend fun remove(ids: List<String>) {
        if (ids.isEmpty()) return
        val wanted = ids.toSet()
        writeAll(readAll().filterNot { line -> idOf(line) in wanted })
    }

    override suspend fun size(): Int = readAll().size

    override suspend fun clear() {
        writeAll(emptyList())
    }

    private fun readAll(): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    /** Пишем через временный файл: обрыв посреди записи не должен оставить очередь пустой. */
    private fun writeAll(lines: List<String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(lines.joinToString("\n"))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun idOf(line: String): String? =
        Regex("\"event_id\"\\s*:\\s*\"([^\"]+)\"").find(line)?.groupValues?.get(1)

    companion object {
        const val DEFAULT_MAX_ENTRIES = 500
    }
}
