package com.impossi8le.vpnapp.domain.diagnostics

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Файловая очередь недоотправленного.
 *
 * Файл, а не память: приложение закрывается между попытками отправки, и событие,
 * оставшееся в памяти, потерялось бы. Ограничение по количеству — чтобы очередь
 * не росла бесконечно, если сеть недоступна неделю.
 */
class FileReportQueueTest {

    @Test
    fun `положил и прочитал обратно`() = runBlocking {
        val dir = createTempDirectory().toFile()
        val queue = FileReportQueue(File(dir, "queue.jsonl"))
        queue.enqueue("""{"event_id":"a"}""")
        queue.enqueue("""{"event_id":"b"}""")

        assertEquals(listOf("""{"event_id":"a"}""", """{"event_id":"b"}"""), queue.peek())
    }

    @Test
    fun `переживает пересоздание`() = runBlocking {
        val dir = createTempDirectory().toFile()
        val file = File(dir, "queue.jsonl")
        FileReportQueue(file).enqueue("""{"event_id":"a"}""")

        val reopened = FileReportQueue(file)
        assertEquals(1, reopened.size(), "очередь обязана читаться с диска")
    }

    @Test
    fun `remove убирает отправленное`() = runBlocking {
        val dir = createTempDirectory().toFile()
        val queue = FileReportQueue(File(dir, "queue.jsonl"))
        queue.enqueue("""{"event_id":"a"}""")
        queue.enqueue("""{"event_id":"b"}""")

        queue.remove(listOf("a"))

        assertEquals(listOf("""{"event_id":"b"}"""), queue.peek())
    }

    @Test
    fun `очередь не растёт бесконечно`() = runBlocking {
        val dir = createTempDirectory().toFile()
        val queue = FileReportQueue(File(dir, "queue.jsonl"), maxEntries = 3)
        repeat(5) { queue.enqueue("""{"event_id":"$it"}""") }

        assertEquals(3, queue.size(), "старые обязаны вытесняться")
        assertTrue(queue.peek().last().contains("\"4\""), "самое свежее обязано остаться")
    }

    @Test
    fun `два экземпляра над одним файлом не теряют события`() = runBlocking {
        val dir = createTempDir()
        val file = File(dir, "queue.jsonl")
        // Так оно и живёт в приложении: запись из UI и чтение воркером — разные
        // объекты над ОДНИМ файлом. Без общей блокировки второй read-modify-write
        // затирает первый, и событие исчезает молча.
        val writer = FileReportQueue(file)
        val worker = FileReportQueue(file)

        writer.enqueue("""{"event_id":"from-ui"}""")
        worker.remove(emptyList()) // рабочий цикл читает и перезаписывает тот же файл
        writer.enqueue("""{"event_id":"from-ui-2"}""")

        val all = FileReportQueue(file).peek()
        assertTrue(all.any { it.contains("from-ui") }, "событие из UI не должно пропасть")
        assertTrue(all.any { it.contains("from-ui-2") }, "второе событие тоже обязано остаться")
    }
}
