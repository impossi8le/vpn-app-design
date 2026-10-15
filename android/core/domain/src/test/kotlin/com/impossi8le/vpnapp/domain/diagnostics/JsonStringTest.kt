package com.impossi8le.vpnapp.domain.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Экранирование значения для JSON.
 *
 * `meta` отчёта собирается склейкой, и туда попадает модель телефона — текст из
 * системы, который мы не контролируем. Кавычка или обратный слэш в нём сломали бы
 * тело запроса, и отчёт не доехал бы. Поэтому проверяется не «похоже на верное»,
 * а разбором: результат обязан читаться штатным парсером.
 */
class JsonStringTest {

    @Test
    fun `обычная строка оборачивается в кавычки и разбирается`() {
        val encoded = jsonString("Pixel 7")

        assertEquals("Pixel 7", Json.parseToJsonElement(encoded).jsonPrimitive.content)
    }

    @Test
    fun `кавычка внутри не ломает JSON`() {
        val encoded = jsonString("""Модель "X"""")

        // Главное здесь — что разбор НЕ бросил: до экранирования склейка дала бы
        // неразбираемое тело.
        assertEquals("""Модель "X"""", Json.parseToJsonElement(encoded).jsonPrimitive.content)
    }

    @Test
    fun `обратный слэш экранируется`() {
        val encoded = jsonString("""путь\к\файлу""")

        assertEquals("""путь\к\файлу""", Json.parseToJsonElement(encoded).jsonPrimitive.content)
    }

    @Test
    fun `перевод строки экранируется, а не рвёт тело на строки`() {
        val encoded = jsonString("первая\nвторая")

        assertTrue(!encoded.contains("\n"), "сырой перевод строки недопустим в JSON-строке")
        assertEquals("первая\nвторая", Json.parseToJsonElement(encoded).jsonPrimitive.content)
    }

    @Test
    fun `управляющий символ уходит в u-escape`() {
        val encoded = jsonString("до\u0007после")

        assertEquals("до\u0007после", Json.parseToJsonElement(encoded).jsonPrimitive.content)
    }

    @Test
    fun `кириллица не портится`() {
        val encoded = jsonString("Нидерланды")

        assertEquals("Нидерланды", Json.parseToJsonElement(encoded).jsonPrimitive.content)
    }
}
