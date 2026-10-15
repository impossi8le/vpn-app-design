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
}
