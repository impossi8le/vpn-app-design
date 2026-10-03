package com.impossi8le.vpnapp.domain.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Выправление `verb` в тексте профиля.
 *
 * Это защита от утечки приватного ключа: у ядра OpenVPN 3 уровень логирования
 * берётся ИЗ ТЕКСТА профиля, а не из нашего конфига, и боевой профиль содержит
 * `verb 3` — то есть при прямом пробросе тела PEM уходят в logcat.
 *
 * Проверяется здесь, на JVM: операция текстовая и в ядре не нуждается.
 */
class ProfileSanitizerTest {

    @Test
    fun `verb 3 понижается до безопасного`() {
        val result = ProfileSanitizer.sanitizeVerb("client\ndev tun\nverb 3\nremote host 1194\n")

        assertTrue(result.text.contains("verb 1"), "уровень обязан быть понижен")
        assertFalse(result.text.contains("verb 3"), "опасного значения остаться не должно")
        assertEquals(3, result.originalVerb, "исходное значение надо сохранить для журнала")
        assertTrue(result.lowered)
        assertTrue(result.wasUnsafe, "verb 3 — это тот порог, на котором печатаются PEM")
    }

    @Test
    fun `безопасный verb не меняется`() {
        val source = "client\nverb 1\nremote host 1194\n"
        val result = ProfileSanitizer.sanitizeVerb(source)

        assertEquals(source, result.text, "менять нечего — текст обязан остаться прежним")
        assertEquals(1, result.originalVerb)
        assertFalse(result.lowered)
        assertFalse(result.wasUnsafe)
    }

    @Test
    fun `verb 0 не считается опасным`() {
        val result = ProfileSanitizer.sanitizeVerb("verb 0\n")
        assertEquals(0, result.originalVerb)
        assertFalse(result.wasUnsafe)
    }

    @Test
    fun `профиль без verb не меняется`() {
        val source = "client\ndev tun\nremote host 1194 udp\n"
        val result = ProfileSanitizer.sanitizeVerb(source)

        assertEquals(source, result.text)
        assertNull(result.originalVerb, "если уровня нет, значит его и не задавали")
        assertFalse(result.lowered)
    }

    @Test
    fun `второй verb удаляется, а не остаётся в профиле`() {
        // Иначе ядро взяло бы ПОСЛЕДНЕЕ значение и второй `verb 3` обошёл бы
        // нашу правку — то есть защита обходится одной лишней строкой.
        val result = ProfileSanitizer.sanitizeVerb("verb 1\nremote host 1194\nverb 3\n")

        assertEquals(1, Regex("(?m)^verb ").findAll(result.text).count(), "директива обязана остаться одна")
        assertFalse(result.text.contains("verb 3"), "второе, опасное значение обязано исчезнуть")
    }

    @Test
    fun `дубликат опасного значения тоже понижается`() {
        val result = ProfileSanitizer.sanitizeVerb("verb 5\nverb 5\n")

        assertEquals(1, Regex("(?m)^verb ").findAll(result.text).count())
        assertTrue(result.text.contains("verb 1"))
    }

    @Test
    fun `ведущие пробелы не мешают распознать директиву`() {
        val result = ProfileSanitizer.sanitizeVerb("client\n   verb 3   \n")

        assertEquals(3, result.originalVerb, "директива с отступом — та же директива")
        assertFalse(result.text.contains("verb 3"))
    }

    @Test
    fun `хвостовой комментарий не мешает`() {
        // В реальных профилях после значения бывает комментарий.
        val result = ProfileSanitizer.sanitizeVerb("verb 3 # подробный лог\n")

        assertEquals(3, result.originalVerb)
        assertFalse(result.text.contains("verb 3"))
    }

    @Test
    fun `похожие директивы не трогаются`() {
        // `verbose` и `verbosity` — другие слова; трогать их значит ломать профиль.
        val source = "client\nverbose yes\nverbosity 4\nremote host 1194\n"
        val result = ProfileSanitizer.sanitizeVerb(source)

        assertEquals(source, result.text)
        assertNull(result.originalVerb)
    }

    @Test
    fun `блоки PEM остаются нетронутыми`() {
        // Ключи не должны даже слегка измениться: сдвиг в PEM = нерабочий профиль.
        val keyBody = (1..20).joinToString("\n") { "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcd$it" }
        val source = "client\nverb 3\n<key>\n-----BEGIN PRIVATE KEY-----\n$keyBody\n-----END PRIVATE KEY-----\n</key>\n"

        val result = ProfileSanitizer.sanitizeVerb(source)

        assertTrue(result.text.contains(keyBody), "тело ключа обязано остаться байт в байт")
        assertTrue(result.text.contains("-----BEGIN PRIVATE KEY-----"))
        assertTrue(result.text.contains("</key>"))
        assertFalse(result.text.contains("verb 3"))
    }

    @Test
    fun `остальные директивы сохраняют порядок`() {
        val source = "client\ndev tun\nverb 3\nremote host 1194 udp\nredirect-gateway def1 bypass-dhcp\n"
        val result = ProfileSanitizer.sanitizeVerb(source)

        val lines = result.text.split('\n').filter { it.isNotBlank() }
        assertEquals("client", lines[0])
        assertEquals("dev tun", lines[1])
        assertEquals("verb 1", lines[2], "правка на месте, порядок не сдвинут")
        assertEquals("remote host 1194 udp", lines[3])
        assertEquals("redirect-gateway def1 bypass-dhcp", lines[4])
    }

    @Test
    fun `боевой уровень 3 распознаётся как опасный`() {
        // Ровно то значение, что стоит в продакшен-профиле: при нём ядро печатает
        // тела PEM. Тест напоминает, что это не гипотеза.
        val result = ProfileSanitizer.sanitizeVerb("verb 3\n")
        assertTrue(result.wasUnsafe)
        assertEquals(1, result.text.trim().removePrefix("verb ").toInt())
    }
}
