package com.impossi8le.vpnapp.domain.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Повторы в журнале ядра схлопываются (issue #66).
 *
 * Строка взята из отчёта 04.11: при потере сети она повторялась сотни раз и
 * вытеснила из буфера всё остальное.
 */
class LogRepeatCollapserTest {

    private val flood = "UDP send exception: send: Network is unreachable"

    @Test
    fun `сотни повторов дают одну строку и счётчик`() {
        val c = LogRepeatCollapser()
        val written = mutableListOf<String>()

        repeat(500) { c.accept(flood)?.let(written::add) }
        c.flush()?.let(written::add)

        assertEquals(2, written.size, "должна остаться строка и её счётчик")
        assertEquals(flood, written[0])
        assertEquals("… повторено 499 раз", written[1])
    }

    @Test
    fun `одиночная строка пишется без счётчика`() {
        val c = LogRepeatCollapser()
        assertEquals(flood, c.accept(flood))
        assertNull(c.flush(), "повтора не было — счётчик не нужен")
    }

    @Test
    fun `смена строки закрывает серию про предыдущую`() {
        val c = LogRepeatCollapser()
        repeat(3) { c.accept(flood) }

        val next = c.accept("event: name=RECONNECTING error=false fatal=false info=")

        assertEquals(
            "… повторено 2 раз\nevent: name=RECONNECTING error=false fatal=false info=",
            next,
            "хвост обязан относиться к предыдущей серии, а не к новой строке",
        )
    }

    @Test
    fun `пустые строки между повторами не разрывают серию`() {
        val c = LogRepeatCollapser()
        c.accept(flood)
        assertNull(c.accept(""))
        assertNull(c.accept(flood))
        c.accept("")

        assertEquals("… повторено 1 раз", c.summary())
    }

    @Test
    fun `разные строки подряд пишутся как есть`() {
        val c = LogRepeatCollapser()
        assertEquals("a", c.accept("a"))
        assertEquals("b", c.accept("b"))
        assertEquals("c", c.accept("c"))
    }
}