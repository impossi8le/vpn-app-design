package com.impossi8le.vpnapp.domain.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Кольцевой буфер логов.
 *
 * Логи пишутся ВСЕГДА, включая релиз: иначе в релизной сборке собирать нечего, и
 * причина сбоя на чужом телефоне остаётся невидимой (сейчас логи стоят под
 * `BuildConfig.DEBUG`). Буфер в памяти ограничен, поэтому старые строки
 * вытесняются — иначе долгая сессия съест память.
 *
 * Скраббер применяется здесь, на входе: секрет не должен попасть в буфер вообще.
 */
class LogBufferTest {

    @Test
    fun `строки накапливаются в порядке добавления`() {
        val buffer = LogBuffer()
        buffer.add("первая")
        buffer.add("вторая")

        assertEquals(listOf("первая", "вторая"), buffer.snapshot())
    }

    @Test
    fun `при превышении лимита вытесняется самое старое`() {
        val buffer = LogBuffer(maxLines = 3)
        repeat(5) { buffer.add("строка $it") }

        assertEquals(listOf("строка 2", "строка 3", "строка 4"), buffer.snapshot())
    }

    @Test
    fun `секрет не попадает в буфер`() {
        val buffer = LogBuffer()
        buffer.add("key: -----BEGIN PRIVATE KEY-----SECRETDATA-----END PRIVATE KEY-----")

        val snapshot = buffer.snapshot().joinToString("\n")
        assertFalse(snapshot.contains("SECRETDATA"), "скраббер обязан работать на входе, а не на выходе")
        assertTrue(snapshot.contains("[REDACTED]"))
    }

    @Test
    fun `лимит по байтам тоже вытесняет`() {
        val buffer = LogBuffer(maxLines = 1000, maxBytes = 50)
        buffer.add("a".repeat(30))
        buffer.add("b".repeat(30))

        val snapshot = buffer.snapshot()
        assertTrue(snapshot.size < 2, "две строки по 30 байт не влезают в 50 байт")
        assertTrue(snapshot.any { it.startsWith("b") }, "свежая строка обязана остаться")
    }

    @Test
    fun `параллельная запись и чтение не ломают буфер`() {
        val buffer = LogBuffer(maxLines = 500)
        val threads = (1..8).map { n ->
            Thread { repeat(200) { buffer.add("строка $n-$it") } }
        }
        threads.forEach { it.start() }
        // Чтение идёт ОДНОВРЕМЕННО с записью — именно здесь ловится гонка.
        repeat(200) { buffer.snapshot() }
        threads.forEach { it.join() }

        assertTrue(buffer.snapshot().isNotEmpty(), "буфер обязан пережить параллельный доступ")
    }

    @Test
    fun `clear опустошает буфер`() {
        val buffer = LogBuffer()
        buffer.add("что-то")
        buffer.clear()

        assertEquals(0, buffer.size())
    }
}
